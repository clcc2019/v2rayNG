package com.xray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.xray.ang.AppConfig
import com.xray.ang.AppConfig.LOOPBACK
import com.xray.ang.BuildConfig
import com.xray.ang.R
import com.xray.ang.contracts.ServiceControl
import com.xray.ang.contracts.Tun2SocksControl
import com.xray.ang.handler.MmkvManager
import com.xray.ang.handler.NotificationManager
import com.xray.ang.handler.SettingsManager
import com.xray.ang.handler.V2rayConfigManager
import com.xray.ang.handler.V2RayServiceManager
import com.xray.ang.util.MessageUtil
import com.xray.ang.util.MyContextWrapper
import com.xray.ang.util.Utils
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@SuppressLint("VpnServicePolicy")
class V2RayVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isStarting = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)
    private val networkCallbackRegistered = AtomicBoolean(false)

    /**destroy
     * Unfortunately registerDefaultNetworkCallback is going to return our VPN interface: https://android.googlesource.com/platform/frameworks/base/+/dda156ab0c5d66ad82bdcf76cda07cbc0a9c8a2e
     *
     * This makes doing a requestNetwork with REQUEST necessary so that we don't get ALL possible networks that
     * satisfies default network capabilities but only THE default network. Unfortunately we need to have
     * android.permission.CHANGE_NETWORK_STATE to be able to call requestNetwork.
     *
     * Source: https://android.googlesource.com/platform/frameworks/base/+/2df4c7d/services/core/java/com/android/server/ConnectivityService.java#887
     */
    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkRequest by lazy {
        NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
    }

    private val connectivity by lazy { getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager }

    @delegate:RequiresApi(Build.VERSION_CODES.P)
    private val defaultNetworkCallback by lazy {
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                // it's a good idea to refresh capabilities
                setUnderlyingNetworks(arrayOf(network))
            }

            override fun onLost(network: Network) {
                setUnderlyingNetworks(null)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            val policy = StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build()
            StrictMode.setThreadPolicy(policy)
        }
        V2RayServiceManager.bindServiceControl(this)
    }

    override fun onRevoke() {
        stopService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        NotificationManager.cancelNotification()
        V2RayServiceManager.onServiceDestroyed(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service may be restarted on the same instance before onDestroy runs.
        // Rebind the control handle so manager lookups do not see a stale null reference.
        V2RayServiceManager.bindServiceControl(this)
        startService()
        return START_STICKY
        //return super.onStartCommand(intent, flags, startId)
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!isStarting.compareAndSet(false, true)) {
            Log.d(AppConfig.TAG, "VPN start already in progress, skipping duplicate request")
            return
        }

        isStopping.set(false)
        serviceScope.launch {
            try {
                val startAt = SystemClock.elapsedRealtime()
                val guid = MmkvManager.getSelectServer()
                if (guid.isNullOrEmpty()) {
                    Log.e(AppConfig.TAG, "Failed to start VPN: no selected server")
                    MessageUtil.sendMsg2UI(this@V2RayVpnService, AppConfig.MSG_STATE_START_FAILURE, "")
                    stopSelf()
                    return@launch
                }
                V2RayServiceManager.sendStartingPhase(this@V2RayVpnService, R.string.connection_preparing_config)
                val knownProfile = V2RayServiceManager.consumePendingStartProfile(guid)
                val configDeferred = async {
                    V2rayConfigManager.getV2rayConfig(this@V2RayVpnService, guid, knownProfile)
                }
                V2RayServiceManager.sendStartingPhase(this@V2RayVpnService, R.string.connection_preparing_vpn)
                if (!setupVpnService()) {
                    Log.e(AppConfig.TAG, "Failed to setup VPN service")
                    isStopping.set(true)
                    MessageUtil.sendMsg2UI(this@V2RayVpnService, AppConfig.MSG_STATE_START_FAILURE, "")
                    stopAllService(isForced = true)
                    configDeferred.cancel()
                    return@launch
                }
                if (isStopping.get()) {
                    Log.i(AppConfig.TAG, "VPN start aborted because stop is in progress")
                    configDeferred.cancel()
                    return@launch
                }
                if (!::mInterface.isInitialized) {
                    Log.e(AppConfig.TAG, "Failed to create VPN interface")
                    isStopping.set(true)
                    stopAllService(isForced = true)
                    configDeferred.cancel()
                    return@launch
                }
                val configResult = configDeferred.await()
                if (!configResult.status) {
                    Log.e(AppConfig.TAG, "Failed to build V2Ray config")
                    isStopping.set(true)
                    MessageUtil.sendMsg2UI(this@V2RayVpnService, AppConfig.MSG_STATE_START_FAILURE, "")
                    stopAllService(isForced = true)
                    return@launch
                }
                V2RayServiceManager.sendStartingPhase(this@V2RayVpnService, R.string.connection_starting_core)
                if (!V2RayServiceManager.startCoreLoop(mInterface, configResult)) {
                    Log.e(AppConfig.TAG, "Failed to start V2Ray core loop")
                    MessageUtil.sendMsg2UI(this@V2RayVpnService, AppConfig.MSG_STATE_START_FAILURE, "")
                    isStopping.set(true)
                    stopAllService(isForced = true)
                    return@launch
                }
                registerPlatformNetworkCallback()
                Log.i(AppConfig.TAG, "VPN start finished in ${SystemClock.elapsedRealtime() - startAt}ms")
            } finally {
                isStarting.set(false)
            }
        }
    }

    override fun stopService() {
        if (!isStopping.compareAndSet(false, true)) {
            Log.d(AppConfig.TAG, "VPN stop already in progress, skipping duplicate request")
            return
        }

        serviceScope.launch {
            try {
                V2RayServiceManager.sendStoppingPhase(this@V2RayVpnService, R.string.connection_stopping_tun)
                stopAllService(true)
                V2RayServiceManager.onServiceStopCompleted(this@V2RayVpnService)
            } finally {
                isStarting.set(false)
                isStopping.set(false)
            }
        }
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        val startAt = SystemClock.elapsedRealtime()
        val prepare = prepare(this)
        if (prepare != null) {
            Log.e(AppConfig.TAG, "VPN preparation failed - VPN permission not granted")
            stopSelf()
            return false
        }

        val configureStartAt = SystemClock.elapsedRealtime()
        if (!configureVpnService()) {
            Log.e(AppConfig.TAG, "VPN configuration failed")
            return false
        }
                Log.i(AppConfig.TAG, "VPN configure finished in ${SystemClock.elapsedRealtime() - configureStartAt}ms")

        val tunStartAt = SystemClock.elapsedRealtime()
        V2RayServiceManager.sendStartingPhase(this, R.string.connection_starting_tun)
        if (!runTun2socks()) {
            Log.e(AppConfig.TAG, "Failed to start tun2socks")
            return false
        }
        Log.i(AppConfig.TAG, "tun2socks setup finished in ${SystemClock.elapsedRealtime() - tunStartAt}ms")
        Log.i(AppConfig.TAG, "VPN setup finished in ${SystemClock.elapsedRealtime() - startAt}ms")
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val startAt = SystemClock.elapsedRealtime()
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        val networkStartAt = SystemClock.elapsedRealtime()
        configureNetworkSettings(builder)
        Log.i(AppConfig.TAG, "VPN network settings configured in ${SystemClock.elapsedRealtime() - networkStartAt}ms")

        // Configure app-specific settings (session name and per-app proxy)
        val perAppStartAt = SystemClock.elapsedRealtime()
        configurePerAppProxy(builder)
        Log.i(AppConfig.TAG, "VPN per-app rules configured in ${SystemClock.elapsedRealtime() - perAppStartAt}ms")

        // Close the old interface since the parameters have been changed
        try {
            if (::mInterface.isInitialized) {
                mInterface.close()
            }
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "Failed to close old interface", e)
        }

        // Configure non-blocking platform builder features only.
        val platformStartAt = SystemClock.elapsedRealtime()
        configurePlatformFeatures(builder)
        Log.i(AppConfig.TAG, "VPN builder platform features configured in ${SystemClock.elapsedRealtime() - platformStartAt}ms")

        // Create a new interface using the builder and save the parameters
        try {
            val establishStartAt = SystemClock.elapsedRealtime()
            val iface = builder.establish()
            if (iface == null) {
                Log.e(AppConfig.TAG, "Failed to establish VPN interface: builder returned null")
                return false
            }
            mInterface = iface
            isRunning = true
            Log.i(AppConfig.TAG, "VPN interface established in ${SystemClock.elapsedRealtime() - establishStartAt}ms")
            Log.i(AppConfig.TAG, "VPN configureVpnService total ${SystemClock.elapsedRealtime() - startAt}ms")
            return true
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to establish VPN interface", e)
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val startAt = SystemClock.elapsedRealtime()
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_PREFIXES.forEach { (address, prefix) ->
                builder.addRoute(address, prefix)
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        val vpnDns = SettingsManager.getVpnDnsServers().take(2)
        vpnDns.forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        builder.setSession(V2RayServiceManager.getRunningServerName())
        Log.i(AppConfig.TAG, "VPN routes=${if (bypassLan) AppConfig.ROUTED_IP_PREFIXES.size else 1}, dns=${vpnDns.size}, ipv6=${MmkvManager.decodeSettingsBool(AppConfig.PREF_PREFER_IPV6) == true}, configureNetworkSettings took ${SystemClock.elapsedRealtime() - startAt}ms")
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    private fun registerPlatformNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }
        if (!networkCallbackRegistered.compareAndSet(false, true)) {
            return
        }
        serviceScope.launch {
            try {
                connectivity.requestNetwork(defaultNetworkRequest, defaultNetworkCallback)
            } catch (e: Exception) {
                networkCallbackRegistered.set(false)
                Log.e(AppConfig.TAG, "Failed to request default network", e)
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val startAt = SystemClock.elapsedRealtime()
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(AppConfig.TAG, "Failed to configure app in VPN: ${e.localizedMessage}", e)
            }
        }
        Log.i(AppConfig.TAG, "VPN per-app package count=${apps.size}, bypassMode=${bypassApps == true}, configurePerAppProxy took ${SystemClock.elapsedRealtime() - startAt}ms")
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks(): Boolean {
        if (isStopping.get()) {
            return false
        }

        if (!TProxyService.isAvailable()) {
            Log.e(AppConfig.TAG, "Hev tun native library is unavailable")
            return false
        }

        tun2SocksService = TProxyService(
            context = applicationContext,
            vpnInterface = mInterface,
            isRunningProvider = { isRunning },
            restartCallback = { runTun2socks() }
        )

        tun2SocksService?.startTun2Socks()
        return true
    }

    private fun stopAllService(isForced: Boolean = true) {
        val startAt = SystemClock.elapsedRealtime()
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        isRunning = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && networkCallbackRegistered.compareAndSet(true, false)) {
            try {
                connectivity.unregisterNetworkCallback(defaultNetworkCallback)
            } catch (e: Exception) {
                Log.w(AppConfig.TAG, "Failed to unregister network callback", e)
            }
        }

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null

        V2RayServiceManager.sendStoppingPhase(this, R.string.connection_stopping_core)
        V2RayServiceManager.stopCoreLoop()

        if (isForced) {
            V2RayServiceManager.sendStoppingPhase(this, R.string.connection_releasing_service)
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            try {
                if (::mInterface.isInitialized) {
                    mInterface.close()
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to close VPN interface", e)
            }
        }

        Log.i(AppConfig.TAG, "VPN stop finished in ${SystemClock.elapsedRealtime() - startAt}ms")
    }
}

package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.V2rayConfigManager
import com.v2ray.ang.handler.V2RayServiceManager
import com.v2ray.ang.util.MyContextWrapper
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class V2RayProxyOnlyService : Service(), ServiceControl {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isStarting = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        V2RayServiceManager.serviceControl = SoftReference(this)
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startService()
        return START_STICKY
    }

    /**
     * Destroys the service.
     */
    override fun onDestroy() {
        if (!isStopping.get()) {
            V2RayServiceManager.stopCoreLoop()
        }
        V2RayServiceManager.onServiceDestroyed(this)
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    override fun getService(): Service {
        return this
    }

    /**
     * Starts the service.
     */
    override fun startService() {
        if (!isStarting.compareAndSet(false, true)) {
            Log.d(AppConfig.TAG, "Proxy-only start already in progress, skipping duplicate request")
            return
        }

        isStopping.set(false)
        serviceScope.launch {
            try {
                val startAt = SystemClock.elapsedRealtime()
                val guid = MmkvManager.getSelectServer()
                if (guid.isNullOrEmpty()) {
                    Log.e(AppConfig.TAG, "Failed to start proxy-only: no selected server")
                    stopSelf()
                    return@launch
                }
                val knownProfile = V2RayServiceManager.consumePendingStartProfile(guid)
                val configResult = V2rayConfigManager.getV2rayConfig(this@V2RayProxyOnlyService, guid, knownProfile)
                if (!configResult.status) {
                    Log.e(AppConfig.TAG, "Failed to build proxy-only V2Ray config")
                    stopSelf()
                    return@launch
                }
                if (!V2RayServiceManager.startCoreLoop(null, configResult)) {
                    Log.e(AppConfig.TAG, "Failed to start proxy-only core loop")
                    stopSelf()
                    return@launch
                }
                Log.i(AppConfig.TAG, "Proxy-only start finished in ${SystemClock.elapsedRealtime() - startAt}ms")
            } finally {
                isStarting.set(false)
            }
        }
    }

    /**
     * Stops the service.
     */
    override fun stopService() {
        if (!isStopping.compareAndSet(false, true)) {
            Log.d(AppConfig.TAG, "Proxy-only stop already in progress, skipping duplicate request")
            return
        }

        serviceScope.launch {
            try {
                val startAt = SystemClock.elapsedRealtime()
                V2RayServiceManager.stopCoreLoop()
                stopSelf()
                V2RayServiceManager.onServiceStopCompleted(this@V2RayProxyOnlyService)
                Log.i(AppConfig.TAG, "Proxy-only stop finished in ${SystemClock.elapsedRealtime() - startAt}ms")
            } finally {
                isStarting.set(false)
                isStopping.set(false)
            }
        }
    }

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Attaches the base context to the service.
     * @param newBase The new base context.
     */
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let {
            MyContextWrapper.wrap(newBase, SettingsManager.getLocale())
        }
        super.attachBaseContext(context)
    }
}

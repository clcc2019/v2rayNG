package com.xray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.xray.ang.R
import com.xray.ang.contracts.ServiceControl
import com.xray.ang.AppConfig
import com.xray.ang.handler.MmkvManager
import com.xray.ang.handler.SettingsManager
import com.xray.ang.handler.V2rayConfigManager
import com.xray.ang.handler.V2RayServiceManager
import com.xray.ang.util.MessageUtil
import com.xray.ang.util.MyContextWrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class V2RayProxyOnlyService : Service(), ServiceControl {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val transitionGuard = ServiceTransitionGuard("Proxy-only")

    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        V2RayServiceManager.bindServiceControl(this)
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Service may be restarted on the same instance before onDestroy runs.
        // Rebind the control handle so manager lookups do not see a stale null reference.
        V2RayServiceManager.bindServiceControl(this)
        V2RayServiceRuntime.ensureForegroundStarted()
        startService()
        return START_STICKY
    }

    /**
     * Destroys the service.
     */
    override fun onDestroy() {
        if (!transitionGuard.isStopRequested()) {
            Log.w(AppConfig.TAG, "Proxy-only service destroyed without explicit stop, running safety-net cleanup")
            V2RayServiceManager.stopCoreLoop()
            V2RayServiceManager.onServiceStopCompleted()
            V2RayServiceManager.sendStopSuccess(this)
        }
        V2RayServiceManager.onServiceDestroyed(this)
        V2RayServiceRuntime.terminateProcessIfIdle()
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
        if (!transitionGuard.beginStart()) {
            return
        }
        V2RayServiceManager.onServiceStartAccepted()

        serviceScope.launch {
            try {
                val startAt = SystemClock.elapsedRealtime()
                val guid = MmkvManager.getSelectServer()
                if (guid.isNullOrEmpty()) {
                    Log.e(AppConfig.TAG, "Failed to start proxy-only: no selected server")
                    abortStart("")
                    return@launch
                }
                V2RayServiceManager.sendStartingPhase(this@V2RayProxyOnlyService, R.string.connection_preparing_config)
                val knownProfile = V2RayServiceManager.consumePendingStartProfile(guid)
                val configResult = V2rayConfigManager.getV2rayConfig(this@V2RayProxyOnlyService, guid, knownProfile)
                if (!configResult.status) {
                    Log.e(AppConfig.TAG, "Failed to build proxy-only V2Ray config")
                    abortStart(configResult.errorResId ?: "")
                    return@launch
                }
                V2RayServiceManager.sendStartingPhase(this@V2RayProxyOnlyService, R.string.connection_starting_core)
                if (!V2RayServiceManager.startCoreLoop(null, configResult)) {
                    Log.e(AppConfig.TAG, "Failed to start proxy-only core loop")
                    abortStart("")
                    return@launch
                }
                Log.i(AppConfig.TAG, "Proxy-only start finished in ${SystemClock.elapsedRealtime() - startAt}ms")
            } finally {
                transitionGuard.finishStart()
            }
        }
    }

    private fun abortStart(errorInfo: java.io.Serializable) {
        transitionGuard.requestStop()
        MessageUtil.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, errorInfo)
        V2RayServiceManager.stopCoreLoop()
        V2RayServiceManager.onServiceStopCompleted()
        V2RayServiceManager.sendStopSuccess(this)
        stopSelf()
    }

    /**
     * Stops the service.
     */
    override fun stopService() {
        if (!transitionGuard.beginStop()) {
            return
        }

        serviceScope.launch {
            try {
                val startAt = SystemClock.elapsedRealtime()
                V2RayServiceManager.sendStoppingPhase(this@V2RayProxyOnlyService, R.string.connection_stopping_core)
                V2RayServiceManager.sendStoppingPhase(this@V2RayProxyOnlyService, R.string.connection_releasing_service)
                V2RayServiceManager.stopCoreLoop()
                V2RayServiceManager.onServiceStopCompleted()
                V2RayServiceManager.sendStopSuccess(this@V2RayProxyOnlyService)
                stopSelf()
                Log.i(AppConfig.TAG, "Proxy-only stop finished in ${SystemClock.elapsedRealtime() - startAt}ms")
            } finally {
                transitionGuard.finishStop()
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

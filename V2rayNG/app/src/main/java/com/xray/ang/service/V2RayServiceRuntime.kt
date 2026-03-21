package com.xray.ang.service

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.xray.ang.AppConfig
import com.xray.ang.handler.NotificationManager
import com.xray.ang.handler.V2RayServiceManager

internal class ServiceTransitionGuard(private val serviceName: String) {
    enum class Phase { IDLE, STARTING, RUNNING, STOPPING }

    private val lock = Any()
    private var phase = Phase.IDLE
    private var stopRequested = false

    fun beginStart(): Boolean = synchronized(lock) {
        if (phase != Phase.IDLE) {
            Log.d(AppConfig.TAG, "$serviceName start rejected in phase=$phase")
            return false
        }
        phase = Phase.STARTING
        stopRequested = false
        true
    }

    fun finishStart(): Unit = synchronized(lock) {
        if (phase == Phase.STARTING && !stopRequested) {
            phase = Phase.RUNNING
        }
    }

    fun requestStop(): Unit = synchronized(lock) {
        if (phase == Phase.STARTING) {
            phase = Phase.STOPPING
        }
        stopRequested = true
    }

    fun isStopRequested(): Boolean = synchronized(lock) { stopRequested }

    fun beginStop(): Boolean = synchronized(lock) {
        if (phase == Phase.IDLE || phase == Phase.STOPPING) {
            Log.d(AppConfig.TAG, "$serviceName stop rejected in phase=$phase")
            return false
        }
        phase = Phase.STOPPING
        stopRequested = true
        true
    }

    fun finishStop(): Unit = synchronized(lock) {
        phase = Phase.IDLE
        stopRequested = false
    }

    fun currentPhase(): Phase = synchronized(lock) { phase }
}

internal object V2RayServiceRuntime {
    fun ensureForegroundStarted() {
        NotificationManager.showNotification(null)
    }

    fun terminateProcessIfIdle() {
        if (!V2RayServiceManager.shouldTerminateDaemonProcess()) {
            return
        }

        Handler(Looper.getMainLooper()).post {
            Log.i(AppConfig.TAG, "Terminating daemon process after service stop to avoid stale native runtime reuse")
            Process.killProcess(Process.myPid())
        }
    }
}

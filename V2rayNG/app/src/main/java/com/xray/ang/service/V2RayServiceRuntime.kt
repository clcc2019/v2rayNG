package com.xray.ang.service

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.xray.ang.AppConfig
import com.xray.ang.handler.NotificationManager
import com.xray.ang.handler.V2RayServiceManager
import java.util.concurrent.atomic.AtomicBoolean

internal class ServiceTransitionGuard(private val serviceName: String) {
    private val isStarting = AtomicBoolean(false)
    private val isStopping = AtomicBoolean(false)

    fun beginStart(): Boolean {
        if (!isStarting.compareAndSet(false, true)) {
            Log.d(AppConfig.TAG, "$serviceName start already in progress, skipping duplicate request")
            return false
        }
        isStopping.set(false)
        return true
    }

    fun finishStart() {
        isStarting.set(false)
    }

    fun requestStop() {
        isStopping.set(true)
    }

    fun isStopRequested(): Boolean = isStopping.get()

    fun beginStop(): Boolean {
        if (!isStopping.compareAndSet(false, true)) {
            Log.d(AppConfig.TAG, "$serviceName stop already in progress, skipping duplicate request")
            return false
        }
        return true
    }

    fun finishStop() {
        isStarting.set(false)
        isStopping.set(false)
    }
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

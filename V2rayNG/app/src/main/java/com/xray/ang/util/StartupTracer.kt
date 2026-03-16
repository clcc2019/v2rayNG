package com.xray.ang.util

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.xray.ang.BuildConfig

object StartupTracer {
    private const val TAG = "StartupTrace"
    @Volatile
    private var appStartElapsedMs: Long = 0L

    fun markAppStart() {
        if (!BuildConfig.DEBUG) return
        if (appStartElapsedMs == 0L) {
            appStartElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun mark(label: String) {
        if (!BuildConfig.DEBUG) return
        val start = appStartElapsedMs
        if (start == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - start
        Log.i(TAG, "$label: ${elapsed}ms")
    }

    fun beginSection(name: String) {
        if (!BuildConfig.DEBUG) return
        Trace.beginSection(name)
    }

    fun endSection() {
        if (!BuildConfig.DEBUG) return
        Trace.endSection()
    }
}

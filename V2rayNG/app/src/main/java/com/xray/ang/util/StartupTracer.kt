package com.xray.ang.util

import android.os.SystemClock
import android.os.Trace
import android.util.Log
import com.xray.ang.BuildConfig

object StartupTracer {
    private const val TAG = "StartupTrace"

    @Volatile
    private var appStartElapsedMs: Long = 0L

    @Volatile
    var onCreateElapsedMs: Long = 0L
        private set

    @Volatile
    var firstFrameElapsedMs: Long = 0L
        private set

    fun markAppStart() {
        if (appStartElapsedMs == 0L) {
            appStartElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    fun mark(label: String) {
        val start = appStartElapsedMs
        if (start == 0L) return
        val elapsed = SystemClock.elapsedRealtime() - start

        when (label) {
            "MainActivity.onCreate.end" -> onCreateElapsedMs = elapsed
            "MainActivity.firstFrame" -> firstFrameElapsedMs = elapsed
        }

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "$label: ${elapsed}ms")
        }
    }

    fun beginSection(name: String) {
        if (!BuildConfig.DEBUG) return
        Trace.beginSection(name)
    }

    fun endSection() {
        if (!BuildConfig.DEBUG) return
        Trace.endSection()
    }

    fun getStartupSummary(): String? {
        if (firstFrameElapsedMs <= 0L) return null
        return "onCreate=${onCreateElapsedMs}ms, firstFrame=${firstFrameElapsedMs}ms"
    }
}

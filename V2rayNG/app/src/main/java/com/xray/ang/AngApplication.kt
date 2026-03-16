package com.xray.ang

import android.content.Context
import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.tencent.mmkv.MMKV
import com.xray.ang.handler.SettingsManager
import com.xray.ang.util.StartupTracer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AngApplication : Application() {
    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
        StartupTracer.markAppStart()
    }

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .build()
    @Volatile
    private var workManagerInitialized = false
    private val workManagerLock = Any()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        StartupTracer.beginSection("App.onCreate")
        try {
            super.onCreate()

            MMKV.initialize(this)

            // Ensure critical preference defaults are present in MMKV early
            SettingsManager.initAppFast(this)
            SettingsManager.setNightMode()

            CoroutineScope(Dispatchers.Default).launch {
                SettingsManager.initAppDeferred(this@AngApplication)
            }
        } finally {
            StartupTracer.endSection()
        }
    }

    fun ensureWorkManagerInitialized() {
        if (workManagerInitialized) return
        synchronized(workManagerLock) {
            if (workManagerInitialized) return
            WorkManager.initialize(this, workManagerConfiguration)
            workManagerInitialized = true
        }
    }
}

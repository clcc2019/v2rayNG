package com.v2ray.ang.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG
import com.v2ray.ang.AppConfig.MSG_MEASURE_CONFIG_CANCEL
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.extension.serializable
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.util.MessageUtil

class V2RayTestService : Service() {
    private val workerLock = Any()
    private var activeWorker: RealPingWorkerService? = null

    /**
     * Initializes the V2Ray environment.
     */
    override fun onCreate() {
        super.onCreate()
        V2RayNativeManager.initCoreEnv(this)
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
     * Cleans up resources when the service is destroyed.
     */
    override fun onDestroy() {
        super.onDestroy()
        cancelActiveWorker()
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val message = intent?.serializable<TestServiceMessage>("content") ?: return START_NOT_STICKY
        when (message.key) {
            MSG_MEASURE_CONFIG -> {
                val guidsList = if (message.serverGuids.isNotEmpty()) {
                    message.serverGuids
                } else if (message.subscriptionId.isNotEmpty()) {
                    MmkvManager.decodeServerList(message.subscriptionId)
                } else {
                    MmkvManager.decodeAllServerList()
                }

                if (guidsList.isNotEmpty()) {
                    cancelActiveWorker()
                    lateinit var worker: RealPingWorkerService
                    worker = RealPingWorkerService(this, guidsList) { status ->
                        MessageUtil.sendMsg2UI(this@V2RayTestService, AppConfig.MSG_MEASURE_CONFIG_FINISH, status)
                        clearWorker(worker)
                        stopSelfResult(startId)
                    }
                    synchronized(workerLock) {
                        activeWorker = worker
                    }
                    worker.start()
                } else {
                    stopSelfResult(startId)
                }
            }

            MSG_MEASURE_CONFIG_CANCEL -> {
                cancelActiveWorker()
                stopSelfResult(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun clearWorker(worker: RealPingWorkerService) {
        synchronized(workerLock) {
            if (activeWorker === worker) {
                activeWorker = null
            }
        }
    }

    private fun cancelActiveWorker() {
        synchronized(workerLock) {
            activeWorker?.cancel()
            activeWorker = null
        }
    }
}

package com.xray.ang.service

import android.content.Context
import com.xray.ang.AppConfig
import com.xray.ang.handler.SettingsManager
import com.xray.ang.handler.V2RayNativeManager
import com.xray.ang.handler.V2rayConfigManager
import com.xray.ang.util.MessageUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Worker that runs a batch of real-ping tests independently.
 * Each batch owns its own CoroutineScope/dispatcher and can be cancelled separately.
 */
class RealPingWorkerService(
    private val context: Context,
    private val guids: List<String>,
    private val workerDispatcher: CoroutineDispatcher = DEFAULT_REAL_PING_DISPATCHER,
    private val onFinish: (status: String) -> Unit = {}
) {
    companion object {
        private val DEFAULT_PARALLELISM = Runtime.getRuntime().availableProcessors().coerceAtLeast(1).coerceAtMost(6)
        private val DEFAULT_REAL_PING_DISPATCHER = Dispatchers.IO.limitedParallelism(DEFAULT_PARALLELISM)
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + workerDispatcher + CoroutineName("RealPingBatchWorker"))

    private val runningCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)

    fun start() {
        val jobs = guids.map { guid ->
            scope.launch {
                runningCount.incrementAndGet()
                try {
                    val result = startRealPing(guid)
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_SUCCESS, Pair(guid, result))
                } finally {
                    val completed = completedCount.incrementAndGet()
                    val left = runningCount.decrementAndGet()
                    MessageUtil.sendMsg2UI(context, AppConfig.MSG_MEASURE_CONFIG_NOTIFY, "$left / ${guids.size - completed}")
                }
            }
        }

        scope.launch {
            try {
                joinAll(*jobs.toTypedArray())
                onFinish("0")
            } catch (_: CancellationException) {
                onFinish("-1")
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    private fun startRealPing(guid: String): Long {
        val retFailure = -1L
        val configResult = V2rayConfigManager.getV2rayConfig4Speedtest(context, guid)
        if (!configResult.status) {
            return retFailure
        }
        return V2RayNativeManager.measureOutboundDelay(configResult.content, SettingsManager.getDelayTestUrl())
    }
}

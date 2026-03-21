package com.xray.ang.domain.main

import android.content.Context
import com.xray.ang.AppConfig
import com.xray.ang.dto.TestServiceMessage
import com.xray.ang.handler.SpeedtestManager
import com.xray.ang.util.MessageUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ServerTestTarget(
    val guid: String,
    val serverAddress: String,
    val serverPort: Int
)

data class ServerTestResult(
    val guid: String,
    val delayMillis: Long
)

class MainServerTestCoordinator(
    private val tcpingDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(
        Runtime.getRuntime().availableProcessors().coerceAtLeast(2) * 2
    ),
    private val tcpingRunner: suspend (String, Int, SpeedtestManager.TcpingOptions) -> Long =
        { host, port, options -> SpeedtestManager.tcping(host, port, options) },
    private val closeAllTcpSockets: () -> Unit = SpeedtestManager::closeAllTcpSockets,
    private val sendTestServiceMessage: (Context, TestServiceMessage) -> Unit = MessageUtil::sendMsg2TestService,
    private val sendServiceMessage: (Context, Int, String) -> Unit = MessageUtil::sendMsg2Service
) {
    companion object {
        internal val BULK_TCPING_OPTIONS = SpeedtestManager.TcpingOptions()
        internal val SINGLE_TCPING_OPTIONS = SpeedtestManager.INTERACTIVE_TCPING_OPTIONS
    }

    suspend fun runTcping(
        targets: List<ServerTestTarget>,
        onResult: suspend (ServerTestResult) -> Unit
    ) {
        coroutineScope {
            targets.forEach { target ->
                launch(tcpingDispatcher) {
                    val delayMillis = tcpingRunner(
                        target.serverAddress,
                        target.serverPort,
                        BULK_TCPING_OPTIONS
                    )
                    onResult(ServerTestResult(target.guid, delayMillis))
                }
            }
        }
    }

    suspend fun testTcping(target: ServerTestTarget): ServerTestResult {
        val delayMillis = withContext(tcpingDispatcher) {
            tcpingRunner(
                target.serverAddress,
                target.serverPort,
                SINGLE_TCPING_OPTIONS
            )
        }
        return ServerTestResult(target.guid, delayMillis)
    }

    fun cancelTcping() {
        closeAllTcpSockets()
    }

    fun cancelRealPing(context: Context) {
        sendTestServiceMessage(
            context,
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
    }

    fun requestRealPing(
        context: Context,
        subscriptionId: String,
        visibleServerGuids: List<String>,
        isKeywordFiltering: Boolean
    ) {
        sendTestServiceMessage(
            context,
            TestServiceMessage(
                key = AppConfig.MSG_MEASURE_CONFIG,
                subscriptionId = subscriptionId,
                serverGuids = if (isKeywordFiltering) visibleServerGuids else emptyList()
            )
        )
    }

    fun requestCurrentServerRealPing(context: Context) {
        sendServiceMessage(context, AppConfig.MSG_MEASURE_DELAY, "")
    }
}

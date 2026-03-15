package com.v2ray.ang.domain.main

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.util.MessageUtil
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
    private val tcpingRunner: suspend (String, Int) -> Long = SpeedtestManager::tcping,
    private val closeAllTcpSockets: () -> Unit = SpeedtestManager::closeAllTcpSockets,
    private val sendTestServiceMessage: (Context, TestServiceMessage) -> Unit = MessageUtil::sendMsg2TestService,
    private val sendServiceMessage: (Context, Int, String) -> Unit = MessageUtil::sendMsg2Service
) {
    suspend fun runTcping(
        targets: List<ServerTestTarget>,
        onResult: suspend (ServerTestResult) -> Unit
    ) {
        coroutineScope {
            targets.forEach { target ->
                launch(tcpingDispatcher) {
                    val delayMillis = tcpingRunner(target.serverAddress, target.serverPort)
                    onResult(ServerTestResult(target.guid, delayMillis))
                }
            }
        }
    }

    suspend fun testTcping(target: ServerTestTarget): ServerTestResult {
        val delayMillis = withContext(tcpingDispatcher) {
            tcpingRunner(target.serverAddress, target.serverPort)
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

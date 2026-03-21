package com.xray.ang.domain.main

import android.content.Context
import com.xray.ang.AppConfig
import com.xray.ang.dto.TestServiceMessage
import com.xray.ang.handler.SpeedtestManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import java.util.Collections

class MainServerTestCoordinatorTest {
    @Test
    fun runTcping_emitsAllResults() = runBlocking {
        val emitted = Collections.synchronizedList(mutableListOf<ServerTestResult>())
        val coordinator = MainServerTestCoordinator(
            tcpingRunner = { host, port, _ -> "$host:$port".length.toLong() }
        )

        coordinator.runTcping(
            listOf(
                ServerTestTarget("a", "1.1.1.1", 443),
                ServerTestTarget("b", "8.8.8.8", 80)
            )
        ) { result ->
            emitted += result
        }

        assertEquals(setOf("a", "b"), emitted.map { it.guid }.toSet())
        assertEquals(setOf(11L, 10L), emitted.map { it.delayMillis }.toSet())
    }

    @Test
    fun testTcping_usesInteractiveTcpingOptions() = runBlocking {
        var capturedOptions: SpeedtestManager.TcpingOptions? = null
        val coordinator = MainServerTestCoordinator(
            tcpingRunner = { _, _, options ->
                capturedOptions = options
                42L
            }
        )

        val result = coordinator.testTcping(ServerTestTarget("a", "1.1.1.1", 443))

        assertEquals(42L, result.delayMillis)
        assertEquals(1, capturedOptions?.attempts)
        assertEquals(1500, capturedOptions?.connectTimeoutMillis)
    }

    @Test
    fun requestRealPing_usesVisibleGuidsOnlyWhenFiltering() {
        val sentMessages = mutableListOf<TestServiceMessage>()
        val coordinator = MainServerTestCoordinator(
            sendTestServiceMessage = { _, message -> sentMessages += message }
        )

        coordinator.requestRealPing(fakeContext(), "sub1", listOf("a", "b"), isKeywordFiltering = true)
        coordinator.requestRealPing(fakeContext(), "sub1", listOf("a", "b"), isKeywordFiltering = false)

        assertEquals(listOf("a", "b"), sentMessages[0].serverGuids)
        assertEquals(emptyList<String>(), sentMessages[1].serverGuids)
        assertEquals(AppConfig.MSG_MEASURE_CONFIG, sentMessages[0].key)
    }

    @Test
    fun cancelRealPing_sendsCancelMessage() {
        val sentMessages = mutableListOf<TestServiceMessage>()
        val coordinator = MainServerTestCoordinator(
            sendTestServiceMessage = { _, message -> sentMessages += message }
        )

        coordinator.cancelRealPing(fakeContext())

        assertEquals(AppConfig.MSG_MEASURE_CONFIG_CANCEL, sentMessages.single().key)
    }

    private fun fakeContext(): Context = mock()
}

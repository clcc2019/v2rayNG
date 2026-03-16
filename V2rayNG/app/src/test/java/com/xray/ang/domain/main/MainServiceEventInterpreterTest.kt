package com.xray.ang.domain.main

import android.content.Intent
import com.xray.ang.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MainServiceEventInterpreterTest {
    @Test
    fun interpret_mapsStartFailureWithOptionalErrorId() {
        val withError = mock<Intent>().apply {
            whenever(getIntExtra("key", 0)).thenReturn(AppConfig.MSG_STATE_START_FAILURE)
            whenever(getIntExtra("content", 0)).thenReturn(123)
        }
        val withoutError = mock<Intent>().apply {
            whenever(getIntExtra("key", 0)).thenReturn(AppConfig.MSG_STATE_START_FAILURE)
            whenever(getIntExtra("content", 0)).thenReturn(0)
        }

        assertEquals(MainServiceEvent.ServiceStartFailure(123), MainServiceEventInterpreter.interpret(withError))
        assertEquals(MainServiceEvent.ServiceStartFailure(null), MainServiceEventInterpreter.interpret(withoutError))
    }

    @Test
    fun interpret_mapsConfigTestSuccessPair() {
        val intent = mock<Intent>().apply {
            whenever(getIntExtra("key", 0)).thenReturn(AppConfig.MSG_MEASURE_CONFIG_SUCCESS)
            @Suppress("DEPRECATION")
            whenever(getSerializableExtra("content")).thenReturn(Pair("guid-1", 42L))
        }

        assertEquals(
            MainServiceEvent.ConfigTestSuccess("guid-1", 42L),
            MainServiceEventInterpreter.interpret(intent)
        )
    }

    @Test
    fun interpret_returnsNullForUnknownMessage() {
        val intent = mock<Intent>().apply {
            whenever(getIntExtra("key", 0)).thenReturn(-1)
        }

        assertNull(MainServiceEventInterpreter.interpret(intent))
    }
}

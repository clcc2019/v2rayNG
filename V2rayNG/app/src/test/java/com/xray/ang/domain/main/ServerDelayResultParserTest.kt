package com.xray.ang.domain.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerDelayResultParserTest {
    @Test
    fun parseDelayMillis_supportsEnglishAndChineseUnits() {
        assertEquals(123L, ServerDelayResultParser.parseDelayMillis("delay 123 ms"))
        assertEquals(456L, ServerDelayResultParser.parseDelayMillis("耗时 456毫秒"))
    }

    @Test
    fun parseDelayResult_marksKnownFailures() {
        assertEquals(-1L, ServerDelayResultParser.parseDelayResult("request failed"))
        assertEquals(-1L, ServerDelayResultParser.parseDelayResult("无互联网"))
    }

    @Test
    fun parseDelayResult_returnsNullForIrrelevantContent() {
        assertNull(ServerDelayResultParser.parseDelayResult("pending"))
    }
}

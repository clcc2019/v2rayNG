package com.xray.ang.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionLandingInfoFormatterTest {

    @Test
    fun extract_keepsIpv4AddressReadable() {
        val content = """
            Connection test succeeded
            (US SJC) 203.0.113.10 · http=http/2 · tls=TLSv1.3
        """.trimIndent()

        val result = ConnectionLandingInfoFormatter.extract(content)

        assertEquals("203.0.113.10 · US SJC", result)
    }

    @Test
    fun extract_compactsIpv6AddressForDock() {
        val content = """
            Connection test succeeded
            (DE FRA) 2408:8207:20a0:6d10:9d66:b7f9:30c0:9dca · http=http/3 · tls=TLSv1.3
        """.trimIndent()

        val result = ConnectionLandingInfoFormatter.extract(content)

        assertEquals("2408:8207:…:30c0:9dca · DE FRA", result)
    }

    @Test
    fun extract_dropsUnknownLocationAndCompactsIpv6Address() {
        val content = """
            Connection test succeeded
            (unknown) 2001:0db8:85a3:0000:0000:8a2e:0370:7334 · http=http/2
        """.trimIndent()

        val result = ConnectionLandingInfoFormatter.extract(content)

        assertEquals("2001:0db8:…:0370:7334", result)
    }

    @Test
    fun extract_returnsNullWhenIpInfoLineIsMissing() {
        assertNull(ConnectionLandingInfoFormatter.extract("Connection test succeeded"))
    }
}

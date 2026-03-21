package com.xray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeedtestManagerTest {

    @Test
    fun parseRemoteIPInfo_parsesCloudflareTraceResponse() {
        val content = """
            fl=77f123
            h=cloudflare.com
            ip=203.0.113.10
            ts=1742467200.123
            visit_scheme=https
            colo=SJC
            http=http/2
            loc=US
            tls=TLSv1.3
            sni=plaintext
            warp=off
        """.trimIndent()

        val result = SpeedtestManager.parseRemoteIPInfo(content)

        assertEquals("(US SJC) 203.0.113.10 · http=http/2 · tls=TLSv1.3", result)
    }

    @Test
    fun parseRemoteIPInfo_fallsBackToLegacyJsonFormat() {
        val content = """{"ip":"198.51.100.7","country_code":"JP"}"""

        val result = SpeedtestManager.parseRemoteIPInfo(content)

        assertEquals("(JP) 198.51.100.7", result)
    }

    @Test
    fun parseRemoteIPInfo_returnsNullForUnknownPayload() {
        assertNull(SpeedtestManager.parseRemoteIPInfo("not-a-valid-response"))
    }
}

package com.xray.ang.handler.config

import com.xray.ang.AppConfig
import com.xray.ang.dto.ProfileItem
import com.xray.ang.dto.V2rayConfig
import com.xray.ang.enums.EConfigType
import com.xray.ang.handler.V2rayConfigManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2rayConfigManagerTest {

    @Test
    fun populateTlsSettings_ignoresInvalidEchForceQueryValue() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            server = "example.com"
            security = AppConfig.TLS
            echConfigList = "config-list"
            echForceQuery = "cloudflare-ech.com"
        }
        val streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean()

        V2rayConfigManager.populateTlsSettings(streamSettings, profile, null)

        assertEquals("config-list", streamSettings.tlsSettings?.echConfigList)
        assertNull(streamSettings.tlsSettings?.echForceQuery)
    }

    @Test
    fun populateTlsSettings_keepsSupportedEchForceQueryValue() {
        val profile = ProfileItem.create(EConfigType.VLESS).apply {
            server = "example.com"
            security = AppConfig.TLS
            echForceQuery = "full"
        }
        val streamSettings = V2rayConfig.OutboundBean.StreamSettingsBean()

        V2rayConfigManager.populateTlsSettings(streamSettings, profile, null)

        assertEquals("full", streamSettings.tlsSettings?.echForceQuery)
    }
}

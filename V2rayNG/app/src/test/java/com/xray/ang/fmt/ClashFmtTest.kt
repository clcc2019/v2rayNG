package com.xray.ang.fmt

import com.xray.ang.AppConfig
import com.xray.ang.enums.EConfigType
import com.xray.ang.enums.NetworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClashFmtTest {

    @Test
    fun parseSubscription_mapsSupportedProxyTypes() {
        val yaml = """
            proxies:
              - name: trojan-ws
                type: trojan
                server: trojan.example.com
                port: 443
                password: secret
                sni: edge.example.com
                skip-cert-verify: true
                network: ws
                ws-opts:
                  path: /ws
                  headers:
                    Host: cdn.example.com
              - name: ss-basic
                type: ss
                server: 1.2.3.4
                port: 8388
                cipher: aes-256-gcm
                password: pass
              - name: vmess-grpc
                type: vmess
                server: vmess.example.com
                port: 443
                uuid: 123e4567-e89b-12d3-a456-426614174000
                cipher: auto
                tls: true
                client-fingerprint: chrome
                alpn:
                  - h2
                  - http/1.1
                network: grpc
                grpc-opts:
                  grpc-service-name: api
              - name: vless-reality
                type: vless
                server: vless.example.com
                port: 8443
                uuid: 123e4567-e89b-12d3-a456-426614174001
                flow: xtls-rprx-vision
                servername: reality.example.com
                client-fingerprint: chrome
                reality-opts:
                  public-key: public-key-value
                  short-id: short-id-value
        """.trimIndent()

        val result = ClashFmt.parseSubscription(yaml)

        assertEquals(4, result.size)

        val trojan = result[0]
        assertEquals(EConfigType.TROJAN, trojan.configType)
        assertEquals(NetworkType.WS.type, trojan.network)
        assertEquals("cdn.example.com", trojan.host)
        assertEquals("/ws", trojan.path)
        assertEquals(AppConfig.TLS, trojan.security)
        assertEquals(true, trojan.insecure)
        assertEquals("edge.example.com", trojan.sni)
        assertEquals("http/1.1", trojan.alpn)

        val shadowsocks = result[1]
        assertEquals(EConfigType.SHADOWSOCKS, shadowsocks.configType)
        assertEquals("aes-256-gcm", shadowsocks.method)
        assertEquals("pass", shadowsocks.password)
        assertNull(shadowsocks.security)

        val vmess = result[2]
        assertEquals(EConfigType.VMESS, vmess.configType)
        assertEquals(NetworkType.GRPC.type, vmess.network)
        assertEquals("api", vmess.serviceName)
        assertEquals(AppConfig.TLS, vmess.security)
        assertEquals("chrome", vmess.fingerPrint)
        assertEquals("h2,http/1.1", vmess.alpn)

        val vless = result[3]
        assertEquals(EConfigType.VLESS, vless.configType)
        assertEquals(AppConfig.REALITY, vless.security)
        assertEquals("public-key-value", vless.publicKey)
        assertEquals("short-id-value", vless.shortId)
        assertEquals("xtls-rprx-vision", vless.flow)
        assertEquals("reality.example.com", vless.sni)
        assertNull(vless.echForceQuery)
    }

    @Test
    fun parseSubscription_mapsShadowsocksV2rayPluginWebsocket() {
        val yaml = """
            proxies:
              - name: ss-ws
                type: ss
                server: ss.example.com
                port: 443
                cipher: chacha20-ietf-poly1305
                password: secret
                plugin: v2ray-plugin
                plugin-opts:
                  mode: websocket
                  host: ws.example.com
                  path: /ray
                  tls: true
                  skip-cert-verify: true
        """.trimIndent()

        val result = ClashFmt.parseSubscription(yaml)

        assertEquals(1, result.size)
        val config = result.first()
        assertEquals(NetworkType.WS.type, config.network)
        assertEquals("ws.example.com", config.host)
        assertEquals("/ray", config.path)
        assertEquals(AppConfig.TLS, config.security)
        assertTrue(config.insecure == true)
        assertEquals("http/1.1", config.alpn)
    }

    @Test
    fun parseSubscription_mapsHysteria2AndWireguard() {
        val yaml = """
            proxies:
              - name: hy2-node
                type: hysteria2
                server: hy2.example.com
                port: 8443
                password: hy2-secret
                sni: hy2-sni.example.com
                skip-cert-verify: true
                alpn: [h3]
                obfs: salamander
                obfs-password: obfs-secret
                up: 100
                down: 200 Mbps
                ports: 20000-21000
                hop-interval: 30
              - name: wg-node
                type: wireguard
                ip: 172.16.0.2/32
                ipv6: 2606:4700:110:8f81:d551:a0:532e:a2b3/128
                private-key: wg-private
                mtu: 1280
                peers:
                  - server: wg.example.com
                    port: 51820
                    public-key: wg-public
                    pre-shared-key: wg-psk
                    reserved: [1, 2, 3]
        """.trimIndent()

        val result = ClashFmt.parseSubscription(yaml)

        assertEquals(2, result.size)

        val hy2 = result[0]
        assertEquals(EConfigType.HYSTERIA2, hy2.configType)
        assertEquals(NetworkType.HYSTERIA.type, hy2.network)
        assertEquals("hy2-secret", hy2.password)
        assertEquals("hy2-sni.example.com", hy2.sni)
        assertEquals("obfs-secret", hy2.obfsPassword)
        assertEquals("100Mbps", hy2.bandwidthUp)
        assertEquals("200Mbps", hy2.bandwidthDown)
        assertEquals("20000-21000", hy2.portHopping)
        assertEquals("30", hy2.portHoppingInterval)

        val wg = result[1]
        assertEquals(EConfigType.WIREGUARD, wg.configType)
        assertEquals("wg.example.com", wg.server)
        assertEquals("51820", wg.serverPort)
        assertEquals("wg-private", wg.secretKey)
        assertEquals("wg-public", wg.publicKey)
        assertEquals("wg-psk", wg.preSharedKey)
        assertEquals("1,2,3", wg.reserved)
        assertEquals(1280, wg.mtu)
        assertEquals("172.16.0.2/32,2606:4700:110:8f81:d551:a0:532e:a2b3/128", wg.localAddress)
    }

    @Test
    fun parseSubscription_fallsBackUnknownUuidProxyToVless() {
        val yaml = """
            proxies:
              - name: custom-ws
                type: custom
                server: edge.example.com
                port: 443
                uuid: 123e4567-e89b-12d3-a456-426614174002
                network: websocket
                tls: true
                ws-opts:
                  path: /gateway
                  headers:
                    Host: cdn.example.com
        """.trimIndent()

        val result = ClashFmt.parseSubscription(yaml)

        assertEquals(1, result.size)
        val config = result.first()
        assertEquals(EConfigType.VLESS, config.configType)
        assertEquals(NetworkType.WS.type, config.network)
        assertEquals(AppConfig.TLS, config.security)
        assertEquals("cdn.example.com", config.host)
        assertEquals("/gateway", config.path)
        assertEquals("none", config.method)
        assertEquals("http/1.1", config.alpn)
    }

    @Test
    fun parseSubscription_keepsVlessWhenEncryptionAndUnknownNetworkAreLoose() {
        val yaml = """
            proxies:
              - name: vless-loose
                type: vless
                server: vless.example.com
                port: 443
                uuid: 123e4567-e89b-12d3-a456-426614174003
                encryption: none
                network: unknown-transport
        """.trimIndent()

        val result = ClashFmt.parseSubscription(yaml)

        assertEquals(1, result.size)
        val config = result.first()
        assertEquals(EConfigType.VLESS, config.configType)
        assertEquals("none", config.method)
        assertEquals(NetworkType.TCP.type, config.network)
        assertNull(config.host)
        assertNull(config.path)
    }
}

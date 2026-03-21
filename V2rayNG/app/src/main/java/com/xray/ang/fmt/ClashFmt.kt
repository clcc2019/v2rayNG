package com.xray.ang.fmt

import android.util.Log
import com.xray.ang.AppConfig
import com.xray.ang.dto.ProfileItem
import com.xray.ang.enums.EConfigType
import com.xray.ang.enums.NetworkType
import com.xray.ang.util.JsonUtil
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

object ClashFmt {
    private val vlessFallbackNetworks = setOf(
        NetworkType.TCP.type,
        NetworkType.KCP.type,
        NetworkType.WS.type,
        NetworkType.HTTP_UPGRADE.type,
        NetworkType.XHTTP.type,
        NetworkType.HTTP.type,
        NetworkType.H2.type,
        NetworkType.GRPC.type,
    )

    fun parseSubscription(text: String?): List<ProfileItem> {
        if (text.isNullOrBlank()) return emptyList()

        return try {
            val load = Load(LoadSettings.builder().build())
            val root = asStringMap(load.loadFromString(text)) ?: return emptyList()
            val proxies = root["proxies"] as? List<*> ?: return emptyList()
            proxies.mapNotNull { proxy ->
                parseProxy(asStringMap(proxy) ?: return@mapNotNull null)
            }
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to parse Clash subscription YAML", e)
            emptyList()
        }
    }

    private fun parseProxy(raw: Map<String, Any?>): ProfileItem? {
        val type = string(raw, "type")?.lowercase()
        return when (type) {
            "vmess" -> parseVmess(raw)
            "vless" -> parseVless(raw)
            "trojan" -> parseTrojan(raw)
            "ss" -> parseShadowsocks(raw)
            "socks", "socks5" -> parseSocks(raw)
            "http" -> parseHttp(raw)
            "hysteria2" -> parseHysteria2(raw)
            "wireguard" -> parseWireguard(raw)
            else -> parseAsVless(raw)
        }
    }

    private fun parseVmess(raw: Map<String, Any?>): ProfileItem? {
        val config = createBaseProfile(EConfigType.VMESS, raw) ?: return null
        config.password = string(raw, "uuid") ?: return null
        config.method = string(raw, "cipher") ?: AppConfig.DEFAULT_SECURITY
        applyTransportSettings(config, raw) ?: return null
        applySecuritySettings(config, raw)
        applyDefaultTransportAlpn(config)
        return config
    }

    private fun parseVless(raw: Map<String, Any?>): ProfileItem? {
        val config = createBaseProfile(EConfigType.VLESS, raw) ?: return null
        config.password = string(raw, "uuid") ?: return null
        config.method = firstNonBlank(
            string(raw, "encryption"),
            string(raw, "cipher")
        ) ?: "none"
        config.flow = string(raw, "flow")
        applyTransportSettings(config, raw) ?: return null
        applySecuritySettings(config, raw)
        applyDefaultTransportAlpn(config)
        return config
    }

    private fun parseTrojan(raw: Map<String, Any?>): ProfileItem? {
        val config = createBaseProfile(EConfigType.TROJAN, raw) ?: return null
        config.password = string(raw, "password") ?: return null
        config.flow = string(raw, "flow")
        applyTransportSettings(config, raw) ?: return null
        applySecuritySettings(config, raw, forceTls = true)
        applyDefaultTransportAlpn(config)
        return config
    }

    private fun parseShadowsocks(raw: Map<String, Any?>): ProfileItem? {
        val config = createBaseProfile(EConfigType.SHADOWSOCKS, raw) ?: return null
        config.method = string(raw, "cipher")?.lowercase() ?: return null
        config.password = string(raw, "password") ?: return null
        if (!applyShadowsocksPlugin(config, raw)) {
            return null
        }
        applyDefaultTransportAlpn(config)
        return config
    }

    private fun parseSocks(raw: Map<String, Any?>): ProfileItem? {
        val config = createBaseProfile(EConfigType.SOCKS, raw) ?: return null
        config.username = string(raw, "username")
        config.password = string(raw, "password")
        return config
    }

    private fun parseHttp(raw: Map<String, Any?>): ProfileItem? {
        val config = createBaseProfile(EConfigType.HTTP, raw) ?: return null
        config.username = string(raw, "username")
        config.password = string(raw, "password")
        return config
    }

    private fun parseHysteria2(raw: Map<String, Any?>): ProfileItem? {
        val config = createBaseProfile(EConfigType.HYSTERIA2, raw) ?: return null
        config.password = string(raw, "password") ?: return null
        config.network = NetworkType.HYSTERIA.type
        config.security = AppConfig.TLS
        config.sni = firstNonBlank(
            string(raw, "sni"),
            string(raw, "servername"),
            string(raw, "server-name")
        )
        config.insecure = boolean(raw["skip-cert-verify"])
        config.pinnedCA256 = string(raw, "fingerprint")
        config.alpn = csv(stringList(raw["alpn"]))
        config.bandwidthUp = normalizeBandwidth(string(raw, "up"))
        config.bandwidthDown = normalizeBandwidth(string(raw, "down"))
        if (string(raw, "obfs")?.equals("salamander", ignoreCase = true) == true) {
            config.obfsPassword = string(raw, "obfs-password")
        }
        string(raw, "ports")?.let { config.portHopping = it }
        val hopInterval = intValue(raw["hop-interval"])?.takeIf { it >= 5 } ?: intValue(raw["hop_interval"])?.takeIf { it >= 5 }
        hopInterval?.let { config.portHoppingInterval = it.toString() }
        return config
    }

    private fun parseWireguard(raw: Map<String, Any?>): ProfileItem? {
        val config = ProfileItem.create(EConfigType.WIREGUARD)
        val peer = firstWireguardPeer(raw) ?: return null
        val server = string(peer, "server")?.removeSurrounding("[", "]") ?: return null
        val port = port(peer["port"]) ?: return null
        config.remarks = string(raw, "name") ?: "$server:$port"
        config.server = server
        config.serverPort = port
        config.secretKey = string(raw, "private-key") ?: return null
        config.publicKey = string(peer, "public-key") ?: return null
        config.preSharedKey = firstNonBlank(string(peer, "pre-shared-key"), string(raw, "pre-shared-key"))
        config.reserved = reservedString(peer["reserved"] ?: raw["reserved"]) ?: "0,0,0"
        config.mtu = intValue(raw["mtu"])
        config.localAddress = csv(
            listOfNotNull(
                string(raw, "ip"),
                string(raw, "ipv6")
            )
        ) ?: AppConfig.WIREGUARD_LOCAL_ADDRESS_V4
        return config
    }

    private fun createBaseProfile(type: EConfigType, raw: Map<String, Any?>): ProfileItem? {
        val server = string(raw, "server")?.removeSurrounding("[", "]") ?: return null
        val port = port(raw["port"]) ?: return null
        return ProfileItem.create(type).apply {
            remarks = string(raw, "name") ?: "$server:$port"
            this.server = server
            serverPort = port
        }
    }

    private fun parseAsVless(raw: Map<String, Any?>): ProfileItem? {
        val transport = normalizedNetwork(raw)
        if (string(raw, "uuid").isNullOrBlank()) {
            return null
        }
        if (transport !in vlessFallbackNetworks) {
            return null
        }
        return parseVless(raw)
    }

    private fun applyTransportSettings(config: ProfileItem, raw: Map<String, Any?>): ProfileItem? {
        val network = normalizedNetwork(raw)
        when (network) {
            "", NetworkType.TCP.type -> {
                config.network = NetworkType.TCP.type
                raw["http-opts"]?.let { value ->
                    val opts = asStringMap(value) ?: return null
                    config.headerType = AppConfig.HEADER_TYPE_HTTP
                    config.host = csv(headerHosts(asStringMap(opts["headers"])))
                    config.path = csv(stringList(opts["path"]))
                }
            }

            NetworkType.WS.type -> {
                val opts = asStringMap(raw["ws-opts"])
                config.network = NetworkType.WS.type
                config.host = headerHost(asStringMap(opts?.get("headers"))) ?: string(opts, "host")
                config.path = firstString(opts?.get("path")) ?: "/"
            }

            NetworkType.GRPC.type -> {
                val opts = asStringMap(raw["grpc-opts"])
                config.network = NetworkType.GRPC.type
                config.serviceName = string(opts, "grpc-service-name") ?: string(opts, "service-name")
                config.authority = string(opts, "authority")
                config.mode = string(opts, "grpc-mode")?.takeIf { it.equals("multi", ignoreCase = true) }
            }

            NetworkType.H2.type, NetworkType.HTTP.type -> {
                val opts = asStringMap(
                    raw[if (network == NetworkType.H2.type) "h2-opts" else "http-opts"]
                ) ?: asStringMap(raw["h2-opts"])
                    ?: asStringMap(raw["http-opts"])
                config.network = network
                config.host = csv(stringList(opts?.get("host")))
                    ?: csv(headerHosts(asStringMap(opts?.get("headers"))))
                config.path = firstString(opts?.get("path")) ?: "/"
            }

            NetworkType.KCP.type -> {
                val opts = asStringMap(raw["kcp-opts"])
                config.network = NetworkType.KCP.type
                config.headerType = string(opts, "header-type") ?: string(opts, "header")
                config.seed = string(opts, "seed")
                if (config.headerType.isNullOrBlank()) {
                    config.headerType = "none"
                }
            }

            NetworkType.HTTP_UPGRADE.type -> {
                val opts = asStringMap(raw["httpupgrade-opts"])
                    ?: asStringMap(raw["http-upgrade-opts"])
                config.network = NetworkType.HTTP_UPGRADE.type
                config.host = string(opts, "host")
                config.path = firstString(opts?.get("path")) ?: "/"
            }

            NetworkType.XHTTP.type -> {
                val opts = asStringMap(raw["xhttp-opts"])
                config.network = NetworkType.XHTTP.type
                config.host = string(opts, "host")
                config.path = firstString(opts?.get("path")) ?: "/"
                config.xhttpMode = string(opts, "mode")
                opts?.get("extra")?.let { extra ->
                    config.xhttpExtra = if (extra is String) extra else JsonUtil.toJson(extra)
                }
            }

            else -> {
                config.network = NetworkType.TCP.type
            }
        }
        return config
    }

    private fun applySecuritySettings(
        config: ProfileItem,
        raw: Map<String, Any?>,
        forceTls: Boolean = false
    ) {
        val realityOpts = asStringMap(raw["reality-opts"])
        when {
            !realityOpts.isNullOrEmpty() -> {
                config.security = AppConfig.REALITY
                config.publicKey = string(realityOpts, "public-key")
                config.shortId = string(realityOpts, "short-id")
                config.spiderX = string(realityOpts, "spider-x")
            }

            boolean(raw["tls"]) == true || forceTls -> {
                config.security = AppConfig.TLS
            }

            else -> {
                config.security = null
            }
        }

        config.sni = firstNonBlank(
            string(raw, "servername"),
            string(raw, "server-name"),
            string(raw, "sni")
        )
        config.insecure = boolean(raw["skip-cert-verify"])
        config.fingerPrint = string(raw, "client-fingerprint")
        config.pinnedCA256 = string(raw, "fingerprint")
        config.alpn = csv(stringList(raw["alpn"]))

        val echOpts = asStringMap(raw["ech-opts"])
        if (boolean(echOpts?.get("enable")) == true) {
            config.echConfigList = string(echOpts, "config")
        }
    }

    private fun applyShadowsocksPlugin(config: ProfileItem, raw: Map<String, Any?>): Boolean {
        val plugin = string(raw, "plugin")?.lowercase() ?: return true
        val opts = asStringMap(raw["plugin-opts"]) ?: emptyMap()

        return when (plugin) {
            "v2ray-plugin" -> {
                val mode = string(opts, "mode")?.lowercase()
                if (!mode.isNullOrEmpty() && mode != "websocket" && mode != "ws") {
                    false
                } else {
                    config.network = NetworkType.WS.type
                    config.host = string(opts, "host")
                    config.path = firstString(opts["path"]) ?: "/"
                    if (boolean(opts["tls"]) == true) {
                        config.security = AppConfig.TLS
                    }
                    config.insecure = boolean(opts["skip-cert-verify"])
                    config.sni = firstNonBlank(string(opts, "host"), config.sni)
                    true
                }
            }

            "obfs", "simple-obfs" -> {
                when (string(opts, "mode")?.lowercase()) {
                    "http", null, "" -> {
                        config.network = NetworkType.TCP.type
                        config.headerType = AppConfig.HEADER_TYPE_HTTP
                        config.host = string(opts, "host")
                        config.path = firstString(opts["path"])
                        true
                    }

                    else -> false
                }
            }

            else -> false
        }
    }

    private fun string(map: Map<String, Any?>?, key: String): String? {
        return map?.get(key)?.let(::string)
    }

    private fun string(value: Any?): String? {
        return when (value) {
            null -> null
            is String -> value.trim().takeIf { it.isNotEmpty() }
            is Number, is Boolean -> value.toString()
            else -> null
        }
    }

    private fun boolean(value: Any?): Boolean? {
        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> when (value.trim().lowercase()) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> null
            }

            else -> null
        }
    }

    private fun port(value: Any?): String? {
        return when (value) {
            is Number -> value.toInt().takeIf { it > 0 }?.toString()
            is String -> value.trim().toIntOrNull()?.takeIf { it > 0 }?.toString()
            else -> null
        }
    }

    private fun intValue(value: Any?): Int? {
        return when (value) {
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull()
            else -> null
        }
    }

    private fun stringList(value: Any?): List<String> {
        return when (value) {
            is List<*> -> value.mapNotNull(::string)
            else -> listOfNotNull(string(value))
        }
    }

    private fun firstString(value: Any?): String? {
        return stringList(value).firstOrNull()
    }

    private fun headerHost(headers: Map<String, Any?>?): String? {
        return firstString(headers?.get("Host")) ?: firstString(headers?.get("host"))
    }

    private fun headerHosts(headers: Map<String, Any?>?): List<String> {
        return stringList(headers?.get("Host")).ifEmpty {
            stringList(headers?.get("host"))
        }
    }

    private fun csv(values: List<String>): String? {
        val filtered = values.map { it.trim() }.filter { it.isNotEmpty() }
        return filtered.takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun normalizedNetwork(raw: Map<String, Any?>): String {
        return when (string(raw, "network")?.lowercase()) {
            null, "" -> ""
            "websocket" -> NetworkType.WS.type
            "http2" -> NetworkType.H2.type
            "http-upgrade" -> NetworkType.HTTP_UPGRADE.type
            else -> string(raw, "network")?.lowercase().orEmpty()
        }
    }

    private fun normalizeBandwidth(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim().replace(Regex("\\s+"), "")
        return when {
            normalized.isEmpty() -> null
            normalized.any { it.isLetter() } -> normalized
            else -> "${normalized}Mbps"
        }
    }

    private fun applyDefaultTransportAlpn(config: ProfileItem) {
        if (config.network == NetworkType.WS.type && config.alpn.isNullOrBlank()) {
            config.alpn = "http/1.1"
        }
    }

    private fun reservedString(value: Any?): String? {
        return when (value) {
            is List<*> -> value.mapNotNull(::intValue).joinToString(",").takeIf { it.isNotBlank() }
            is String -> value.trim().takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun firstWireguardPeer(raw: Map<String, Any?>): Map<String, Any?>? {
        val peers = raw["peers"] as? List<*>
        val firstPeer = peers?.firstOrNull()?.let(::asStringMap)
        if (firstPeer != null) {
            return firstPeer
        }
        return raw.takeIf { string(raw, "server") != null && port(raw["port"]) != null && string(raw, "public-key") != null }
    }

    private fun asStringMap(value: Any?): Map<String, Any?>? {
        val map = value as? Map<*, *> ?: return null
        return buildMap {
            map.forEach { (key, entryValue) ->
                (key as? String)?.let { put(it, entryValue) }
            }
        }
    }
}

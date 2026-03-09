package com.v2ray.ang.dto

import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.AppConfig.PORT_SOCKS
import com.v2ray.ang.AppConfig.TAG_BLOCKED
import com.v2ray.ang.AppConfig.TAG_DIRECT
import com.v2ray.ang.AppConfig.TAG_PROXY
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

data class ProfileItem(
    val configVersion: Int = 4,
    val configType: EConfigType,
    var subscriptionId: String = "",
    var addedTime: Long = System.currentTimeMillis(),

    var remarks: String = "",
    var description: String? = null,
    var server: String? = null,
    var serverPort: String? = null,

    var password: String? = null,
    var method: String? = null,
    var flow: String? = null,
    var username: String? = null,

    var network: String? = null,
    var headerType: String? = null,
    var host: String? = null,
    var path: String? = null,
    var seed: String? = null,
    var quicSecurity: String? = null,
    var quicKey: String? = null,
    var mode: String? = null,
    var serviceName: String? = null,
    var authority: String? = null,
    var xhttpMode: String? = null,
    var xhttpExtra: String? = null,

    var security: String? = null,
    var sni: String? = null,
    var alpn: String? = null,
    var fingerPrint: String? = null,
    var insecure: Boolean? = null,
    var echConfigList: String? = null,
    var echForceQuery: String? = null,
    var pinnedCA256: String? = null,

    var publicKey: String? = null,
    var shortId: String? = null,
    var spiderX: String? = null,
    var mldsa65Verify: String? = null,

    var secretKey: String? = null,
    var preSharedKey: String? = null,
    var localAddress: String? = null,
    var reserved: String? = null,
    var mtu: Int? = null,

    var obfsPassword: String? = null,
    var portHopping: String? = null,
    var portHoppingInterval: String? = null,
    var pinSHA256: String? = null,
    var bandwidthDown: String? = null,
    var bandwidthUp: String? = null,

    var policyGroupType: String? = null,
    var policyGroupSubscriptionId: String? = null,
    var policyGroupFilter: String? = null,

    ) {
    companion object {
        fun create(configType: EConfigType): ProfileItem {
            return ProfileItem(configType = configType)
        }
    }

    fun getAllOutboundTags(): MutableList<String> {
        return mutableListOf(TAG_PROXY, TAG_DIRECT, TAG_BLOCKED)
    }

    fun getServerAddressAndPort(): String {
        if (server.isNullOrEmpty() && configType == EConfigType.CUSTOM) {
            return "$LOOPBACK:$PORT_SOCKS"
        }
        return Utils.getIpv6Address(server) + ":" + serverPort
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ProfileItem) return false

        return (this.server == other.server
                && this.serverPort == other.serverPort
                && this.password == other.password
                && this.method == other.method
                && this.flow == other.flow
                && this.username == other.username

                && this.network == other.network
                && this.headerType == other.headerType
                && this.host == other.host
                && this.path == other.path
                && this.seed == other.seed
                && this.quicSecurity == other.quicSecurity
                && this.quicKey == other.quicKey
                && this.mode == other.mode
                && this.serviceName == other.serviceName
                && this.authority == other.authority
                && this.xhttpMode == other.xhttpMode

                && this.security == other.security
                && this.sni == other.sni
                && this.alpn == other.alpn
                && this.fingerPrint == other.fingerPrint
                && this.publicKey == other.publicKey
                && this.shortId == other.shortId

                && this.secretKey == other.secretKey
                && this.localAddress == other.localAddress
                && this.reserved == other.reserved
                && this.mtu == other.mtu

                && this.obfsPassword == other.obfsPassword
                && this.portHopping == other.portHopping
                && this.portHoppingInterval == other.portHoppingInterval
                && this.pinnedCA256 == other.pinnedCA256
                )
    }

    override fun hashCode(): Int {
        var result = server.hashCode()
        result = 31 * result + serverPort.hashCode()
        result = 31 * result + password.hashCode()
        result = 31 * result + method.hashCode()
        result = 31 * result + flow.hashCode()
        result = 31 * result + username.hashCode()
        result = 31 * result + network.hashCode()
        result = 31 * result + headerType.hashCode()
        result = 31 * result + host.hashCode()
        result = 31 * result + path.hashCode()
        result = 31 * result + seed.hashCode()
        result = 31 * result + quicSecurity.hashCode()
        result = 31 * result + quicKey.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + serviceName.hashCode()
        result = 31 * result + authority.hashCode()
        result = 31 * result + xhttpMode.hashCode()
        result = 31 * result + security.hashCode()
        result = 31 * result + sni.hashCode()
        result = 31 * result + alpn.hashCode()
        result = 31 * result + fingerPrint.hashCode()
        result = 31 * result + publicKey.hashCode()
        result = 31 * result + shortId.hashCode()
        result = 31 * result + secretKey.hashCode()
        result = 31 * result + localAddress.hashCode()
        result = 31 * result + reserved.hashCode()
        result = 31 * result + mtu.hashCode()
        result = 31 * result + obfsPassword.hashCode()
        result = 31 * result + portHopping.hashCode()
        result = 31 * result + portHoppingInterval.hashCode()
        result = 31 * result + pinnedCA256.hashCode()
        return result
    }
}

package com.xray.ang.fmt

import com.xray.ang.dto.ProfileItem
import com.xray.ang.dto.V2rayConfig.OutboundBean
import com.xray.ang.enums.EConfigType
import com.xray.ang.extension.isNotNullEmpty
import com.xray.ang.handler.V2rayConfigManager
import com.xray.ang.util.Utils

object HttpFmt : FmtBase() {
    /**
     * Converts a ProfileItem object to an OutboundBean object.
     *
     * @param profileItem the ProfileItem object to convert
     * @return the converted OutboundBean object, or null if conversion fails
     */
    fun toOutbound(profileItem: ProfileItem): OutboundBean? {
        val outboundBean = V2rayConfigManager.createInitOutbound(EConfigType.HTTP)

        outboundBean?.settings?.servers?.first()?.let { server ->
            val port = Utils.parsePortOrNull(profileItem.serverPort) ?: return null
            server.address = getServerAddress(profileItem)
            server.port = port
            if (profileItem.username.isNotNullEmpty()) {
                val socksUsersBean = OutboundBean.OutSettingsBean.ServersBean.SocksUsersBean()
                socksUsersBean.user = profileItem.username.orEmpty()
                socksUsersBean.pass = profileItem.password.orEmpty()
                server.users = listOf(socksUsersBean)
            }
        }

        return outboundBean
    }
}

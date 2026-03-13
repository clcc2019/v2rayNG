package com.v2ray.ang.handler

import android.util.Log
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.ConfigResult
import com.v2ray.ang.dto.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.util.Utils

internal fun validateServerPort(profileItem: ProfileItem, result: ConfigResult): Boolean {
    val port = Utils.parsePortOrNull(profileItem.serverPort)
    if (port == null) {
        Log.w(AppConfig.TAG, "Invalid server port: ${profileItem.serverPort}")
        result.errorResId = R.string.toast_invalid_port
        return false
    }
    return true
}

internal fun validateHysteriaPortHopping(profileItem: ProfileItem, result: ConfigResult): Boolean {
    if (profileItem.configType != EConfigType.HYSTERIA2) return true
    val hopping = profileItem.portHopping
    if (hopping.isNullOrBlank()) return true
    if (!Utils.isValidPortHopping(hopping)) {
        result.errorResId = R.string.toast_invalid_port_hop
        return false
    }
    val interval = profileItem.portHoppingInterval?.trim()
    if (!interval.isNullOrEmpty()) {
        val value = interval.toIntOrNull()
        if (value == null || value < 5) {
            result.errorResId = R.string.toast_invalid_port_hop_interval
            return false
        }
    }
    return true
}

internal fun validateWireguardReserved(profileItem: ProfileItem, result: ConfigResult): Boolean {
    if (profileItem.configType != EConfigType.WIREGUARD) return true
    if (!Utils.isValidWireguardReserved(profileItem.reserved)) {
        result.errorResId = R.string.toast_invalid_wireguard_reserved
        return false
    }
    return true
}

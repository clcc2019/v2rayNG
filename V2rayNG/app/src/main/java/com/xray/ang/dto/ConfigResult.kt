package com.xray.ang.dto

data class ConfigResult(
    var status: Boolean,
    var guid: String? = null,
    var content: String = "",
    var errorResId: Int? = null,
    var profile: ProfileItem? = null,
)

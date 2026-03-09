package com.v2ray.ang.dto

data class ServersCache(
    val guid: String,
    val profile: ProfileItem,
    val displayAddress: String,
    val testDelayMillis: Long = 0L
)

package com.xray.ang.data.repository

import com.xray.ang.AppConfig
import com.xray.ang.handler.MmkvManager

interface PerAppProxyRepository {
    fun getAll(): Set<String>
    fun saveAll(packages: Set<String>)
}

object DefaultPerAppProxyRepository : PerAppProxyRepository {
    override fun getAll(): Set<String> {
        return MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET).orEmpty()
    }

    override fun saveAll(packages: Set<String>) {
        MmkvManager.encodeSettings(AppConfig.PREF_PER_APP_PROXY_SET, packages.toMutableSet())
    }
}

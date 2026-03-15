package com.v2ray.ang.domain.settings

import com.v2ray.ang.handler.SettingsChangeManager

interface SettingsChangeNotifier {
    fun requestRestartService()
    fun requestGroupTabRefresh()
}

object DefaultSettingsChangeNotifier : SettingsChangeNotifier {
    override fun requestRestartService() {
        SettingsChangeManager.makeRestartService()
    }

    override fun requestGroupTabRefresh() {
        SettingsChangeManager.makeSetupGroupTab()
    }
}

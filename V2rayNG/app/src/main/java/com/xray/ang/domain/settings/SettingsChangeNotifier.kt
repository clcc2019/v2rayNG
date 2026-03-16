package com.xray.ang.domain.settings

import com.xray.ang.handler.SettingsChangeManager

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

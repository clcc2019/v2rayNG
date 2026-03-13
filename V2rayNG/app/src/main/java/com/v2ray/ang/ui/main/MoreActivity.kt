package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMoreBinding
import com.v2ray.ang.ui.common.hapticClick
import com.v2ray.ang.ui.common.startActivityWithDefaultTransition

class MoreActivity : BaseActivity() {
    private val binding by lazy { ActivityMoreBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.more_title))
        setupActions()
    }

    private fun setupActions() {
        bindLaunch(binding.rowSubSetting.root, Intent(this, SubSettingActivity::class.java))
        bindLaunch(binding.rowRoutingSetting.root, Intent(this, RoutingSettingActivity::class.java))
        bindLaunch(binding.rowSettings.root, Intent(this, SettingsActivity::class.java))
        bindLaunch(binding.rowBackupRestore.root, Intent(this, BackupActivity::class.java))
        bindLaunch(binding.rowLogcat.root, Intent(this, LogcatActivity::class.java))
        bindLaunch(binding.rowCheckUpdate.root, Intent(this, CheckUpdateActivity::class.java))
        bindLaunch(binding.rowAbout.root, Intent(this, AboutActivity::class.java))
    }

    private fun bindLaunch(view: android.view.View, intent: Intent) {
        view.setOnClickListener {
            it.hapticClick()
            startActivityWithDefaultTransition(intent)
        }
    }
}

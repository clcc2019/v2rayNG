package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMoreBinding

class MoreActivity : BaseActivity() {
    private val binding by lazy { ActivityMoreBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.more_title))
        setupActions()
        postScreenContentEnterMotion(binding.root)
    }

    private fun setupActions() {
        applyPressMotion(
            binding.rowSubSetting.root,
            binding.rowRoutingSetting.root,
            binding.rowSettings.root,
            binding.rowBackupRestore.root,
            binding.rowLogcat.root,
            binding.rowObservability.root,
            binding.rowCheckUpdate.root,
            binding.rowAbout.root
        )
        bindLaunchAction(binding.rowSubSetting.root) { Intent(this, SubSettingActivity::class.java) }
        bindLaunchAction(binding.rowRoutingSetting.root) { Intent(this, RoutingSettingActivity::class.java) }
        bindLaunchAction(binding.rowSettings.root) { Intent(this, SettingsActivity::class.java) }
        bindLaunchAction(binding.rowBackupRestore.root) { Intent(this, BackupActivity::class.java) }
        bindLaunchAction(binding.rowLogcat.root) { Intent(this, LogcatActivity::class.java) }
        bindLaunchAction(binding.rowObservability.root) { Intent(this, ObservabilityActivity::class.java) }
        bindLaunchAction(binding.rowCheckUpdate.root) { Intent(this, CheckUpdateActivity::class.java) }
        bindLaunchAction(binding.rowAbout.root) { Intent(this, AboutActivity::class.java) }
    }
}

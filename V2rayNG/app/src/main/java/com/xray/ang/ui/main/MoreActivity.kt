package com.xray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.xray.ang.R
import com.xray.ang.databinding.ActivityMoreBinding

class MoreActivity : BaseActivity() {
    private data class ActionRow(
        val view: View,
        val intentFactory: () -> Intent
    )

    private val binding by lazy { ActivityMoreBinding.inflate(layoutInflater) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.more_title))
        setupActions()
        postScreenContentEnterMotion(binding.root)
    }

    private fun setupActions() {
        val actionRows = listOf(
            ActionRow(binding.rowSubSetting.root) { Intent(this, SubSettingActivity::class.java) },
            ActionRow(binding.rowRoutingSetting.root) { Intent(this, RoutingSettingActivity::class.java) },
            ActionRow(binding.rowSettings.root) { Intent(this, SettingsActivity::class.java) },
            ActionRow(binding.rowBackupRestore.root) { Intent(this, BackupActivity::class.java) },
            ActionRow(binding.rowLogcat.root) { Intent(this, LogcatActivity::class.java) },
            ActionRow(binding.rowObservability.root) { Intent(this, ObservabilityActivity::class.java) },
            ActionRow(binding.rowCheckUpdate.root) { Intent(this, CheckUpdateActivity::class.java) },
            ActionRow(binding.rowAbout.root) { Intent(this, AboutActivity::class.java) }
        )
        applyPressMotion(*actionRows.map(ActionRow::view).toTypedArray())
        actionRows.forEach { row ->
            bindLaunchAction(row.view, intentProvider = row.intentFactory)
        }
    }
}

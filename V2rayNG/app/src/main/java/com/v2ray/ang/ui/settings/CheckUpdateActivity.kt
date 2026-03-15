package com.v2ray.ang.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityCheckUpdateBinding
import com.v2ray.ang.dto.CheckUpdateResult
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.handler.V2RayNativeManager
import com.v2ray.ang.ui.common.actionBottomSheetItem
import com.v2ray.ang.ui.common.showMessageBottomSheet
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.text.DateFormat
import java.util.Date

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }
    private var checkJob: Job? = null
    private var lastResult: CheckUpdateResult? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))
        applyPressMotion(binding.actionCheckUpdate)
        applyPressMotion(binding.actionViewUpdate)
        applyPressMotion(binding.layoutPreRelease)
        (binding.root.getChildAt(0) as? ViewGroup)?.let {
            postStaggeredEnterMotion(it, translationOffsetDp = 10f, startDelay = 36L)
        }

        binding.actionCheckUpdate.setOnClickListener {
            checkForUpdates(binding.checkPreRelease.isChecked, showDialogOnUpdate = true)
        }

        binding.actionViewUpdate.setOnClickListener {
            lastResult?.let(::showUpdateDialog)
        }

        binding.layoutPreRelease.setOnClickListener {
            binding.checkPreRelease.toggle()
        }

        binding.checkPreRelease.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)
        binding.checkPreRelease.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, isChecked)
        }

        binding.tvCurrentVersion.text = installedVersionLabel()
        binding.tvLatestVersion.text = getString(R.string.update_summary_waiting)
        binding.tvVersion.text = getString(
            R.string.update_version_footer,
            BuildConfig.VERSION_NAME,
            V2RayNativeManager.getLibVersion()
        )

        renderReadyState()

        binding.root.post {
            if (!isFinishing && !isDestroyed) {
                checkForUpdates(binding.checkPreRelease.isChecked, showDialogOnUpdate = false)
            }
        }
    }

    private fun checkForUpdates(includePreRelease: Boolean, showDialogOnUpdate: Boolean) {
        renderCheckingState()
        toast(R.string.update_checking_for_update)
        checkJob?.cancel()
        checkJob = launchLoadingTask(
            taskContext = Dispatchers.IO,
            onError = { e ->
                lastResult = null
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                val message = e.message ?: getString(R.string.toast_failure)
                renderErrorState(message)
                toastError(message)
            },
            task = {
                UpdateCheckerManager.checkForUpdate(includePreRelease)
            },
            onSuccess = { result ->
                if (result.hasUpdate) {
                    lastResult = result
                    renderUpdateAvailableState(result)
                    if (showDialogOnUpdate && !isFinishing && !isDestroyed) {
                        showUpdateDialog(result)
                    }
                } else {
                    lastResult = null
                    renderLatestState()
                    toastSuccess(R.string.update_already_latest_version)
                }
            }
        )
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        UiMotion.animatePulse(binding.cardUpdateStatus, pulseScale = 1.012f, duration = MotionTokens.PULSE_QUICK)
        showMessageBottomSheet(
            title = getString(R.string.update_new_version_found, result.latestVersion.orEmpty()),
            message = result.releaseNotes.orEmpty(),
            actions = listOf(
                actionBottomSheetItem(getString(R.string.update_now), R.drawable.ic_check_update_24dp) {
                    result.downloadUrl?.let {
                        Utils.openUri(this, it)
                    }
                },
                actionBottomSheetItem(getString(android.R.string.cancel), R.drawable.ic_chevron_down_20dp) {}
            )
        )
    }

    private fun renderReadyState() {
        binding.tvStatusTitle.text = getString(R.string.update_status_ready_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_ready_summary)
        binding.tvLatestVersion.text = getString(R.string.update_summary_waiting)
        binding.layoutReleaseNotes.visibility = android.view.View.GONE
        binding.actionCheckUpdate.isEnabled = true
        binding.actionCheckUpdate.text = getString(R.string.update_action_check_now)
        binding.actionViewUpdate.visibility = android.view.View.GONE
        binding.tvLastChecked.visibility = android.view.View.GONE
        updateStatusBadge(
            text = getString(R.string.update_status_badge_ready),
            backgroundColor = R.color.md_theme_primaryContainer,
            textColor = R.color.md_theme_onPrimaryContainer
        )
    }

    private fun renderCheckingState() {
        binding.tvStatusTitle.text = getString(R.string.update_status_checking_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_checking_summary)
        binding.layoutReleaseNotes.visibility = android.view.View.GONE
        binding.actionCheckUpdate.isEnabled = false
        binding.actionCheckUpdate.text = getString(R.string.update_action_checking)
        binding.actionViewUpdate.visibility = android.view.View.GONE
        updateStatusBadge(
            text = getString(R.string.update_status_badge_checking),
            backgroundColor = R.color.md_theme_primaryContainer,
            textColor = R.color.md_theme_onPrimaryContainer
        )
    }

    private fun renderLatestState() {
        binding.tvStatusTitle.text = getString(R.string.update_status_latest_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_latest_summary)
        binding.tvLatestVersion.text = installedVersionLabel()
        binding.layoutReleaseNotes.visibility = android.view.View.GONE
        binding.actionCheckUpdate.isEnabled = true
        binding.actionCheckUpdate.text = getString(R.string.update_action_check_now)
        binding.actionViewUpdate.visibility = android.view.View.GONE
        updateStatusBadge(
            text = getString(R.string.update_status_badge_latest),
            backgroundColor = R.color.color_latency_bg_good,
            textColor = R.color.colorPing
        )
        updateLastCheckedLabel()
    }

    private fun renderUpdateAvailableState(result: CheckUpdateResult) {
        binding.tvStatusTitle.text = getString(R.string.update_status_available_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_available_summary)
        binding.tvLatestVersion.text = result.latestVersion?.let {
            getString(R.string.update_version_value, it)
        } ?: getString(R.string.update_summary_waiting)
        binding.actionCheckUpdate.isEnabled = true
        binding.actionCheckUpdate.text = getString(R.string.update_action_check_now)
        binding.actionViewUpdate.visibility = android.view.View.VISIBLE
        binding.actionViewUpdate.isEnabled = true

        if (result.releaseNotes.isNullOrBlank()) {
            binding.layoutReleaseNotes.visibility = android.view.View.GONE
        } else {
            binding.layoutReleaseNotes.visibility = android.view.View.VISIBLE
            binding.tvReleaseNotes.text = result.releaseNotes.trim()
        }

        updateStatusBadge(
            text = getString(R.string.update_status_badge_available),
            backgroundColor = R.color.md_theme_primaryContainer,
            textColor = R.color.md_theme_onPrimaryContainer
        )
        updateLastCheckedLabel()
    }

    private fun renderErrorState(message: String) {
        binding.tvStatusTitle.text = getString(R.string.update_status_error_title)
        binding.tvStatusSummary.text = message
        binding.tvLatestVersion.text = getString(R.string.update_summary_waiting)
        binding.layoutReleaseNotes.visibility = android.view.View.GONE
        binding.actionCheckUpdate.isEnabled = true
        binding.actionCheckUpdate.text = getString(R.string.update_action_check_now)
        binding.actionViewUpdate.visibility = android.view.View.GONE
        updateStatusBadge(
            text = getString(R.string.update_status_badge_error),
            backgroundColor = R.color.md_theme_errorContainer,
            textColor = R.color.md_theme_error
        )
        updateLastCheckedLabel()
    }

    private fun updateLastCheckedLabel() {
        val checkedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
        binding.tvLastChecked.text = getString(R.string.update_last_checked, checkedAt)
        binding.tvLastChecked.visibility = android.view.View.VISIBLE
    }

    private fun installedVersionLabel(): String {
        return getString(R.string.update_version_value, BuildConfig.VERSION_NAME)
    }

    private fun updateStatusBadge(text: String, backgroundColor: Int, textColor: Int) {
        binding.tvStatusBadge.text = text
        binding.tvStatusBadge.background = binding.tvStatusBadge.background.mutateAndTint(backgroundColor)
        binding.tvStatusBadge.setTextColor(ContextCompat.getColor(this, textColor))
    }

    private fun Drawable.mutateAndTint(colorRes: Int): Drawable {
        return DrawableCompat.wrap(mutate()).also {
            DrawableCompat.setTint(it, ContextCompat.getColor(this@CheckUpdateActivity, colorRes))
        }
    }
}

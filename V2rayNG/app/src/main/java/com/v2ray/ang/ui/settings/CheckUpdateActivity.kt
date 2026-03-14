package com.v2ray.ang.ui

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
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

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }
    private var checkJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))
        applyPressMotion(binding.layoutCheckUpdate)
        (binding.root.getChildAt(0) as? ViewGroup)?.let {
            postStaggeredEnterMotion(it, translationOffsetDp = 10f, startDelay = 36L)
        }

        binding.layoutCheckUpdate.setOnClickListener {
            checkForUpdates(binding.checkPreRelease.isChecked)
        }

        binding.checkPreRelease.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, isChecked)
        }
        binding.checkPreRelease.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_CHECK_UPDATE_PRE_RELEASE, false)

        "v${BuildConfig.VERSION_NAME} (${V2RayNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        checkForUpdates(binding.checkPreRelease.isChecked)
    }

    private fun checkForUpdates(includePreRelease: Boolean) {
        toast(R.string.update_checking_for_update)
        checkJob?.cancel()
        checkJob = launchLoadingTask(
            taskContext = Dispatchers.IO,
            onError = { e ->
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            },
            task = {
                UpdateCheckerManager.checkForUpdate(includePreRelease)
            },
            onSuccess = { result ->
                if (result.hasUpdate) {
                    showUpdateDialog(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            }
        )
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        UiMotion.animatePulse(binding.layoutCheckUpdate, pulseScale = 1.012f, duration = MotionTokens.PULSE_QUICK)
        showMessageBottomSheet(
            title = getString(R.string.update_new_version_found, result.latestVersion),
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
}

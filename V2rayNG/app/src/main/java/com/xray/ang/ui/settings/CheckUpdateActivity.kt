package com.xray.ang.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.DrawableCompat
import com.xray.ang.AppConfig
import com.xray.ang.BuildConfig
import com.xray.ang.R
import com.xray.ang.databinding.ActivityCheckUpdateBinding
import com.xray.ang.dto.CheckUpdateResult
import com.xray.ang.extension.toastError
import com.xray.ang.extension.toastSuccess
import com.xray.ang.handler.MmkvManager
import com.xray.ang.handler.UpdateCheckerManager
import com.xray.ang.handler.V2RayNativeManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.File
import java.text.DateFormat
import java.util.Date

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }
    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var lastResult: CheckUpdateResult? = null
    private var pendingInstallFile: File? = null
    private var lastCheckedAt: String? = null
    private var isChecking = false
    private var isDownloading = false

    private val installPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val apkFile = pendingInstallFile ?: return@registerForActivityResult
        if (packageManager.canRequestPackageInstalls()) {
            launchInstaller(apkFile)
        } else {
            renderInstallPermissionState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))
        applyPressMotion(binding.actionCheckUpdate, binding.actionViewUpdate, binding.layoutPreRelease)
        postScreenContentEnterMotion(binding.root)

        bindClickAction(binding.actionCheckUpdate, withHaptic = false) {
            handlePrimaryAction()
        }

        bindClickAction(binding.actionViewUpdate, withHaptic = false) {
            if (!isChecking && !isDownloading) {
                checkForUpdates(binding.checkPreRelease.isChecked, userInitiated = true)
            }
        }

        bindClickAction(binding.layoutPreRelease, withHaptic = false) {
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
                checkForUpdates(binding.checkPreRelease.isChecked, userInitiated = false)
            }
        }
    }

    override fun onDestroy() {
        checkJob?.cancel()
        downloadJob?.cancel()
        super.onDestroy()
    }

    private fun handlePrimaryAction() {
        if (isChecking || isDownloading) return

        val cachedApk = pendingInstallFile
        if (cachedApk != null) {
            if (cachedApk.exists()) {
                openInstallerOrRequestPermission(cachedApk)
            } else {
                pendingInstallFile = null
                lastResult?.let(::renderUpdateAvailableState) ?: renderReadyState()
            }
            return
        }

        val result = lastResult
        if (result?.hasUpdate == true && !result.downloadUrl.isNullOrBlank()) {
            downloadAndInstallUpdate(result)
        } else {
            checkForUpdates(binding.checkPreRelease.isChecked, userInitiated = true)
        }
    }

    private fun checkForUpdates(includePreRelease: Boolean, userInitiated: Boolean) {
        clearPendingInstallState()
        isChecking = true
        renderCheckingState()
        checkJob?.cancel()
        checkJob = launchLoadingTask(
            taskContext = Dispatchers.IO,
            onError = onError@{ e ->
                isChecking = false
                if (e is CancellationException) return@onError

                lastResult = null
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                recordLastChecked()
                val message = e.message ?: getString(R.string.toast_failure)
                renderErrorState(message)
                if (userInitiated) {
                    toastError(message)
                }
            },
            task = {
                UpdateCheckerManager.checkForUpdate(includePreRelease)
            },
            onSuccess = { result ->
                isChecking = false
                recordLastChecked()
                if (result.hasUpdate) {
                    lastResult = result
                    renderUpdateAvailableState(result)
                    if (userInitiated) {
                        toastSuccess(getString(R.string.update_new_version_found, result.latestVersion.orEmpty()))
                    }
                } else {
                    lastResult = null
                    renderLatestState()
                    if (userInitiated) {
                        toastSuccess(R.string.update_already_latest_version)
                    }
                }
            }
        )
    }

    private fun downloadAndInstallUpdate(result: CheckUpdateResult) {
        val downloadUrl = result.downloadUrl
        if (downloadUrl.isNullOrBlank()) {
            toastError(R.string.update_download_failed)
            return
        }

        clearPendingInstallState(deleteFile = true)
        isDownloading = true
        renderDownloadingState(result)
        downloadJob?.cancel()
        downloadJob = launchLoadingTask(
            taskContext = Dispatchers.IO,
            onError = onError@{ e ->
                isDownloading = false
                if (e is CancellationException) return@onError

                Log.e(AppConfig.TAG, "Failed to download update: ${e.message}")
                renderUpdateAvailableState(result)
                toastError(e.message ?: getString(R.string.update_download_failed))
            },
            task = {
                UpdateCheckerManager.downloadApk(this, downloadUrl)
                    ?.takeIf { it.exists() }
                    ?: throw IllegalStateException(getString(R.string.update_download_failed))
            },
            onSuccess = { apkFile ->
                isDownloading = false
                pendingInstallFile = apkFile
                openInstallerOrRequestPermission(apkFile)
            }
        )
    }

    private fun openInstallerOrRequestPermission(apkFile: File) {
        if (!apkFile.exists()) {
            clearPendingInstallState()
            lastResult?.let(::renderUpdateAvailableState) ?: renderReadyState()
            toastError(R.string.update_download_failed)
            return
        }

        if (!packageManager.canRequestPackageInstalls()) {
            renderInstallPermissionState()
            requestInstallPermission()
            return
        }

        launchInstaller(apkFile)
    }

    private fun requestInstallPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:$packageName")
        )
        try {
            installPermissionLauncher.launch(intent)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Unable to open install permission settings", e)
            toastError(R.string.toast_failure)
        }
    }

    private fun launchInstaller(apkFile: File) {
        val apkUri = FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.cache",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        if (installIntent.resolveActivity(packageManager) == null) {
            toastError(R.string.update_install_unavailable)
            lastResult?.let(::renderUpdateAvailableState) ?: renderReadyState()
            return
        }

        renderInstallReadyState()
        startActivity(installIntent)
    }

    private fun clearPendingInstallState(deleteFile: Boolean = false) {
        val apkFile = pendingInstallFile
        pendingInstallFile = null
        if (deleteFile && apkFile?.exists() == true) {
            runCatching { apkFile.delete() }
        }
    }

    private fun renderReadyState() {
        binding.tvStatusTitle.text = getString(R.string.update_status_ready_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_ready_summary)
        binding.tvLatestVersion.text = getString(R.string.update_summary_waiting)
        renderReleaseNotes(null)
        configurePrimaryAction(
            enabled = true,
            textRes = R.string.update_action_check_now,
            iconRes = R.drawable.ic_check_update_24dp
        )
        hideSecondaryAction()
        showLastCheckedIfAvailable()
        updateStatusBadge(
            text = getString(R.string.update_status_badge_ready),
            backgroundColor = R.color.md_theme_primaryContainer,
            textColor = R.color.md_theme_onPrimaryContainer
        )
    }

    private fun renderCheckingState() {
        binding.tvStatusTitle.text = getString(R.string.update_status_checking_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_checking_summary)
        renderReleaseNotes(null)
        configurePrimaryAction(
            enabled = false,
            textRes = R.string.update_action_checking,
            iconRes = R.drawable.ic_check_update_24dp
        )
        hideSecondaryAction()
        showLastCheckedIfAvailable()
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
        renderReleaseNotes(null)
        configurePrimaryAction(
            enabled = true,
            textRes = R.string.update_action_check_now,
            iconRes = R.drawable.ic_refresh_24dp
        )
        hideSecondaryAction()
        showLastCheckedIfAvailable()
        updateStatusBadge(
            text = getString(R.string.update_status_badge_latest),
            backgroundColor = R.color.color_latency_bg_good,
            textColor = R.color.colorPing
        )
    }

    private fun renderUpdateAvailableState(result: CheckUpdateResult) {
        binding.tvStatusTitle.text = getString(R.string.update_status_available_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_available_summary)
        binding.tvLatestVersion.text = result.latestVersion?.let {
            getString(R.string.update_version_value, it)
        } ?: getString(R.string.update_summary_waiting)
        renderReleaseNotes(result.releaseNotes)
        configurePrimaryAction(
            enabled = true,
            textRes = R.string.update_action_download_install,
            iconRes = R.drawable.ic_cloud_download_24dp
        )
        showSecondaryAction(
            textRes = R.string.update_action_recheck,
            iconRes = R.drawable.ic_refresh_24dp
        )
        showLastCheckedIfAvailable()
        updateStatusBadge(
            text = getString(R.string.update_status_badge_available),
            backgroundColor = R.color.md_theme_primaryContainer,
            textColor = R.color.md_theme_onPrimaryContainer
        )
    }

    private fun renderDownloadingState(result: CheckUpdateResult) {
        binding.tvStatusTitle.text = getString(R.string.update_status_downloading_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_downloading_summary)
        binding.tvLatestVersion.text = result.latestVersion?.let {
            getString(R.string.update_version_value, it)
        } ?: getString(R.string.update_summary_waiting)
        renderReleaseNotes(result.releaseNotes)
        configurePrimaryAction(
            enabled = false,
            textRes = R.string.update_action_downloading,
            iconRes = R.drawable.ic_cloud_download_24dp
        )
        hideSecondaryAction()
        showLastCheckedIfAvailable()
        updateStatusBadge(
            text = getString(R.string.update_status_badge_downloading),
            backgroundColor = R.color.md_theme_primaryContainer,
            textColor = R.color.md_theme_onPrimaryContainer
        )
    }

    private fun renderInstallPermissionState() {
        binding.tvStatusTitle.text = getString(R.string.update_status_permission_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_permission_summary)
        renderReleaseNotes(lastResult?.releaseNotes)
        configurePrimaryAction(
            enabled = true,
            textRes = R.string.update_action_allow_install,
            iconRes = R.drawable.ic_check_update_24dp
        )
        showSecondaryAction(
            textRes = R.string.update_action_recheck,
            iconRes = R.drawable.ic_refresh_24dp
        )
        showLastCheckedIfAvailable()
        updateStatusBadge(
            text = getString(R.string.update_status_badge_permission),
            backgroundColor = R.color.md_theme_primaryContainer,
            textColor = R.color.md_theme_onPrimaryContainer
        )
    }

    private fun renderInstallReadyState() {
        binding.tvStatusTitle.text = getString(R.string.update_status_install_title)
        binding.tvStatusSummary.text = getString(R.string.update_status_install_summary)
        renderReleaseNotes(lastResult?.releaseNotes)
        configurePrimaryAction(
            enabled = true,
            textRes = R.string.update_action_install_now,
            iconRes = R.drawable.ic_check_update_24dp
        )
        showSecondaryAction(
            textRes = R.string.update_action_recheck,
            iconRes = R.drawable.ic_refresh_24dp
        )
        showLastCheckedIfAvailable()
        updateStatusBadge(
            text = getString(R.string.update_status_badge_install),
            backgroundColor = R.color.color_latency_bg_good,
            textColor = R.color.colorPing
        )
    }

    private fun renderErrorState(message: String) {
        binding.tvStatusTitle.text = getString(R.string.update_status_error_title)
        binding.tvStatusSummary.text = message
        binding.tvLatestVersion.text = getString(R.string.update_summary_waiting)
        renderReleaseNotes(null)
        configurePrimaryAction(
            enabled = true,
            textRes = R.string.update_action_check_now,
            iconRes = R.drawable.ic_refresh_24dp
        )
        hideSecondaryAction()
        showLastCheckedIfAvailable()
        updateStatusBadge(
            text = getString(R.string.update_status_badge_error),
            backgroundColor = R.color.md_theme_errorContainer,
            textColor = R.color.md_theme_error
        )
    }

    private fun renderReleaseNotes(notes: String?) {
        if (notes.isNullOrBlank()) {
            binding.layoutReleaseNotes.visibility = View.GONE
            binding.tvReleaseNotes.text = ""
            return
        }

        binding.layoutReleaseNotes.visibility = View.VISIBLE
        binding.tvReleaseNotes.text = notes.trim()
    }

    private fun configurePrimaryAction(enabled: Boolean, textRes: Int, iconRes: Int) {
        binding.actionCheckUpdate.isEnabled = enabled
        binding.actionCheckUpdate.setText(textRes)
        binding.actionCheckUpdate.setIconResource(iconRes)
    }

    private fun showSecondaryAction(textRes: Int, iconRes: Int) {
        binding.actionViewUpdate.visibility = View.VISIBLE
        binding.actionViewUpdate.isEnabled = true
        binding.actionViewUpdate.setText(textRes)
        binding.actionViewUpdate.setIconResource(iconRes)
    }

    private fun hideSecondaryAction() {
        binding.actionViewUpdate.visibility = View.GONE
    }

    private fun recordLastChecked() {
        lastCheckedAt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date())
        showLastCheckedIfAvailable()
    }

    private fun showLastCheckedIfAvailable() {
        val checkedAt = lastCheckedAt
        if (checkedAt.isNullOrBlank()) {
            binding.tvLastChecked.visibility = View.GONE
            return
        }
        binding.tvLastChecked.text = getString(R.string.update_last_checked, checkedAt)
        binding.tvLastChecked.visibility = View.VISIBLE
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

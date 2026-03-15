package com.v2ray.ang.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.app.Activity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.tencent.mmkv.MMKV
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.WEBDAV_BACKUP_FILE_NAME
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityBackupBinding
import com.v2ray.ang.databinding.DialogWebdavBinding
import com.v2ray.ang.dto.WebDavConfig
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.WebDavManager
import com.v2ray.ang.ui.common.actionBottomSheetItem
import com.v2ray.ang.ui.common.showActionBottomSheet
import com.v2ray.ang.util.ZipUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class BackupActivity : HelperBaseActivity() {
    companion object {
        private const val BACKUP_FILE_TIMESTAMP_PATTERN = "yyyy-MM-dd-HH-mm-ss"
    }

    private val binding by lazy { ActivityBackupBinding.inflate(layoutInflater) }

    private val configBackupOptions: Array<out String> by lazy {
        resources.getStringArray(R.array.config_backup_options)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_configuration_backup_restore))
        applyPressMotion(
            binding.layoutBackup,
            binding.layoutShare,
            binding.layoutRestore,
            binding.layoutWebdavConfigSetting
        )
        postScreenContentEnterMotion(binding.root)

        bindClickAction(binding.layoutBackup, withHaptic = false) {
            showActionBottomSheet(
                title = getString(R.string.title_configuration_backup),
                actions = listOf(
                    actionBottomSheetItem(configBackupOptions[0], R.drawable.ic_file_24dp) { backupViaLocal() },
                    actionBottomSheetItem(configBackupOptions[1], R.drawable.ic_cloud_download_24dp) { backupViaWebDav() }
                )
            )
        }

        bindClickAction(binding.layoutShare, withHaptic = false) {
            shareBackupArchive()
        }

        bindClickAction(binding.layoutRestore, withHaptic = false) {
            showActionBottomSheet(
                title = getString(R.string.title_configuration_restore),
                actions = listOf(
                    actionBottomSheetItem(configBackupOptions[0], R.drawable.ic_restore_24dp) { restoreViaLocal() },
                    actionBottomSheetItem(configBackupOptions[1], R.drawable.ic_cloud_download_24dp) { restoreViaWebDav() }
                )
            )
        }

        bindClickAction(binding.layoutWebdavConfigSetting, withHaptic = false) {
            showWebDavSettingsDialog()
        }
    }

    /**
     * Backup configuration to cache directory
     * Returns Pair<success, zipFilePath>
     */
    private fun backupConfigurationToCache(): Pair<Boolean, String> {
        val dateFormatted = createBackupTimestamp()
        val folderName = "${getString(R.string.app_name)}_${dateFormatted}"
        val backupDir = this.cacheDir.absolutePath + "/$folderName"
        val outputZipFilePath = "${this.cacheDir.absolutePath}/$folderName.zip"

        val count = MMKV.backupAllToDirectory(backupDir)
        if (count <= 0) {
            return Pair(false, "")
        }

        if (ZipUtil.zipFromFolder(backupDir, outputZipFilePath)) {
            return Pair(true, outputZipFilePath)
        } else {
            return Pair(false, "")
        }
    }

    private fun restoreConfiguration(zipFile: File): Boolean {
        val backupDir = this.cacheDir.absolutePath + "/${System.currentTimeMillis()}"

        if (!ZipUtil.unzipToFolder(zipFile, backupDir)) {
            return false
        }

        val count = MMKV.restoreAllFromDirectory(backupDir)
        SettingsChangeManager.makeSetupGroupTab()
        SettingsChangeManager.makeRestartService()

        SettingsManager.initApp(this)
        return count > 0
    }

    private fun createBackupTimestamp(): String {
        return SimpleDateFormat(BACKUP_FILE_TIMESTAMP_PATTERN, Locale.getDefault())
            .format(System.currentTimeMillis())
    }

    private fun showFileChooser() {
        launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
            }
            launchLoadingTask(
                task = { restoreFromUri(uri) },
                onSuccess = { handleRestoreResult(it) },
                onError = { e ->
                    Log.e(AppConfig.TAG, "Error during file restore", e)
                    toastError(R.string.toast_failure)
                }
            )
        }
    }

    private fun restoreFromUri(uri: android.net.Uri): Boolean {
        val targetFile = File(cacheDir.absolutePath, "${System.currentTimeMillis()}.zip")
        return try {
            contentResolver.openInputStream(uri).use { input ->
                targetFile.outputStream().use { fileOut ->
                    input?.copyTo(fileOut)
                }
            }
            restoreConfiguration(targetFile)
        } finally {
            targetFile.delete()
        }
    }

    private fun backupViaLocal() {
        val defaultFileName = "${getString(R.string.app_name)}_${createBackupTimestamp()}.zip"

        launchCreateDocument(defaultFileName) { uri ->
            if (uri == null) return@launchCreateDocument
            launchLoadingTask(
                task = { writeBackupToUri(uri) },
                onSuccess = { success ->
                    if (success) toastSuccess(R.string.toast_success) else toastError(R.string.toast_failure)
                },
                onError = { e ->
                    Log.e(AppConfig.TAG, "Failed to backup configuration", e)
                    toastError(R.string.toast_failure)
                }
            )
        }
    }

    private fun writeBackupToUri(uri: android.net.Uri): Boolean {
        val ret = backupConfigurationToCache()
        if (!ret.first) return false
        val zipFile = File(ret.second)
        return try {
            contentResolver.openOutputStream(uri)?.use { output ->
                zipFile.inputStream().use { input ->
                    input.copyTo(output)
                }
            } != null
        } finally {
            zipFile.delete()
        }
    }

    private fun restoreViaLocal() {
        showFileChooser()
    }

    private fun shareBackupArchive() {
        launchLoadingTask(
            task = { backupConfigurationToCache() },
            onSuccess = { result ->
                if (result.first) {
                    launchBackupShareSheet(File(result.second))
                } else {
                    toastError(R.string.toast_failure)
                }
            },
            onError = { e ->
                Log.e(AppConfig.TAG, "Error preparing share backup", e)
                toastError(R.string.toast_failure)
            }
        )
    }

    private fun launchBackupShareSheet(zipFile: File) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(this, "${BuildConfig.APPLICATION_ID}.cache", zipFile)
                    ),
                getString(R.string.title_configuration_share)
            )
        )
    }

    private fun backupViaWebDav() {
        val saved = MmkvManager.decodeWebDavConfig()
        if (saved == null || saved.baseUrl.isEmpty()) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                val ret = backupConfigurationToCache()
                if (!ret.first) {
                    withContext(Dispatchers.Main) {
                        toastError(R.string.toast_failure)
                    }
                    return@launch
                }

                tempFile = File(ret.second)
                WebDavManager.init(saved)
                val ok = uploadBackupToWebDav(tempFile)

                withContext(Dispatchers.Main) {
                    if (ok) toastSuccess(R.string.toast_success) else toastError(R.string.toast_failure)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "WebDAV backup error", e)
                withContext(Dispatchers.Main) {
                    toastError(R.string.toast_failure)
                }
            } finally {
                try {
                    tempFile?.delete()
                } catch (_: Exception) {
                }
            }
        }.also { showLoading() }
            .invokeOnCompletion { hideLoading() }
    }

    private suspend fun uploadBackupToWebDav(tempFile: File): Boolean {
        return try {
            WebDavManager.uploadFile(tempFile, WEBDAV_BACKUP_FILE_NAME)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "WebDAV upload error", e)
            false
        }
    }

    private fun restoreViaWebDav() {
        val saved = MmkvManager.decodeWebDavConfig()
        if (saved == null || saved.baseUrl.isEmpty()) {
            toastError(R.string.title_webdav_config_setting_unknown)
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            var target: File? = null
            try {
                target = File(cacheDir, "download_${System.currentTimeMillis()}.zip")
                WebDavManager.init(saved)
                val ok = WebDavManager.downloadFile(WEBDAV_BACKUP_FILE_NAME, target)
                if (!ok) {
                    withContext(Dispatchers.Main) {
                        toastError(R.string.toast_failure)
                    }
                    return@launch
                }

                val restored = restoreConfiguration(target)
                withContext(Dispatchers.Main) {
                    handleRestoreResult(restored)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "WebDAV download error", e)
                withContext(Dispatchers.Main) { toastError(R.string.toast_failure) }
            } finally {
                try {
                    target?.delete()
                } catch (_: Exception) {
                }
            }
        }.also { showLoading() }
            .invokeOnCompletion { hideLoading() }
    }

    private fun handleRestoreResult(restored: Boolean) {
        if (restored) {
            setResult(Activity.RESULT_OK)
            toastSuccess(R.string.toast_success)
        } else {
            toastError(R.string.toast_failure)
        }
    }

    private fun showWebDavSettingsDialog() {
        val dialogBinding = DialogWebdavBinding.inflate(layoutInflater)

        MmkvManager.decodeWebDavConfig()?.let { cfg ->
            dialogBinding.etWebdavUrl.setText(cfg.baseUrl)
            dialogBinding.etWebdavUser.setText(cfg.username ?: "")
            dialogBinding.etWebdavPass.setText(cfg.password ?: "")
            dialogBinding.etWebdavRemotePath.setText(cfg.remoteBasePath ?: "/")
        }

        showActionBottomSheet(
            title = getString(R.string.title_webdav_config_setting),
            subtitle = getString(R.string.backup_summary_webdav),
            contentView = dialogBinding.root,
            actions = listOf(
                actionBottomSheetItem(getString(R.string.menu_item_save_config), R.drawable.ic_save_24dp) {
                    val url = dialogBinding.etWebdavUrl.text.toString().trim()
                    val user = dialogBinding.etWebdavUser.text.toString().trim().ifEmpty { null }
                    val pass = dialogBinding.etWebdavPass.text.toString()
                    val remotePath = dialogBinding.etWebdavRemotePath.text.toString().trim().ifEmpty { AppConfig.WEBDAV_BACKUP_DIR }
                    val cfg = WebDavConfig(baseUrl = url, username = user, password = pass, remoteBasePath = remotePath)
                    MmkvManager.encodeWebDavConfig(cfg)
                    toastSuccess(R.string.toast_success)
                },
                actionBottomSheetItem(getString(android.R.string.cancel), R.drawable.ic_chevron_down_20dp) {}
            )
        )
    }
}

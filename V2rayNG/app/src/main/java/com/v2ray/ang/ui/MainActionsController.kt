package com.v2ray.ang.ui

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActionsController(
    private val activity: MainActivity,
    private val mainViewModel: MainViewModel,
    private val onSetupGroupTabs: () -> Unit
) {
    private data class ManualImportAction(
        val type: EConfigType,
        @StringRes val labelResId: Int,
        val iconResId: Int
    )

    private fun action(
        @StringRes labelResId: Int,
        iconResId: Int,
        destructive: Boolean = false,
        handler: () -> Unit
    ): ActionBottomSheetItem {
        return ActionBottomSheetItem(
            labelResId = labelResId,
            iconRes = iconResId,
            destructive = destructive,
            handler = handler
        )
    }

    fun handleOptionsItem(itemId: Int): Boolean {
        return when (itemId) {
            R.id.action_add_sheet -> {
                showAddSheet()
                true
            }
            R.id.action_more_sheet -> {
                showMoreSheet()
                true
            }
            else -> false
        }
    }

    private fun showAddSheet() {
        activity.showActionBottomSheet(
            title = activity.getString(R.string.menu_item_add_config),
            subtitle = activity.getString(R.string.current_config),
            actions = buildHomeAddActions()
        )
    }

    private fun showMoreSheet() {
        activity.showActionBottomSheet(
            title = "",
            subtitle = null,
            actions = buildHomeMoreActions()
        )
    }

    private fun buildHomeAddActions(): List<ActionBottomSheetItem> {
        val baseActions = listOf(
            action(R.string.menu_item_import_config_qrcode, R.drawable.ic_qu_scan_24dp) { importQRcode() },
            action(R.string.menu_item_import_config_clipboard, R.drawable.ic_copy) { importClipboard() },
            action(R.string.menu_item_import_config_local, R.drawable.ic_file_24dp) { importConfigLocal() }
        )
        val manualActions = listOf(
            ManualImportAction(EConfigType.POLICYGROUP, R.string.menu_item_import_config_policy_group, R.drawable.ic_subscriptions_24dp),
            ManualImportAction(EConfigType.VMESS, R.string.menu_item_import_config_manually_vmess, R.drawable.ic_add_24dp),
            ManualImportAction(EConfigType.VLESS, R.string.menu_item_import_config_manually_vless, R.drawable.ic_add_24dp),
            ManualImportAction(EConfigType.SHADOWSOCKS, R.string.menu_item_import_config_manually_ss, R.drawable.ic_add_24dp),
            ManualImportAction(EConfigType.SOCKS, R.string.menu_item_import_config_manually_socks, R.drawable.ic_add_24dp),
            ManualImportAction(EConfigType.HTTP, R.string.menu_item_import_config_manually_http, R.drawable.ic_add_24dp),
            ManualImportAction(EConfigType.TROJAN, R.string.menu_item_import_config_manually_trojan, R.drawable.ic_add_24dp),
            ManualImportAction(EConfigType.WIREGUARD, R.string.menu_item_import_config_manually_wireguard, R.drawable.ic_add_24dp),
            ManualImportAction(EConfigType.HYSTERIA2, R.string.menu_item_import_config_manually_hysteria2, R.drawable.ic_add_24dp)
        )
        val manualItems = manualActions.map { entry ->
            action(entry.labelResId, entry.iconResId) { importManually(entry.type.value) }
        }
        return baseActions + manualItems
    }

    private fun buildHomeMoreActions(): List<ActionBottomSheetItem> {
        return listOf(
            action(R.string.title_sub_update, R.drawable.ic_cloud_download_24dp) { importConfigViaSub() },
            action(R.string.title_export_all, R.drawable.ic_description_24dp) { exportAll() },
            action(R.string.title_ping_all_server, R.drawable.ic_logcat_24dp) {
                activity.toast(activity.getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                mainViewModel.testAllTcping()
            },
            action(R.string.title_real_ping_all_server, R.drawable.ic_logcat_24dp) {
                activity.toast(activity.getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                mainViewModel.testAllRealPing()
            },
            action(R.string.title_sort_by_test_results, R.drawable.ic_feedback_24dp) { sortByTestResults() },
            action(R.string.title_del_duplicate_config, R.drawable.ic_delete_24dp, destructive = true) {
                delDuplicateConfig()
            },
            action(R.string.title_del_invalid_config, R.drawable.ic_delete_24dp, destructive = true) {
                delInvalidConfig()
            },
            action(R.string.title_del_all_config, R.drawable.ic_delete_24dp, destructive = true) {
                delAllConfig()
            }
        )
    }

    private fun importManually(createConfigType: Int) {
        if (createConfigType == EConfigType.POLICYGROUP.value) {
            activity.startActivity(
                Intent()
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(activity, ServerGroupActivity::class.java)
            )
        } else {
            activity.startActivity(
                Intent()
                    .putExtra("createConfigType", createConfigType)
                    .putExtra("subscriptionId", mainViewModel.subscriptionId)
                    .setClass(activity, ServerActivity::class.java)
            )
        }
    }

    private fun importQRcode(): Boolean {
        activity.launchQrScanner { scanResult ->
            if (scanResult != null) {
                importBatchConfig(scanResult)
            }
        }
        return true
    }

    private fun importClipboard(): Boolean {
        try {
            val clipboard = Utils.getClipboard(activity)
            importBatchConfig(clipboard)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from clipboard", e)
            return false
        }
        return true
    }

    private fun importBatchConfig(server: String?) {
        launchLoadingTask(
            delayMillis = 500L,
            task = { AngConfigManager.importBatchConfig(server, mainViewModel.subscriptionId, true) },
            onSuccess = { (count, countSub) ->
                when {
                    count > 0 -> {
                        activity.toast(activity.getString(R.string.title_import_config_count, count))
                        mainViewModel.reloadServerList()
                    }
                    countSub > 0 -> onSetupGroupTabs()
                    else -> activity.toastError(R.string.toast_failure)
                }
            },
            onFailure = { e ->
                activity.toastError(R.string.toast_failure)
                Log.e(AppConfig.TAG, "Failed to import batch config", e)
            }
        )
    }

    private fun importConfigLocal(): Boolean {
        try {
            showFileChooser()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to import config from local file", e)
            return false
        }
        return true
    }

    private fun importConfigViaSub(): Boolean {
        launchLoadingTask(
            delayMillis = 500L,
            task = { mainViewModel.updateConfigViaSubAll() },
            onSuccess = { result ->
                if (result.successCount + result.failureCount + result.skipCount == 0) {
                    activity.toast(R.string.title_update_subscription_no_subscription)
                } else if (result.successCount > 0 && result.failureCount + result.skipCount == 0) {
                    activity.toast(activity.getString(R.string.title_update_config_count, result.configCount))
                } else {
                    activity.toast(
                        activity.getString(
                            R.string.title_update_subscription_result,
                            result.configCount, result.successCount, result.failureCount, result.skipCount
                        )
                    )
                }
                if (result.configCount > 0) {
                    mainViewModel.reloadServerList()
                }
            }
        )
        return true
    }

    private fun exportAll() {
        launchLoadingTask(
            task = { mainViewModel.exportAllServer() },
            onSuccess = { count ->
                if (count > 0) {
                    activity.toast(activity.getString(R.string.title_export_config_count, count))
                } else {
                    activity.toastError(R.string.toast_failure)
                }
            }
        )
    }

    private fun delAllConfig() {
        showConfirmDialog(R.string.del_config_comfirm) {
            launchLoadingTask(
                task = { mainViewModel.removeAllServer() },
                onSuccess = { count ->
                    mainViewModel.reloadServerList()
                    activity.toast(activity.getString(R.string.title_del_config_count, count))
                }
            )
        }
    }

    private fun delDuplicateConfig() {
        showConfirmDialog(R.string.del_config_comfirm) {
            launchLoadingTask(
                task = { mainViewModel.removeDuplicateServer() },
                onSuccess = { count ->
                    mainViewModel.reloadServerList()
                    activity.toast(activity.getString(R.string.title_del_duplicate_config_count, count))
                }
            )
        }
    }

    private fun delInvalidConfig() {
        showConfirmDialog(R.string.del_invalid_config_comfirm) {
            launchLoadingTask(
                task = { mainViewModel.removeInvalidServer() },
                onSuccess = { count ->
                    mainViewModel.reloadServerList()
                    activity.toast(activity.getString(R.string.title_del_config_count, count))
                }
            )
        }
    }

    private fun sortByTestResults() {
        launchLoadingTask(
            task = { mainViewModel.sortByTestResults() },
            onSuccess = { mainViewModel.reloadServerList() }
        )
    }

    private fun showConfirmDialog(
        @StringRes messageResId: Int,
        onConfirmed: () -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setMessage(messageResId)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                onConfirmed()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun <T> launchLoadingTask(
        delayMillis: Long = 0L,
        task: suspend () -> T,
        onSuccess: (T) -> Unit,
        onFailure: ((Exception) -> Unit)? = null
    ) {
        activity.showLoadingIndicator()
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = task()
                if (delayMillis > 0) {
                    delay(delayMillis)
                }
                withContext(Dispatchers.Main) {
                    onSuccess(result)
                    activity.hideLoadingIndicator()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onFailure?.invoke(e)
                    activity.hideLoadingIndicator()
                }
            }
        }
    }

    private fun showFileChooser() {
        activity.launchConfigFileChooser { uri ->
            if (uri == null) {
                return@launchConfigFileChooser
            }
            readContentFromUri(uri)
        }
    }

    private fun readContentFromUri(uri: Uri) {
        launchLoadingTask(
            task = {
                activity.contentResolver.openInputStream(uri).use { input ->
                    input?.bufferedReader()?.readText()
                }
            },
            onSuccess = { content ->
                importBatchConfig(content)
            },
            onFailure = { e ->
                Log.e(AppConfig.TAG, "Failed to read content from URI", e)
                activity.toastError(R.string.toast_failure)
            }
        )
    }
}

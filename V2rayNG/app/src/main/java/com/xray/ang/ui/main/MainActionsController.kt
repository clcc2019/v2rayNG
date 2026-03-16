package com.xray.ang.ui

import android.net.Uri
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.enums.EConfigType
import com.xray.ang.extension.toast
import com.xray.ang.extension.toastError
import com.xray.ang.ui.common.ActionBottomSheetItem
import com.xray.ang.ui.common.actionBottomSheetItem
import com.xray.ang.ui.common.showConfirmDialog
import com.xray.ang.ui.common.showActionBottomSheet
import com.xray.ang.util.Utils
import com.xray.ang.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActionsController(
    private val activity: HelperBaseActivity,
    private val mainViewModel: MainViewModel,
    private val onSetupGroupTabs: () -> Unit,
    private val onOpenSearch: () -> Unit
) {
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

    fun showMoreSheet() {
        activity.showActionBottomSheet(
            title = activity.getString(R.string.action_more),
            subtitle = null,
            actions = buildToolbarMoreActions()
        )
    }

    fun showAddSheet() {
        activity.showActionBottomSheet(
            title = activity.getString(R.string.menu_item_add_config),
            subtitle = activity.getString(R.string.current_config),
            actions = buildHomeAddActions()
        )
    }

    private fun buildToolbarMoreActions(): List<ActionBottomSheetItem> = buildList {
        add(actionBottomSheetItem(R.string.menu_item_search, R.drawable.ic_search_24dp) { onOpenSearch() })
        addAll(buildHomeMoreActions())
    }

    private fun buildHomeAddActions(): List<ActionBottomSheetItem> {
        val baseActions = listOf(
            actionBottomSheetItem(R.string.menu_item_import_config_qrcode, R.drawable.ic_qu_scan_24dp) { importQRcode() },
            actionBottomSheetItem(R.string.menu_item_import_config_clipboard, R.drawable.ic_copy) { importClipboard() },
            actionBottomSheetItem(R.string.menu_item_import_config_local, R.drawable.ic_file_24dp) { importConfigLocal() }
        )
        return baseActions + HomeImportSupport.buildManualImportActionItems(::importManually)
    }

    private fun buildHomeMoreActions(): List<ActionBottomSheetItem> {
        return listOf(
            actionBottomSheetItem(R.string.title_sub_update, R.drawable.ic_cloud_download_24dp) { importConfigViaSub() },
            actionBottomSheetItem(R.string.title_export_all, R.drawable.ic_description_24dp) { exportAll() },
            actionBottomSheetItem(R.string.title_ping_all_server, R.drawable.ic_logcat_24dp) {
                activity.toast(activity.getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                mainViewModel.testAllTcping()
            },
            actionBottomSheetItem(R.string.title_real_ping_all_server, R.drawable.ic_logcat_24dp) {
                activity.toast(activity.getString(R.string.connection_test_testing_count, mainViewModel.serversCache.count()))
                mainViewModel.testAllRealPing()
            },
            actionBottomSheetItem(R.string.title_sort_by_test_results, R.drawable.ic_feedback_24dp) { sortByTestResults() },
            actionBottomSheetItem(R.string.title_del_duplicate_config, R.drawable.ic_delete_24dp, destructive = true) {
                delDuplicateConfig()
            },
            actionBottomSheetItem(R.string.title_del_invalid_config, R.drawable.ic_delete_24dp, destructive = true) {
                delInvalidConfig()
            },
            actionBottomSheetItem(R.string.title_del_all_config, R.drawable.ic_delete_24dp, destructive = true) {
                delAllConfig()
            }
        )
    }

    private fun importManually(type: EConfigType) {
        activity.startActivity(HomeImportSupport.createManualImportIntent(activity, mainViewModel.subscriptionId, type))
    }

    private fun importQRcode(): Boolean {
        activity.launchQRCodeScanner { scanResult ->
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
            task = { HomeImportSupport.importBatchConfig(server, mainViewModel.subscriptionId) },
            onSuccess = { result ->
                when {
                    result.configCount > 0 -> {
                        activity.toast(activity.getString(R.string.title_import_config_count, result.configCount))
                        mainViewModel.reloadServerList()
                    }
                    result.subscriptionCount > 0 -> onSetupGroupTabs()
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
        activity.showConfirmDialog(R.string.del_config_comfirm) {
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
        activity.showConfirmDialog(R.string.del_config_comfirm) {
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
        activity.showConfirmDialog(R.string.del_invalid_config_comfirm) {
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
        activity.launchFileChooser { uri ->
            if (uri == null) {
                return@launchFileChooser
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

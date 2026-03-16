package com.xray.ang.ui.common

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.handler.MmkvManager

fun Context.createV2RayAlertDialogBuilder(): AlertDialog.Builder {
    val themedContext = ContextThemeWrapper(this, R.style.ThemeOverlay_V2Ray_AppCompatAlertDialog)
    return AlertDialog.Builder(themedContext)
}

fun Fragment.createV2RayAlertDialogBuilder(): AlertDialog.Builder {
    return requireContext().createV2RayAlertDialogBuilder()
}

fun Context.showConfirmDialog(
    @StringRes messageResId: Int = R.string.del_config_comfirm,
    onConfirmed: () -> Unit
) {
    showActionBottomSheet(
        title = getString(R.string.action_confirm),
        subtitle = getString(messageResId),
        actions = listOf(
            actionBottomSheetItem(getString(android.R.string.ok), R.drawable.ic_action_done) {
                onConfirmed()
            },
            actionBottomSheetItem(getString(android.R.string.cancel), R.drawable.ic_chevron_down_20dp) {}
        )
    )
}

fun Fragment.showConfirmDialog(
    @StringRes messageResId: Int = R.string.del_config_comfirm,
    onConfirmed: () -> Unit
) {
    requireContext().showConfirmDialog(messageResId, onConfirmed)
}

fun Context.runWithRemovalConfirmation(
    @StringRes messageResId: Int = R.string.del_config_comfirm,
    onConfirmed: () -> Unit
) {
    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
        showConfirmDialog(messageResId, onConfirmed)
    } else {
        onConfirmed()
    }
}

fun Fragment.runWithRemovalConfirmation(
    @StringRes messageResId: Int = R.string.del_config_comfirm,
    onConfirmed: () -> Unit
) {
    requireContext().runWithRemovalConfirmation(messageResId, onConfirmed)
}

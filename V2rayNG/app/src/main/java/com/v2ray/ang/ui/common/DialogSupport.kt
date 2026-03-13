package com.v2ray.ang.ui.common

import android.content.Context
import android.view.ContextThemeWrapper
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.handler.MmkvManager

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
    createV2RayAlertDialogBuilder()
        .setMessage(messageResId)
        .setPositiveButton(android.R.string.ok) { _, _ -> onConfirmed() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
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

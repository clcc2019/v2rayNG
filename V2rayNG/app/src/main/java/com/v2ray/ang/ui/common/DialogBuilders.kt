package com.v2ray.ang.ui.common

import android.content.Context
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

fun Context.v2rayAlertDialogBuilder(): AlertDialog.Builder {
    return createV2RayAlertDialogBuilder()
}

fun Fragment.v2rayAlertDialogBuilder(): AlertDialog.Builder {
    return requireContext().createV2RayAlertDialogBuilder()
}

package com.xray.ang.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

fun View.hapticClick() {
    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
}

fun View.hapticVirtualKey() {
    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
}

fun View.hapticLongPress() {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
}

fun View.hapticConfirm() {
    val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.CONFIRM
    } else {
        HapticFeedbackConstants.CONTEXT_CLICK
    }
    performHapticFeedback(feedback)
}

fun View.hapticReject() {
    val feedback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        HapticFeedbackConstants.REJECT
    } else {
        HapticFeedbackConstants.LONG_PRESS
    }
    performHapticFeedback(feedback)
}

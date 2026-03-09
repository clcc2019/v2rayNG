package com.v2ray.ang.ui

import android.content.res.ColorStateList
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemBottomSheetActionBinding
import com.v2ray.ang.databinding.LayoutBottomSheetActionsBinding

data class ActionBottomSheetItem(
    @StringRes val labelResId: Int,
    @DrawableRes val iconRes: Int,
    val destructive: Boolean = false,
    val handler: () -> Unit
)

fun AppCompatActivity.showActionBottomSheet(
    title: CharSequence,
    subtitle: CharSequence? = null,
    actions: List<ActionBottomSheetItem>
) {
    val sheetBinding = LayoutBottomSheetActionsBinding.inflate(layoutInflater)
    val bottomSheetDialog = BottomSheetDialog(this)

    sheetBinding.tvTitle.text = title
    sheetBinding.tvSubtitle.text = subtitle?.toString().orEmpty()
    sheetBinding.tvSubtitle.isVisible = !subtitle.isNullOrBlank()

    actions.forEach { action ->
        val itemBinding = ItemBottomSheetActionBinding.inflate(layoutInflater, sheetBinding.layoutActions, false)
        val iconColorRes = if (action.destructive) R.color.md_theme_error else R.color.md_theme_onSurfaceVariant
        val textColorRes = if (action.destructive) R.color.md_theme_error else R.color.md_theme_onSurface

        itemBinding.ivIcon.setImageResource(action.iconRes)
        itemBinding.ivIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, iconColorRes))
        itemBinding.tvLabel.text = getString(action.labelResId)
        itemBinding.tvLabel.setTextColor(ContextCompat.getColor(this, textColorRes))
        UiMotion.attachPressFeedback(itemBinding.root)
        itemBinding.root.setOnClickListener {
            bottomSheetDialog.dismiss()
            runCatching(action.handler).onFailure { error ->
                Log.e(AppConfig.TAG, "Error handling bottom sheet action", error)
            }
        }
        sheetBinding.layoutActions.addView(itemBinding.root)
    }

    bottomSheetDialog.setContentView(sheetBinding.root)
    bottomSheetDialog.setOnShowListener {
        UiMotion.animateStaggeredChildren(sheetBinding.layoutActions)
    }
    bottomSheetDialog.show()
}

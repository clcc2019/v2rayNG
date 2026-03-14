package com.v2ray.ang.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ItemBottomSheetActionBinding
import com.v2ray.ang.databinding.LayoutBottomSheetActionsBinding
import com.v2ray.ang.ui.UiMotion

data class ActionBottomSheetItem(
    @StringRes val labelResId: Int? = null,
    @DrawableRes val iconRes: Int,
    val destructive: Boolean = false,
    val label: CharSequence? = null,
    val handler: () -> Unit
)

fun actionBottomSheetItem(
    @StringRes labelResId: Int,
    @DrawableRes iconRes: Int,
    destructive: Boolean = false,
    handler: () -> Unit
): ActionBottomSheetItem {
    return ActionBottomSheetItem(
        labelResId = labelResId,
        iconRes = iconRes,
        destructive = destructive,
        handler = handler
    )
}

fun actionBottomSheetItem(
    label: CharSequence,
    @DrawableRes iconRes: Int,
    destructive: Boolean = false,
    handler: () -> Unit
): ActionBottomSheetItem {
    return ActionBottomSheetItem(
        label = label,
        iconRes = iconRes,
        destructive = destructive,
        handler = handler
    )
}

fun Context.showActionBottomSheet(
    title: CharSequence,
    subtitle: CharSequence? = null,
    actions: List<ActionBottomSheetItem> = emptyList(),
    contentView: View? = null
) {
    val inflater = LayoutInflater.from(this)
    val sheetBinding = LayoutBottomSheetActionsBinding.inflate(inflater)
    val bottomSheetDialog = BottomSheetDialog(this)

    val titleText = title.toString()
    val subtitleText = subtitle?.toString().orEmpty()
    val hasTitle = titleText.isNotBlank()
    val hasSubtitle = subtitleText.isNotBlank()

    sheetBinding.tvTitle.text = titleText
    sheetBinding.tvTitle.isVisible = hasTitle
    sheetBinding.tvSubtitle.text = subtitleText
    sheetBinding.tvSubtitle.isVisible = hasSubtitle

    if (!hasTitle) {
        val topPadding = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp8)
        sheetBinding.tvSubtitle.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = topPadding
        }
    }

    if (!hasSubtitle) {
        val topPadding = if (hasTitle) {
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp10)
        } else {
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp6)
        }
        sheetBinding.layoutActions.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = topPadding
        }
    }

    contentView?.let {
        sheetBinding.layoutActions.addView(it)
    }

    if (contentView != null && actions.isNotEmpty()) {
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.padding_spacing_dp1)
            ).also { params ->
                params.topMargin = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp6)
                params.bottomMargin = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp6)
            }
            setBackgroundColor(ContextCompat.getColor(context, R.color.color_card_outline))
        }
        sheetBinding.layoutActions.addView(divider)
    }

    actions.forEach { action ->
        val itemBinding = ItemBottomSheetActionBinding.inflate(inflater, sheetBinding.layoutActions, false)
        val iconColorRes = if (action.destructive) R.color.md_theme_error else R.color.md_theme_onSurfaceVariant
        val textColorRes = if (action.destructive) R.color.md_theme_error else R.color.md_theme_onSurface

        itemBinding.ivIcon.setImageResource(action.iconRes)
        itemBinding.ivIcon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, iconColorRes))
        val labelText = action.label ?: action.labelResId?.let { getString(it) }.orEmpty()
        itemBinding.tvLabel.text = labelText
        itemBinding.tvLabel.setTextColor(ContextCompat.getColor(this, textColorRes))
        UiMotion.attachPressFeedback(itemBinding.root)
        itemBinding.root.setOnClickListener {
            bottomSheetDialog.dismiss()
            runCatching(action.handler).onFailure { error ->
                Log.e(AppConfig.TAG, "Error handling bottom sheet action", error)
            }
        }
        sheetBinding.layoutActions.addView(itemBinding.root)

        if (action != actions.last()) {
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    resources.getDimensionPixelSize(R.dimen.padding_spacing_dp1)
                ).also { params ->
                    params.marginStart = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16) +
                        resources.getDimensionPixelSize(R.dimen.padding_spacing_dp14) +
                        resources.getDimensionPixelSize(R.dimen.padding_spacing_dp20)
                }
                setBackgroundColor(ContextCompat.getColor(context, R.color.color_card_outline))
            }
            sheetBinding.layoutActions.addView(divider)
        }
    }

    bottomSheetDialog.setContentView(sheetBinding.root)
    bottomSheetDialog.setOnShowListener {
        UiMotion.animateStaggeredChildren(sheetBinding.layoutActions)
    }
    bottomSheetDialog.show()
}

fun Context.showMessageBottomSheet(
    title: CharSequence,
    message: CharSequence,
    actions: List<ActionBottomSheetItem>
) {
    val contentView = TextView(this).apply {
        text = message
        setTextColor(ContextCompat.getColor(context, R.color.md_theme_onSurfaceVariant))
        setTextAppearance(R.style.TextAppearance_V2Ray_Body)
        setLineSpacing(resources.getDimension(R.dimen.padding_spacing_dp2), 1f)
        setPadding(
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16),
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp14),
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp16),
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp14)
        )
        background = ContextCompat.getDrawable(context, R.drawable.bg_bottom_sheet_group)
    }
    showActionBottomSheet(title = title, actions = actions, contentView = contentView)
}

fun Context.showChoiceBottomSheet(
    title: CharSequence,
    subtitle: CharSequence? = null,
    options: List<CharSequence>,
    @DrawableRes iconRes: Int,
    onSelected: (Int) -> Unit
) {
    showActionBottomSheet(
        title = title,
        subtitle = subtitle,
        actions = options.mapIndexed { index, option ->
            actionBottomSheetItem(option, iconRes) {
                onSelected(index)
            }
        }
    )
}

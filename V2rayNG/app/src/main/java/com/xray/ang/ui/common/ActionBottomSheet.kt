package com.xray.ang.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.xray.ang.AppConfig
import com.xray.ang.R
import com.xray.ang.ui.MotionTokens
import com.xray.ang.ui.UiMotion

data class ActionBottomSheetItem(
    @StringRes val labelResId: Int? = null,
    @DrawableRes val iconRes: Int,
    val destructive: Boolean = false,
    val secondary: Boolean = false,
    val label: CharSequence? = null,
    val handler: () -> Unit
)

fun actionBottomSheetItem(
    @StringRes labelResId: Int,
    @DrawableRes iconRes: Int,
    destructive: Boolean = false,
    secondary: Boolean = false,
    handler: () -> Unit
): ActionBottomSheetItem {
    return ActionBottomSheetItem(
        labelResId = labelResId,
        iconRes = iconRes,
        destructive = destructive,
        secondary = secondary,
        handler = handler
    )
}

fun actionBottomSheetItem(
    label: CharSequence,
    @DrawableRes iconRes: Int,
    destructive: Boolean = false,
    secondary: Boolean = false,
    handler: () -> Unit
): ActionBottomSheetItem {
    return ActionBottomSheetItem(
        label = label,
        iconRes = iconRes,
        destructive = destructive,
        secondary = secondary,
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
    val sheetView = inflater.inflate(R.layout.layout_bottom_sheet_actions, null, false)
    val titleView = sheetView.findViewById<TextView>(R.id.tv_title)
    val subtitleView = sheetView.findViewById<TextView>(R.id.tv_subtitle)
    val actionsContainer = sheetView.findViewById<LinearLayout>(R.id.layout_actions)
    val bottomSheetDialog = BottomSheetDialog(this)

    val titleText = title.toString()
    val subtitleText = subtitle?.toString().orEmpty()
    val hasTitle = titleText.isNotBlank()
    val hasSubtitle = subtitleText.isNotBlank()

    titleView.text = titleText
    titleView.isVisible = hasTitle
    subtitleView.text = subtitleText
    subtitleView.isVisible = hasSubtitle

    if (!hasTitle) {
        val topPadding = resources.getDimensionPixelSize(R.dimen.padding_spacing_dp8)
        subtitleView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = topPadding
        }
    }

    if (!hasSubtitle) {
        val topPadding = if (hasTitle) {
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp10)
        } else {
            resources.getDimensionPixelSize(R.dimen.padding_spacing_dp6)
        }
        actionsContainer.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = topPadding
        }
    }

    contentView?.let {
        actionsContainer.addView(it)
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
        actionsContainer.addView(divider)
    }

    actions.forEach { action ->
        val itemView = inflater.inflate(R.layout.item_bottom_sheet_action, actionsContainer, false)
        val iconView = itemView.findViewById<ImageView>(R.id.iv_icon)
        val labelView = itemView.findViewById<TextView>(R.id.tv_label)
        val iconColorRes = when {
            action.destructive -> R.color.md_theme_error
            action.secondary -> R.color.md_theme_onSurfaceVariant
            else -> R.color.md_theme_onSurfaceVariant
        }
        val textColorRes = when {
            action.destructive -> R.color.md_theme_error
            action.secondary -> R.color.md_theme_onSurfaceVariant
            else -> R.color.md_theme_onSurface
        }

        iconView.setImageResource(action.iconRes)
        iconView.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this, iconColorRes))
        val labelText = action.label ?: action.labelResId?.let { getString(it) }.orEmpty()
        labelView.text = labelText
        labelView.setTextColor(ContextCompat.getColor(this, textColorRes))
        UiMotion.attachPressFeedback(itemView)
        itemView.setOnClickListener {
            bottomSheetDialog.dismiss()
            runCatching(action.handler).onFailure { error ->
                Log.e(AppConfig.TAG, "Error handling bottom sheet action", error)
            }
        }
        actionsContainer.addView(itemView)

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
            actionsContainer.addView(divider)
        }
    }

    bottomSheetDialog.setContentView(sheetView)
    bottomSheetDialog.setOnShowListener {
        val sheetContent = (sheetView as? ViewGroup)?.getChildAt(0) as? ViewGroup
        sheetContent?.let {
            UiMotion.animateStaggeredChildren(
                container = it,
                translationOffsetDp = 10f,
                stepDelay = 24L
            )
        }
        if (actionsContainer.childCount > 0) {
            actionsContainer.post {
                UiMotion.animateStaggeredChildren(
                    container = actionsContainer,
                    translationOffsetDp = 8f,
                    stepDelay = 18L,
                    startDelay = MotionTokens.STAGGER_START_DELAY
                )
            }
        }
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

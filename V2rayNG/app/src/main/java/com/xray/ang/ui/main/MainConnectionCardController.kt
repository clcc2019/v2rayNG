package com.xray.ang.ui

import android.animation.TimeInterpolator
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.xray.ang.databinding.ActivityMainBinding
import com.xray.ang.R
import com.xray.ang.viewmodel.MainViewModel

class MainConnectionCardController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val mainViewModel: MainViewModel,
    private val motionInterpolator: TimeInterpolator
) {
    private var lastSurfaceAlpha: Float? = null
    private var lastSurfaceScale: Float? = null
    private var lastSurfaceTranslationY: Float? = null
    private var lastVisible: Boolean? = null
    private val dockBackgroundAlphaRenderer = AnimatedFloatRenderer(
        motionInterpolator = motionInterpolator,
        debugKey = "dock_alpha"
    )

    fun updateVisibility(visible: Boolean, immediate: Boolean = false) {
        if (lastVisible == visible && !immediate) {
            return
        }
        lastVisible = visible
        if (immediate) {
            UiMotion.setVisibility(binding.cardConnection, visible)
        } else {
            UiMotion.animateVisibility(binding.cardConnection, visible, translationOffsetDp = 22f)
        }
    }

    fun render(state: ServiceUiState) {
        val selectedProfileName = mainViewModel.getSelectedServerSnapshot()
            ?.profile
            ?.remarks
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.home_connection_profile_empty)
        binding.tvConnectionStatus.text = context.getString(
            when (state) {
                ServiceUiState.RUNNING -> R.string.connection_connected_short
                ServiceUiState.STARTING -> R.string.connection_starting_short
                ServiceUiState.STOPPING -> R.string.connection_stopping_short
                ServiceUiState.STOPPED -> R.string.connection_not_connected_short
            }
        )
        binding.tvConnectionProfile.text = selectedProfileName
        binding.tvConnectionSummary.setText(R.string.home_connection_summary_short)
        applyStatusBadgeStyle(state)
    }

    fun updateStateVisuals(state: ServiceUiState, animate: Boolean) {
        val targetAlpha = if (state == ServiceUiState.STARTING || state == ServiceUiState.STOPPING) 0.92f else 1f
        val targetScale = if (state == ServiceUiState.RUNNING) 1f else 0.992f
        val targetTranslationY = if (state == ServiceUiState.RUNNING) 0f else {
            context.resources.displayMetrics.density * 1.5f
        }
        if (lastSurfaceAlpha == targetAlpha &&
            lastSurfaceScale == targetScale &&
            lastSurfaceTranslationY == targetTranslationY
        ) {
            return
        }
        binding.layoutConnectionSurface.animate().cancel()
        if (!animate) {
            binding.layoutConnectionSurface.alpha = targetAlpha
            binding.layoutConnectionSurface.scaleX = targetScale
            binding.layoutConnectionSurface.scaleY = targetScale
            binding.layoutConnectionSurface.translationY = targetTranslationY
            lastSurfaceAlpha = targetAlpha
            lastSurfaceScale = targetScale
            lastSurfaceTranslationY = targetTranslationY
            return
        }
        binding.layoutConnectionSurface.animate()
            .alpha(targetAlpha)
            .scaleX(targetScale)
            .scaleY(targetScale)
            .translationY(targetTranslationY)
            .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .start()
        lastSurfaceAlpha = targetAlpha
        lastSurfaceScale = targetScale
        lastSurfaceTranslationY = targetTranslationY
    }

    fun renderChromeState(state: AppChromeState, animate: Boolean) {
        updateVisibility(state.showBottomBar, immediate = state.bottomBarVisibilityImmediate)
        if (state.showBottomBar) {
            updateDockBackgroundAlpha(state.bottomBarBackgroundAlpha, animate = animate)
        }
    }

    fun updateDockBackgroundAlpha(alpha: Float, animate: Boolean = false) {
        dockBackgroundAlphaRenderer.render(alpha, animate = animate) { value ->
            val alphaInt = (value * 255).toInt()
            binding.cardConnection.setCardBackgroundColor(
                ColorUtils.setAlphaComponent(
                    ContextCompat.getColor(context, R.color.color_home_card_bg_dock),
                    alphaInt
                )
            )
            binding.cardConnection.strokeColor = ColorUtils.setAlphaComponent(
                ContextCompat.getColor(context, R.color.color_home_card_stroke),
                alphaInt
            )
        }
    }

    private fun applyStatusBadgeStyle(state: ServiceUiState) {
        val textRes = when (state) {
            ServiceUiState.RUNNING -> R.color.color_home_metric_good_text
            ServiceUiState.STARTING,
            ServiceUiState.STOPPING -> R.color.color_home_metric_warn_text
            ServiceUiState.STOPPED -> R.color.color_home_metric_idle_text
        }
        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(context, textRes))
    }
}

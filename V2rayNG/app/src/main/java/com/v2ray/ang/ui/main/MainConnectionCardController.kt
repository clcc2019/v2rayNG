package com.v2ray.ang.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.R
import com.v2ray.ang.viewmodel.MainViewModel

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
    private var lastDockBackgroundAlpha: Float? = null
    private var dockBackgroundAnimator: ValueAnimator? = null

    fun updateVisibility(visible: Boolean, immediate: Boolean = false) {
        if (lastVisible == visible && !immediate) {
            return
        }
        lastVisible = visible
        if (immediate) {
            UiMotion.setVisibility(binding.cardConnection, visible)
        } else {
            UiMotion.animateVisibility(binding.cardConnection, visible, translationOffsetDp = 18f)
        }
    }

    fun render(state: ServiceUiState) {
        val selectedProfileName = mainViewModel.getSelectedServerSnapshot()
            ?.profile
            ?.remarks
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.home_connection_profile_empty)
        binding.tvConnectionTitle.setText(R.string.home_connection_title)
        binding.tvConnectionStatus.text = context.getString(
            when (state) {
                ServiceUiState.RUNNING -> R.string.connection_connected_short
                ServiceUiState.STARTING -> R.string.connection_starting_short
                ServiceUiState.STOPPING -> R.string.connection_stopping_short
                ServiceUiState.STOPPED -> R.string.connection_not_connected_short
            }
        )
        binding.tvConnectionProfile.text = selectedProfileName
        binding.tvConnectionSummary.text = context.getString(
            when (state) {
                ServiceUiState.RUNNING -> R.string.home_connection_subtitle
                ServiceUiState.STARTING -> R.string.connection_starting
                ServiceUiState.STOPPING -> R.string.connection_stopping
                ServiceUiState.STOPPED -> R.string.home_connection_summary_short
            }
        )
        applyStatusBadgeStyle(state)
        updateSecondaryActionsVisibility()
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
        val targetAlpha = alpha.coerceIn(0f, 1f)
        if (lastDockBackgroundAlpha != null && kotlin.math.abs(lastDockBackgroundAlpha!! - targetAlpha) < 0.01f) {
            AppChromeDebugTracer.recordRenderSkip("dock_alpha")
            return
        }
        dockBackgroundAnimator?.cancel()
        if (!animate || lastDockBackgroundAlpha == null) {
            applyDockBackgroundAlpha(targetAlpha)
            return
        }
        dockBackgroundAnimator = ValueAnimator.ofFloat(lastDockBackgroundAlpha!!, targetAlpha).apply {
            duration = MotionTokens.SHORT_ANIMATION_DURATION
            interpolator = motionInterpolator
            addUpdateListener { animator ->
                applyDockBackgroundAlpha(animator.animatedValue as Float)
            }
            start()
        }
    }

    private fun applyDockBackgroundAlpha(alpha: Float) {
        val alphaInt = (alpha * 255).toInt()
        binding.layoutConnectionDockContainer.background?.mutate()?.alpha = alphaInt
        binding.layoutConnectionDockContainer.foreground?.mutate()?.alpha = (alpha * 232).toInt()
        binding.viewConnectionDockOrb.background?.mutate()?.alpha = (alpha * 88).toInt()
        lastDockBackgroundAlpha = alpha
    }

    private fun applyStatusBadgeStyle(state: ServiceUiState) {
        val backgroundRes = when (state) {
            ServiceUiState.RUNNING -> R.color.color_home_metric_good
            ServiceUiState.STARTING,
            ServiceUiState.STOPPING -> R.color.color_home_metric_warn
            ServiceUiState.STOPPED -> R.color.color_home_metric_idle
        }
        val textRes = when (state) {
            ServiceUiState.RUNNING -> R.color.color_home_metric_good_text
            ServiceUiState.STARTING,
            ServiceUiState.STOPPING -> R.color.color_home_metric_warn_text
            ServiceUiState.STOPPED -> R.color.color_home_metric_idle_text
        }
        binding.tvConnectionStatus.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, backgroundRes))
        binding.tvConnectionStatus.setTextColor(ContextCompat.getColor(context, textRes))
    }

    private fun updateSecondaryActionsVisibility() {
        UiMotion.setVisibility(binding.layoutTest, false)
    }
}

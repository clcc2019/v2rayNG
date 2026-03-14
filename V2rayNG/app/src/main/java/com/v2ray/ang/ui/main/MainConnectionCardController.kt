package com.v2ray.ang.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
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
    private val baseDockBackgroundColor by lazy {
        ContextCompat.getColor(context, R.color.color_home_surface_raised)
    }

    fun updateVisibility(visible: Boolean, immediate: Boolean = false) {
        if (lastVisible == visible && !immediate) {
            return
        }
        lastVisible = visible
        if (immediate) {
            UiMotion.setVisibility(binding.viewConnectionDockUnderlay, visible)
            UiMotion.setVisibility(binding.cardConnection, visible)
        } else {
            UiMotion.animateVisibility(binding.viewConnectionDockUnderlay, visible, translationOffsetDp = 14f)
            UiMotion.animateVisibility(binding.cardConnection, visible, translationOffsetDp = 18f)
        }
    }

    fun render() {
        val selectedProfileName = mainViewModel.getSelectedServerSnapshot()
            ?.profile
            ?.remarks
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.home_connection_profile_empty)
        binding.tvConnectionProfile.text = selectedProfileName
        binding.tvConnectionSummary.setText(R.string.home_connection_summary_short)
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
        binding.viewConnectionDockUnderlay.background?.mutate()?.alpha = 255
        binding.cardConnection.setCardBackgroundColor(
            ColorUtils.setAlphaComponent(baseDockBackgroundColor, (alpha * 255).toInt())
        )
        lastDockBackgroundAlpha = alpha
    }

    private fun updateSecondaryActionsVisibility() {
        UiMotion.setVisibility(binding.layoutTest, false)
    }
}

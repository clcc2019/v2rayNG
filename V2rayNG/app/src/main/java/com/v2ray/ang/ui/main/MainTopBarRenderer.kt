package com.v2ray.ang.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.drawable.Drawable
import kotlin.math.abs

class MainTopBarRenderer(
    background: Drawable?,
    private val motionInterpolator: TimeInterpolator
) {
    private val backgroundDrawable = background?.mutate()
    private var backgroundAlpha = -1f
    private var alphaAnimator: ValueAnimator? = null

    fun renderChromeState(state: AppChromeState, animate: Boolean) {
        val targetAlpha = state.topBarBackgroundAlpha.coerceIn(0f, 1f)
        if (abs(backgroundAlpha - targetAlpha) < 0.01f) {
            AppChromeDebugTracer.recordRenderSkip("top_bar_alpha")
            return
        }
        alphaAnimator?.cancel()
        if (!animate || backgroundAlpha < 0f) {
            applyBackgroundAlpha(targetAlpha)
            return
        }
        alphaAnimator = ValueAnimator.ofFloat(backgroundAlpha, targetAlpha).apply {
            duration = MotionTokens.SHORT_ANIMATION_DURATION
            interpolator = motionInterpolator
            addUpdateListener { animator ->
                applyBackgroundAlpha(animator.animatedValue as Float)
            }
            start()
        }
    }

    private fun applyBackgroundAlpha(alpha: Float) {
        backgroundDrawable?.alpha = (alpha * 255).toInt()
        backgroundAlpha = alpha
    }
}

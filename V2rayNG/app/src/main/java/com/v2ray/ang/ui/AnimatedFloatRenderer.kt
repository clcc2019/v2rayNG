package com.v2ray.ang.ui

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import kotlin.math.abs

internal class AnimatedFloatRenderer(
    private val motionInterpolator: TimeInterpolator,
    private val debugKey: String? = null
) {
    private var currentValue: Float? = null
    private var animator: ValueAnimator? = null

    fun render(target: Float, animate: Boolean, apply: (Float) -> Unit) {
        val normalizedTarget = target.coerceIn(0f, 1f)
        currentValue?.let { current ->
            if (abs(current - normalizedTarget) < 0.01f) {
                debugKey?.let { AppChromeDebugTracer.recordRenderSkip(it) }
                return
            }
        }
        animator?.cancel()
        if (!animate || currentValue == null) {
            applyValue(normalizedTarget, apply)
            return
        }
        animator = ValueAnimator.ofFloat(currentValue!!, normalizedTarget).apply {
            duration = MotionTokens.SHORT_ANIMATION_DURATION
            interpolator = motionInterpolator
            addUpdateListener { valueAnimator ->
                applyValue(valueAnimator.animatedValue as Float, apply)
            }
            start()
        }
    }

    fun cancel() {
        animator?.cancel()
        animator = null
    }

    private fun applyValue(value: Float, apply: (Float) -> Unit) {
        apply(value)
        currentValue = value
    }
}

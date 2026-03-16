package com.xray.ang.ui

import android.animation.TimeInterpolator
import android.graphics.drawable.Drawable

class MainTopBarRenderer(
    background: Drawable?,
    private val motionInterpolator: TimeInterpolator
) {
    private val backgroundDrawable = background?.mutate()
    private val backgroundAlphaRenderer = AnimatedFloatRenderer(
        motionInterpolator = motionInterpolator,
        debugKey = "top_bar_alpha"
    )

    fun renderChromeState(state: AppChromeState, animate: Boolean) {
        backgroundAlphaRenderer.render(state.topBarBackgroundAlpha, animate = animate) { alpha ->
            backgroundDrawable?.alpha = (alpha * 255).toInt()
        }
    }
}

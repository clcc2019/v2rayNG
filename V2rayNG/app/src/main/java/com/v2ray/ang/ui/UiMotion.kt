package com.v2ray.ang.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

object UiMotion {
    private const val PRESS_DURATION = 90L
    private const val RELEASE_DURATION = 140L
    private const val REVEAL_DURATION = 180L
    private const val STAGGER_DELAY = 26L
    private val motionInterpolator = FastOutSlowInInterpolator()

    fun attachPressFeedback(
        view: View,
        pressedScale: Float = 0.975f
    ) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(PRESS_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(RELEASE_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }
            }
            false
        }
    }

    fun animateEntrance(
        view: View,
        translationOffsetDp: Float = 14f,
        startDelay: Long = 0L,
        duration: Long = REVEAL_DURATION
    ) {
        val offsetPx = view.resources.displayMetrics.density * translationOffsetDp
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = offsetPx
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(startDelay)
            .setDuration(duration)
            .setInterpolator(motionInterpolator)
            .start()
    }

    fun animateStaggeredChildren(
        container: ViewGroup,
        translationOffsetDp: Float = 16f,
        stepDelay: Long = STAGGER_DELAY
    ) {
        val offsetPx = container.resources.displayMetrics.density * translationOffsetDp
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = offsetPx
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(index * stepDelay)
                .setDuration(REVEAL_DURATION)
                .setInterpolator(motionInterpolator)
                .start()
        }
    }

    fun animatePulse(view: View, pulseScale: Float = 1.04f, duration: Long = 120L) {
        view.animate().cancel()
        view.animate()
            .scaleX(pulseScale)
            .scaleY(pulseScale)
            .setDuration(duration)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(motionInterpolator)
                    .start()
            }
            .start()
    }

    fun animateVisibility(
        view: View,
        visible: Boolean,
        translationOffsetDp: Float = 12f,
        duration: Long = REVEAL_DURATION
    ) {
        val offsetPx = view.resources.displayMetrics.density * translationOffsetDp

        if (visible) {
            if (view.isVisible && view.alpha == 1f && view.translationY == 0f) {
                return
            }
            view.animate().cancel()
            if (!view.isVisible) {
                view.alpha = 0f
                view.translationY = offsetPx
                view.isVisible = true
            }
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(duration)
                .setInterpolator(motionInterpolator)
                .start()
            return
        }

        if (!view.isVisible) {
            return
        }
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationY(offsetPx * 0.72f)
            .setDuration(duration)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                if (!visible) {
                    view.isVisible = false
                }
            }
            .start()
    }
}

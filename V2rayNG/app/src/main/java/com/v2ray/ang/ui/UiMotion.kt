package com.v2ray.ang.ui

import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.v2ray.ang.R

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

    fun attachPressFeedbackAlpha(
        source: View,
        target: View = source,
        pressedAlpha: Float = 0.96f
    ) {
        source.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.animate()
                        .alpha(pressedAlpha)
                        .setDuration(PRESS_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .alpha(1f)
                        .setDuration(RELEASE_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }
            }
            false
        }
    }

    fun attachPressFeedbackStroke(
        source: View,
        target: com.google.android.material.card.MaterialCardView,
        pressedStrokeWidth: Int,
        pressedStrokeColor: Int
    ) {
        val originalStrokeWidth = target.strokeWidth
        val originalStrokeColor = target.strokeColor
        source.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    target.animate().cancel()
                    target.strokeWidth = pressedStrokeWidth
                    target.setStrokeColor(pressedStrokeColor)
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    target.strokeWidth = originalStrokeWidth
                    target.setStrokeColor(originalStrokeColor)
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
        stepDelay: Long = STAGGER_DELAY,
        startDelay: Long = 0L
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
                .setStartDelay(startDelay + (index * stepDelay))
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
        view.setTag(R.id.tag_visibility_target, visible)

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
                val targetVisible = (view.getTag(R.id.tag_visibility_target) as? Boolean) == true
                if (!targetVisible) {
                    view.isVisible = false
                } else {
                    view.alpha = 1f
                    view.translationY = 0f
                }
            }
            .start()
    }

    fun setVisibility(view: View, visible: Boolean) {
        view.animate().cancel()
        view.alpha = if (visible) 1f else 0f
        view.translationY = 0f
        view.isVisible = visible
        view.setTag(R.id.tag_visibility_target, visible)
    }
}

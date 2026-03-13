package com.v2ray.ang.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.v2ray.ang.R

object UiMotion {
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
                        .setDuration(MotionTokens.PRESS_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(MotionTokens.RELEASE_DURATION)
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
                        .setDuration(MotionTokens.PRESS_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    target.animate().cancel()
                    target.animate()
                        .alpha(1f)
                        .setDuration(MotionTokens.RELEASE_DURATION)
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
        duration: Long = MotionTokens.REVEAL_DURATION
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
        stepDelay: Long = MotionTokens.STAGGER_DELAY,
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
                .setDuration(MotionTokens.REVEAL_DURATION)
                .setInterpolator(motionInterpolator)
                .start()
        }
    }

    fun animatePulse(view: View, pulseScale: Float = 1.04f, duration: Long = MotionTokens.PULSE_DEFAULT) {
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

    fun animateEntranceOnce(
        view: View,
        key: Any?,
        translationOffsetDp: Float = 18f,
        scaleFrom: Float = 0.985f,
        startDelay: Long = 0L,
        duration: Long = MotionTokens.LIST_ITEM_ENTRANCE_DURATION
    ) {
        val lastKey = view.getTag(R.id.tag_motion_animated_once)
        if (lastKey == key) {
            return
        }
        view.setTag(R.id.tag_motion_animated_once, key)
        val offsetPx = view.resources.displayMetrics.density * translationOffsetDp
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = offsetPx
        view.scaleX = scaleFrom
        view.scaleY = scaleFrom
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(startDelay)
            .setDuration(duration)
            .setInterpolator(motionInterpolator)
            .start()
    }

    fun animateStatePulse(
        view: View,
        expandScale: Float = 1.016f,
        contractScale: Float = 0.992f,
        duration: Long = MotionTokens.STATUS_TRANSITION_DURATION
    ) {
        (view.getTag(R.id.tag_motion_running_animator) as? Animator)?.cancel()

        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, expandScale, contractScale, 1f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, expandScale, contractScale, 1f)
        val alpha = PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.98f, 1f)
        val animator = ObjectAnimator.ofPropertyValuesHolder(view, scaleX, scaleY, alpha).apply {
            interpolator = motionInterpolator
            this.duration = duration
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    view.setTag(R.id.tag_motion_running_animator, null)
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.alpha = 1f
                }

                override fun onAnimationCancel(animation: Animator) {
                    view.setTag(R.id.tag_motion_running_animator, null)
                    view.scaleX = 1f
                    view.scaleY = 1f
                    view.alpha = 1f
                }
            })
        }
        view.setTag(R.id.tag_motion_running_animator, animator)
        animator.start()
    }

    fun animateFocusShift(
        primary: View,
        secondary: View? = null,
        translationOffsetDp: Float = 8f,
        duration: Long = MotionTokens.EMPHASIS_DURATION
    ) {
        val offsetPx = primary.resources.displayMetrics.density * translationOffsetDp
        primary.animate().cancel()
        secondary?.animate()?.cancel()

        primary.translationY = offsetPx * 0.35f
        primary.alpha = 0.94f
        val primaryAnimator = primary.animate()
            .translationY(0f)
            .alpha(1f)
            .setDuration(duration)
            .setInterpolator(motionInterpolator)

        if (secondary == null) {
            primaryAnimator.start()
            return
        }

        secondary.translationY = offsetPx * 0.28f
        secondary.alpha = 0.82f
        val secondarySet = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(secondary, View.TRANSLATION_Y, secondary.translationY, 0f),
                ObjectAnimator.ofFloat(secondary, View.ALPHA, secondary.alpha, 1f)
            )
            interpolator = motionInterpolator
            this.duration = duration
        }
        primaryAnimator.start()
        secondarySet.start()
    }

    fun animateVisibility(
        view: View,
        visible: Boolean,
        translationOffsetDp: Float = 12f,
        duration: Long = MotionTokens.REVEAL_DURATION
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

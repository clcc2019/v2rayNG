package com.xray.ang.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.graphics.ColorUtils
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.card.MaterialCardView
import com.xray.ang.R
import kotlin.math.roundToInt
import kotlin.math.abs

object UiMotion {
    private const val FLOAT_EPSILON = 0.001f
    private val motionInterpolator = FastOutSlowInInterpolator()
    private val enterInterpolator = PathInterpolator(0.16f, 1f, 0.3f, 1f)
    private val exitInterpolator = PathInterpolator(0.7f, 0f, 0.84f, 0f)
    private val settleInterpolator = PathInterpolator(0.25f, 1f, 0.5f, 1f)
    private val emphasizeInterpolator = PathInterpolator(0.22f, 1f, 0.36f, 1f)

    private fun isMotionEnabled(view: View): Boolean {
        return ValueAnimator.areAnimatorsEnabled()
    }

    private fun canRunTransition(view: View): Boolean {
        return isMotionEnabled(view) && view.isAttachedToWindow
    }

    private fun canHandlePress(view: View): Boolean {
        return view.isEnabled && (view.isClickable || view.isLongClickable)
    }

    fun attachPressFeedback(
        view: View,
        pressedScale: Float = 0.975f
    ) {
        view.setOnTouchListener { target, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!canHandlePress(target)) {
                        return@setOnTouchListener false
                    }
                    if (!isMotionEnabled(target)) {
                        return@setOnTouchListener false
                    }
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
                    if (!isMotionEnabled(target)) {
                        target.scaleX = 1f
                        target.scaleY = 1f
                        return@setOnTouchListener false
                    }
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

    fun attachPressFeedbackComposite(
        source: View,
        scaleTarget: View = source,
        alphaTarget: View = source,
        pressedScale: Float = 0.982f,
        pressedAlpha: Float = 0.96f
    ) {
        source.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!canHandlePress(source)) {
                        return@setOnTouchListener false
                    }
                    if (!isMotionEnabled(scaleTarget)) {
                        return@setOnTouchListener false
                    }
                    scaleTarget.animate().cancel()
                    alphaTarget.animate().cancel()
                    scaleTarget.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .setDuration(MotionTokens.PRESS_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                    alphaTarget.animate()
                        .alpha(pressedAlpha)
                        .setDuration(MotionTokens.PRESS_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!isMotionEnabled(scaleTarget)) {
                        scaleTarget.scaleX = 1f
                        scaleTarget.scaleY = 1f
                        alphaTarget.alpha = 1f
                        return@setOnTouchListener false
                    }
                    scaleTarget.animate().cancel()
                    alphaTarget.animate().cancel()
                    scaleTarget.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(MotionTokens.RELEASE_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                    alphaTarget.animate()
                        .alpha(1f)
                        .setDuration(MotionTokens.RELEASE_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }
            }
            false
        }
    }

    fun attachPressFeedbackDock(
        source: View,
        surfaceTarget: View = source,
        pressedScale: Float = 0.992f,
        pressedTranslationDp: Float = 1.5f,
        pressedAlpha: Float = 0.97f
    ) {
        val pressedTranslationPx = source.resources.displayMetrics.density * pressedTranslationDp
        source.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!canHandlePress(source)) {
                        return@setOnTouchListener false
                    }
                    if (!isMotionEnabled(surfaceTarget)) {
                        return@setOnTouchListener false
                    }
                    surfaceTarget.animate().cancel()
                    surfaceTarget.animate()
                        .scaleX(pressedScale)
                        .scaleY(pressedScale)
                        .translationY(pressedTranslationPx)
                        .alpha(pressedAlpha)
                        .setDuration(MotionTokens.PRESS_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!isMotionEnabled(surfaceTarget)) {
                        surfaceTarget.scaleX = 1f
                        surfaceTarget.scaleY = 1f
                        surfaceTarget.translationY = 0f
                        surfaceTarget.alpha = 1f
                        return@setOnTouchListener false
                    }
                    surfaceTarget.animate().cancel()
                    surfaceTarget.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .alpha(1f)
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
                    if (!canHandlePress(source)) {
                        return@setOnTouchListener false
                    }
                    if (!isMotionEnabled(target)) {
                        return@setOnTouchListener false
                    }
                    target.animate().cancel()
                    target.animate()
                        .alpha(pressedAlpha)
                        .setDuration(MotionTokens.PRESS_DURATION)
                        .setInterpolator(motionInterpolator)
                        .start()
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!isMotionEnabled(target)) {
                        target.alpha = 1f
                        return@setOnTouchListener false
                    }
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
                    if (!canHandlePress(source)) {
                        return@setOnTouchListener false
                    }
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
        if (!canRunTransition(view)) {
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            if (!view.isVisible) {
                view.isVisible = true
            }
            return
        }
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = offsetPx
        view.scaleX = 1f
        view.scaleY = 1f
        if (!view.isVisible) {
            view.isVisible = true
        }
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(startDelay)
            .setDuration(duration)
            .setInterpolator(enterInterpolator)
            .start()
    }

    fun animateStaggeredChildren(
        container: ViewGroup,
        translationOffsetDp: Float = 16f,
        stepDelay: Long = MotionTokens.STAGGER_DELAY,
        startDelay: Long = 0L
    ) {
        val offsetPx = container.resources.displayMetrics.density * translationOffsetDp
        if (!canRunTransition(container)) {
            for (index in 0 until container.childCount) {
                container.getChildAt(index).apply {
                    alpha = 1f
                    translationY = 0f
                    scaleX = 1f
                    scaleY = 1f
                }
            }
            return
        }
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = offsetPx
            child.scaleX = 1f
            child.scaleY = 1f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(startDelay + (index * stepDelay))
                .setDuration(MotionTokens.REVEAL_DURATION)
                .setInterpolator(enterInterpolator)
                .start()
        }
    }

    fun animatePulse(view: View, pulseScale: Float = 1.04f, duration: Long = MotionTokens.PULSE_DEFAULT) {
        if (!canRunTransition(view)) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.animate().cancel()
        view.animate()
            .scaleX(pulseScale)
            .scaleY(pulseScale)
            .setDuration(duration)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                if (!view.isAttachedToWindow) {
                    view.scaleX = 1f
                    view.scaleY = 1f
                    return@withEndAction
                }
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(duration)
                    .setInterpolator(motionInterpolator)
                    .start()
            }
            .start()
    }

    fun animateAlpha(
        view: View,
        targetAlpha: Float,
        duration: Long = MotionTokens.SHORT_ANIMATION_DURATION,
        startDelay: Long = 0L
    ) {
        if (abs(view.alpha - targetAlpha) < FLOAT_EPSILON) {
            return
        }
        if (!canRunTransition(view)) {
            view.alpha = targetAlpha
            return
        }
        view.animate().cancel()
        view.animate()
            .alpha(targetAlpha)
            .setStartDelay(startDelay)
            .setDuration(duration)
            .setInterpolator(settleInterpolator)
            .start()
    }

    fun animateTextChange(
        textView: TextView,
        newText: CharSequence?,
        settledAlpha: Float = 1f,
        translationOffsetDp: Float = 4f,
        duration: Long = MotionTokens.MEDIUM_ANIMATION_DURATION
    ) {
        val targetText = newText ?: ""
        val textChanged = textView.text?.toString().orEmpty() != targetText.toString()
        val alphaChanged = abs(textView.alpha - settledAlpha) > FLOAT_EPSILON
        if (!textChanged && !alphaChanged) {
            return
        }
        if (!canRunTransition(textView)) {
            textView.text = targetText
            textView.alpha = settledAlpha
            textView.translationY = 0f
            return
        }
        if (!textChanged) {
            textView.animate().cancel()
            textView.translationY = 0f
            textView.animate()
                .alpha(settledAlpha)
                .setDuration(duration)
                .setInterpolator(motionInterpolator)
                .start()
            return
        }

        val offsetPx = textView.resources.displayMetrics.density * translationOffsetDp
        val exitDuration = (duration * 0.42f).toLong().coerceAtLeast(60L)
        val enterDuration = (duration - exitDuration).coerceAtLeast(90L)
        textView.animate().cancel()
        textView.animate()
            .alpha(0f)
            .translationY(-offsetPx * 0.35f)
            .setDuration(exitDuration)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                textView.text = targetText
                if (!canRunTransition(textView)) {
                    textView.alpha = settledAlpha
                    textView.translationY = 0f
                    return@withEndAction
                }
                textView.translationY = offsetPx * 0.35f
                textView.animate().cancel()
                textView.animate()
                    .alpha(settledAlpha)
                    .translationY(0f)
                    .setDuration(enterDuration)
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
        if (!canRunTransition(view)) {
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        val offsetPx = view.resources.displayMetrics.density * translationOffsetDp
        val startScale = scaleFrom.coerceIn(0.9f, 1f)
        view.animate().cancel()
        view.alpha = 0f
        view.translationY = offsetPx
        view.scaleX = startScale
        view.scaleY = startScale
        if (!view.isVisible) {
            view.isVisible = true
        }
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(startDelay)
            .setDuration(duration)
            .setInterpolator(enterInterpolator)
            .start()
    }

    fun settleView(view: View) {
        view.animate().cancel()
        view.alpha = 1f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    fun animateStatePulse(
        view: View,
        expandScale: Float = 1.016f,
        contractScale: Float = 0.992f,
        duration: Long = MotionTokens.STATUS_TRANSITION_DURATION
    ) {
        if (!canRunTransition(view)) {
            view.scaleX = 1f
            view.scaleY = 1f
            view.alpha = 1f
            return
        }
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

    fun animateCardSurface(
        card: MaterialCardView,
        backgroundColor: Int,
        strokeColor: Int,
        strokeWidth: Int = card.strokeWidth,
        duration: Long = MotionTokens.MEDIUM_ANIMATION_DURATION
    ) {
        val currentBackground = card.cardBackgroundColor?.defaultColor ?: backgroundColor
        val currentStrokeColor = card.strokeColor
        val currentStrokeWidth = card.strokeWidth
        val styleChanged = currentBackground != backgroundColor ||
            currentStrokeColor != strokeColor ||
            currentStrokeWidth != strokeWidth
        if (!styleChanged) {
            return
        }
        if (!canRunTransition(card)) {
            card.setCardBackgroundColor(backgroundColor)
            card.setStrokeColor(strokeColor)
            card.strokeWidth = strokeWidth
            return
        }

        (card.getTag(R.id.tag_motion_card_style_animator) as? Animator)?.cancel()

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            interpolator = emphasizeInterpolator
            this.duration = duration
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                card.setCardBackgroundColor(ColorUtils.blendARGB(currentBackground, backgroundColor, fraction))
                card.setStrokeColor(ColorUtils.blendARGB(currentStrokeColor, strokeColor, fraction))
                card.strokeWidth = lerp(currentStrokeWidth, strokeWidth, fraction)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    card.setTag(R.id.tag_motion_card_style_animator, null)
                    card.setCardBackgroundColor(backgroundColor)
                    card.setStrokeColor(strokeColor)
                    card.strokeWidth = strokeWidth
                }

                override fun onAnimationCancel(animation: Animator) {
                    card.setTag(R.id.tag_motion_card_style_animator, null)
                }
            })
        }
        card.setTag(R.id.tag_motion_card_style_animator, animator)
        animator.start()
    }

    fun animateFocusShift(
        primary: View,
        secondary: View? = null,
        translationOffsetDp: Float = 8f,
        duration: Long = MotionTokens.EMPHASIS_DURATION
    ) {
        val offsetPx = primary.resources.displayMetrics.density * translationOffsetDp
        if (!canRunTransition(primary)) {
            primary.translationY = 0f
            primary.alpha = 1f
            secondary?.translationY = 0f
            secondary?.alpha = 1f
            return
        }
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
        val exitDuration = (duration * 0.78f).toLong().coerceAtLeast(MotionTokens.FAST_ANIMATION_DURATION)
        view.setTag(R.id.tag_visibility_target, visible)
        if (!canRunTransition(view)) {
            setVisibility(view, visible)
            return
        }

        if (visible) {
            if (view.isVisible && abs(view.alpha - 1f) < FLOAT_EPSILON && abs(view.translationY) < FLOAT_EPSILON) {
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
                .setInterpolator(enterInterpolator)
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
            .setDuration(exitDuration)
            .setInterpolator(exitInterpolator)
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

    fun animateHorizontalVisibility(
        view: View,
        visible: Boolean,
        translationOffsetDp: Float = 10f,
        fromStart: Boolean = false,
        duration: Long = MotionTokens.SHORT_ANIMATION_DURATION
    ) {
        val direction = if (fromStart) -1f else 1f
        val offsetPx = view.resources.displayMetrics.density * translationOffsetDp * direction
        val exitDuration = (duration * 0.78f).toLong().coerceAtLeast(MotionTokens.FAST_ANIMATION_DURATION)
        view.setTag(R.id.tag_visibility_target, visible)
        if (!canRunTransition(view)) {
            setVisibility(view, visible)
            return
        }

        if (visible) {
            if (view.isVisible &&
                abs(view.alpha - 1f) < FLOAT_EPSILON &&
                abs(view.translationX) < FLOAT_EPSILON &&
                abs(view.scaleX - 1f) < FLOAT_EPSILON &&
                abs(view.scaleY - 1f) < FLOAT_EPSILON
            ) {
                return
            }
            view.animate().cancel()
            if (!view.isVisible) {
                view.alpha = 0f
                view.translationX = offsetPx
                view.scaleX = 0.985f
                view.scaleY = 0.985f
                view.isVisible = true
            }
            view.animate()
                .alpha(1f)
                .translationX(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(duration)
                .setInterpolator(enterInterpolator)
                .start()
            return
        }

        if (!view.isVisible) {
            return
        }
        view.animate().cancel()
        view.animate()
            .alpha(0f)
            .translationX(offsetPx * 0.72f)
            .scaleX(0.985f)
            .scaleY(0.985f)
            .setDuration(exitDuration)
            .setInterpolator(exitInterpolator)
            .withEndAction {
                val targetVisible = (view.getTag(R.id.tag_visibility_target) as? Boolean) == true
                if (!targetVisible) {
                    view.isVisible = false
                } else {
                    view.alpha = 1f
                    view.translationX = 0f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            }
            .start()
    }

    fun setVisibility(view: View, visible: Boolean) {
        view.animate().cancel()
        view.alpha = if (visible) 1f else 0f
        view.translationX = 0f
        view.translationY = 0f
        view.scaleX = 1f
        view.scaleY = 1f
        view.isVisible = visible
        view.setTag(R.id.tag_visibility_target, visible)
    }

    private fun lerp(start: Int, end: Int, fraction: Float): Int {
        return (start + (end - start) * fraction).roundToInt()
    }
}

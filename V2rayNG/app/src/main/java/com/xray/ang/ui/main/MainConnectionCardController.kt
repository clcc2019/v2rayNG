package com.xray.ang.ui

import android.animation.TimeInterpolator
import android.content.Context
import android.widget.TextView
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
    enum class ServiceMessageTone {
        MUTED,
        PRIMARY,
        SUCCESS,
        WARNING,
        ERROR
    }

    private var lastSurfaceAlpha: Float? = null
    private var lastSurfaceScale: Float? = null
    private var lastSurfaceTranslationY: Float? = null
    private var lastVisible: Boolean? = null
    private var pinnedServiceMessage: CharSequence? = null
    private var pinnedServiceMessageTone: ServiceMessageTone = ServiceMessageTone.MUTED
    private var transientServiceMessage: CharSequence? = null
    private var transientServiceMessageTone: ServiceMessageTone = ServiceMessageTone.MUTED
    private var transientClearRunnable: Runnable? = null
    private var landingInfoMessage: CharSequence? = null
    private var landingInfoTone: ServiceMessageTone = ServiceMessageTone.MUTED
    private var hasRenderedPrimaryContent = false
    private var lastRenderedState: ServiceUiState? = null
    private var lastRenderedSummary: String? = null
    private var lastRenderedSummaryTone: ServiceMessageTone? = null
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
        val animateTextChanges = hasRenderedPrimaryContent
        val previousState = lastRenderedState
        val selectedProfileName = mainViewModel.getSelectedServerSnapshot()
            ?.profile
            ?.remarks
            ?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.home_connection_profile_empty)
        lastRenderedState = state
        val statusLabel = context.getString(
            when (state) {
                ServiceUiState.RUNNING -> R.string.connection_connected_short
                ServiceUiState.STARTING -> R.string.connection_starting_short
                ServiceUiState.STOPPING -> R.string.connection_stopping_short
                ServiceUiState.STOPPED -> R.string.connection_not_connected_short
            }
        )
        updateDockText(
            textView = binding.tvConnectionStatus,
            text = statusLabel,
            animate = animateTextChanges,
            settledAlpha = 1f
        )
        updateDockText(
            textView = binding.tvConnectionProfile,
            text = selectedProfileName,
            animate = animateTextChanges,
            settledAlpha = 1f
        )
        renderSummary(animate = animateTextChanges)
        applyStatusBadgeStyle(state)
        if (animateTextChanges && previousState != state) {
            UiMotion.animatePulse(binding.tvConnectionStatus, pulseScale = 1.024f, duration = MotionTokens.PULSE_QUICK)
        }
        hasRenderedPrimaryContent = true
    }

    fun showPinnedServiceMessage(message: CharSequence, tone: ServiceMessageTone) {
        clearTransientServiceMessage()
        pinnedServiceMessage = message
        pinnedServiceMessageTone = tone
        renderSummary(animate = hasRenderedPrimaryContent)
    }

    fun clearPinnedServiceMessage() {
        if (pinnedServiceMessage == null) {
            return
        }
        pinnedServiceMessage = null
        pinnedServiceMessageTone = ServiceMessageTone.MUTED
        renderSummary(animate = hasRenderedPrimaryContent)
    }

    fun showTransientServiceMessage(
        message: CharSequence,
        tone: ServiceMessageTone,
        duration: Long = 2200L
    ) {
        transientServiceMessage = message
        transientServiceMessageTone = tone
        renderSummary(animate = hasRenderedPrimaryContent)
        transientClearRunnable?.let { binding.tvConnectionSummary.removeCallbacks(it) }
        val runnable = Runnable {
            transientServiceMessage = null
            transientServiceMessageTone = ServiceMessageTone.MUTED
            renderSummary(animate = hasRenderedPrimaryContent)
        }
        transientClearRunnable = runnable
        binding.tvConnectionSummary.postDelayed(runnable, duration)
    }

    fun showLandingInfoLoading(message: CharSequence) {
        landingInfoMessage = message
        landingInfoTone = ServiceMessageTone.MUTED
        renderSummary(animate = hasRenderedPrimaryContent)
    }

    fun showLandingInfo(message: CharSequence, tone: ServiceMessageTone) {
        landingInfoMessage = message
        landingInfoTone = tone
        renderSummary(animate = hasRenderedPrimaryContent)
    }

    fun clearLandingInfo() {
        landingInfoMessage = null
        landingInfoTone = ServiceMessageTone.MUTED
        renderSummary(animate = hasRenderedPrimaryContent)
    }

    fun dismissServiceMessages() {
        clearTransientServiceMessage()
        clearPinnedServiceMessage()
        renderSummary(animate = hasRenderedPrimaryContent)
    }

    fun clear() {
        clearTransientServiceMessage()
        clearPinnedServiceMessage()
        clearLandingInfo()
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

    private fun clearTransientServiceMessage() {
        transientClearRunnable?.let { binding.tvConnectionSummary.removeCallbacks(it) }
        transientClearRunnable = null
        transientServiceMessage = null
        transientServiceMessageTone = ServiceMessageTone.MUTED
    }

    private fun renderSummary(animate: Boolean = false) {
        val message = when {
            transientServiceMessage != null -> transientServiceMessage
            pinnedServiceMessage != null -> pinnedServiceMessage
            landingInfoMessage != null -> landingInfoMessage
            else -> defaultSummaryText()
        }
        val tone = when {
            transientServiceMessage != null -> transientServiceMessageTone
            pinnedServiceMessage != null -> pinnedServiceMessageTone
            landingInfoMessage != null -> landingInfoTone
            lastRenderedState == ServiceUiState.RUNNING -> ServiceMessageTone.PRIMARY
            else -> ServiceMessageTone.MUTED
        }
        val targetText = if (message.isNullOrBlank()) context.getString(R.string.home_connection_summary_short) else message
        val targetAlpha = when {
            transientServiceMessage != null || pinnedServiceMessage != null || landingInfoMessage != null -> 1f
            lastRenderedState == ServiceUiState.RUNNING -> 0.98f
            else -> 0.94f
        }
        updateDockText(
            textView = binding.tvConnectionSummary,
            text = targetText,
            animate = animate,
            settledAlpha = targetAlpha
        )
        binding.tvConnectionSummary.setTextColor(
            ContextCompat.getColor(context, colorForTone(tone))
        )
        val renderedSummary = targetText.toString()
        if (animate &&
            (lastRenderedSummary != renderedSummary || lastRenderedSummaryTone != tone) &&
            (!message.isNullOrBlank() || tone != ServiceMessageTone.MUTED)
        ) {
            UiMotion.animatePulse(binding.tvConnectionSummary, pulseScale = 1.018f, duration = MotionTokens.PULSE_QUICK)
        }
        lastRenderedSummary = renderedSummary
        lastRenderedSummaryTone = tone
    }

    private fun defaultSummaryText(): CharSequence {
        val hasSelectedProfile = mainViewModel.getSelectedServerSnapshot() != null
        if (!hasSelectedProfile) {
            return context.getString(R.string.home_connection_summary_empty)
        }
        return when (lastRenderedState) {
            ServiceUiState.RUNNING -> context.getString(R.string.home_connection_summary_running)
            else -> context.getString(R.string.home_connection_summary_short)
        }
    }

    private fun updateDockText(
        textView: TextView,
        text: CharSequence?,
        animate: Boolean,
        settledAlpha: Float
    ) {
        if (!animate) {
            textView.animate().cancel()
            textView.text = text
            textView.alpha = settledAlpha
            textView.translationY = 0f
            return
        }
        UiMotion.animateTextChange(
            textView = textView,
            newText = text,
            settledAlpha = settledAlpha,
            translationOffsetDp = 5f,
            duration = MotionTokens.MEDIUM_ANIMATION_DURATION
        )
    }

    private fun colorForTone(tone: ServiceMessageTone): Int {
        return when (tone) {
            ServiceMessageTone.MUTED -> R.color.color_connection_dock_text_secondary
            ServiceMessageTone.PRIMARY -> R.color.color_connection_dock_text_primary
            ServiceMessageTone.SUCCESS -> R.color.color_connection_dock_text_primary
            ServiceMessageTone.WARNING -> R.color.color_connection_dock_text_primary
            ServiceMessageTone.ERROR -> R.color.color_connection_dock_text_primary
        }
    }
}

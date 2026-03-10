package com.v2ray.ang.ui

import android.animation.TimeInterpolator
import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.view.isVisible
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import com.v2ray.ang.dto.ServersCache
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.Utils
import com.v2ray.ang.viewmodel.MainViewModel

class MainConnectionCardController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val mainViewModel: MainViewModel,
    private val motionInterpolator: TimeInterpolator
) {
    private data class ChipUiModel(
        val backgroundColorRes: Int,
        val textColorRes: Int,
        val iconRes: Int? = null
    )

    private data class StatusBadgeUiModel(
        val text: CharSequence,
        val chip: ChipUiModel
    )

    private var lastConnectionSnapshot: ServersCache? = null
    private var lastConnectionState: ServiceUiState? = null

    fun updateVisibility(visible: Boolean) {
        UiMotion.animateVisibility(binding.cardConnection, visible, translationOffsetDp = 18f)
    }

    fun render(state: ServiceUiState) {
        val snapshot = ensureSelectedServerSnapshot()
        val previousGuid = lastConnectionSnapshot?.guid
        val previousState = lastConnectionState
        val profile = snapshot?.profile
        val shouldAnimate = lastConnectionSnapshot != null || lastConnectionState != null
        val connectionTitle = buildConnectionTitle(snapshot) ?: context.getString(R.string.connection_not_connected)
        setTextWithFade(binding.tvActiveServer, connectionTitle, shouldAnimate)
        binding.tvConnectionLabel.text = context.getString(R.string.current_config)
        setBadgeText(binding.tvConfigType, profile?.configType?.name.orEmpty(), shouldAnimate)

        val configBadgeText = buildConfigBadgeText(snapshot)
        setBadgeText(binding.tvConfigMeta, configBadgeText.orEmpty(), shouldAnimate)

        val serverAddress = buildConnectionAddress(snapshot) ?: context.getString(R.string.connection_not_connected)
        setTextWithFade(binding.tvConnectionAddress, serverAddress, shouldAnimate)

        val metaLine = buildConnectionMetaLine(snapshot, state)
        setTextWithVisibility(binding.tvConnectionMetaLine, metaLine, shouldAnimate)

        val badgeUiModel = buildConnectionBadgeUiModel(state, snapshot)
        if (binding.tvConnectionBadge.text.toString() != badgeUiModel.text.toString() && shouldAnimate) {
            UiMotion.animatePulse(binding.tvConnectionBadge, pulseScale = 1.03f, duration = MotionTokens.PULSE_DEFAULT)
        }
        binding.tvConnectionBadge.text = badgeUiModel.text
        applyChipUiModel(binding.tvConnectionBadge, badgeUiModel.chip)
        updateTestButtonState(state == ServiceUiState.RUNNING)
        val shouldPulseCard = shouldAnimate && (
            previousGuid != snapshot?.guid || (previousState != null && previousState != state)
        )
        if (shouldPulseCard) {
            UiMotion.animatePulse(binding.cardConnection, pulseScale = 1.01f, duration = MotionTokens.PULSE_LONG)
        }
        lastConnectionSnapshot = snapshot
        lastConnectionState = state
    }

    fun updateStateVisuals(state: ServiceUiState, animate: Boolean) {
        val targetAlpha = if (state == ServiceUiState.STARTING || state == ServiceUiState.STOPPING) 0.92f else 1f
        binding.layoutConnectionSurface.animate().cancel()
        if (!animate) {
            binding.layoutConnectionSurface.alpha = targetAlpha
            return
        }
        binding.layoutConnectionSurface.animate()
            .alpha(targetAlpha)
            .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .start()
    }

    private fun setTextWithFade(target: TextView, value: CharSequence, animate: Boolean) {
        val newText = value.toString()
        if (target.text.toString() == newText) {
            return
        }
        target.animate().cancel()
        if (!animate) {
            target.text = newText
            return
        }
        target.animate()
            .alpha(0f)
            .setDuration(MotionTokens.FAST_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                target.text = newText
                target.animate()
                    .alpha(1f)
                    .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                    .setInterpolator(motionInterpolator)
                    .start()
            }
            .start()
    }

    private fun setTextWithVisibility(target: TextView, value: CharSequence?, animate: Boolean) {
        val textValue = value?.toString().orEmpty()
        val shouldShow = textValue.isNotBlank()
        if (!shouldShow) {
            hideText(target, animate)
            return
        }

        if (showText(target, textValue, animate)) {
            return
        }

        setTextWithFade(target, textValue, animate)
    }

    private fun setBadgeText(target: TextView, value: String, animate: Boolean) {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            if (target.isVisible) {
                hideText(target, animate)
            }
            return
        }
        if (showText(target, trimmed, animate)) {
            return
        }
        setTextWithFade(target, trimmed, animate)
    }

    private fun showText(target: TextView, value: CharSequence, animate: Boolean): Boolean {
        if (target.isVisible) {
            return false
        }
        target.isVisible = true
        target.alpha = if (animate) 0f else 1f
        target.text = value
        if (animate) {
            target.animate()
                .alpha(1f)
                .setDuration(MotionTokens.SHORT_ANIMATION_DURATION)
                .setInterpolator(motionInterpolator)
                .start()
        }
        return true
    }

    private fun hideText(target: TextView, animate: Boolean) {
        if (!target.isVisible) {
            target.text = ""
            return
        }
        target.animate().cancel()
        if (!animate) {
            target.isVisible = false
            target.text = ""
            target.alpha = 1f
            return
        }
        target.animate()
            .alpha(0f)
            .setDuration(MotionTokens.FAST_ANIMATION_DURATION)
            .setInterpolator(motionInterpolator)
            .withEndAction {
                target.isVisible = false
                target.text = ""
                target.alpha = 1f
            }
            .start()
    }

    private fun applyChipUiModel(target: TextView, model: ChipUiModel) {
        DrawableCompat.setTint(target.background.mutate(), ContextCompat.getColor(context, model.backgroundColorRes))
        target.setTextColor(ContextCompat.getColor(context, model.textColorRes))
        target.setCompoundDrawablesRelativeWithIntrinsicBounds(
            model.iconRes?.let { createFeedbackIconForRes(it, model.textColorRes) },
            null,
            null,
            null
        )
    }

    private fun updateTestButtonState(isRunning: Boolean) {
        val backgroundColorRes = R.color.md_theme_surface
        val strokeColorRes = R.color.md_theme_outlineVariant
        val contentColorRes = R.color.md_theme_onSurfaceVariant
        binding.layoutTest.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(context, backgroundColorRes))
        binding.layoutTest.strokeColor =
            ColorStateList.valueOf(ContextCompat.getColor(context, strokeColorRes))
        binding.layoutTest.setTextColor(ContextCompat.getColor(context, contentColorRes))
        binding.layoutTest.iconTint = ColorStateList.valueOf(ContextCompat.getColor(context, contentColorRes))
    }

    private fun createFeedbackIconForRes(iconRes: Int, tintColorRes: Int) =
        AppCompatResources.getDrawable(context, iconRes)?.mutate()?.apply {
            DrawableCompat.setTint(this, ContextCompat.getColor(context, tintColorRes))
        }

    private fun statusBadge(
        text: CharSequence,
        backgroundColorRes: Int,
        textColorRes: Int
    ) = StatusBadgeUiModel(
        text = text,
        chip = ChipUiModel(
            backgroundColorRes = backgroundColorRes,
            textColorRes = textColorRes
        )
    )

    private fun buildConnectionBadgeUiModel(state: ServiceUiState, snapshot: ServersCache?): StatusBadgeUiModel {
        val latencyText = buildConnectionLatencyText(snapshot)
        if (state == ServiceUiState.RUNNING && !latencyText.isNullOrBlank()) {
            return statusBadge(
                text = latencyText,
                backgroundColorRes = R.color.md_theme_tertiaryContainer,
                textColorRes = R.color.md_theme_onTertiaryContainer
            )
        }

        return when (state) {
            ServiceUiState.STARTING -> statusBadge(
                text = context.getString(R.string.connection_starting_short),
                backgroundColorRes = R.color.md_theme_secondaryContainer,
                textColorRes = R.color.md_theme_onSecondaryContainer
            )

            ServiceUiState.STOPPING -> statusBadge(
                text = context.getString(R.string.connection_stopping_short),
                backgroundColorRes = R.color.md_theme_surfaceVariant,
                textColorRes = R.color.md_theme_onSurfaceVariant
            )

            ServiceUiState.RUNNING -> statusBadge(
                text = context.getString(R.string.connection_connected_short),
                backgroundColorRes = R.color.md_theme_tertiaryContainer,
                textColorRes = R.color.md_theme_onTertiaryContainer
            )

            ServiceUiState.STOPPED -> statusBadge(
                text = context.getString(R.string.connection_not_connected_short),
                backgroundColorRes = R.color.md_theme_surfaceVariant,
                textColorRes = R.color.md_theme_onSurfaceVariant
            )
        }
    }

    private fun ensureSelectedServerSnapshot(): ServersCache? {
        mainViewModel.getSelectedServerSnapshot()?.let { return it }
        val selectedGuid = MmkvManager.getSelectServer().orEmpty()
        if (selectedGuid.isNotBlank()) {
            mainViewModel.onSelectedServerChanged(selectedGuid)
            return mainViewModel.getSelectedServerSnapshot()
        }
        return null
    }

    private fun buildConnectionAddress(snapshot: ServersCache?): String? {
        val profile = snapshot?.profile ?: return null
        val server = profile.server?.trim().orEmpty()
        val port = profile.serverPort?.trim().orEmpty()
        return when {
            server.isNotEmpty() && port.isNotEmpty() -> Utils.getIpv6Address(server) + ":" + port
            server.isNotEmpty() -> Utils.getIpv6Address(server)
            port.isNotEmpty() -> port
            profile.configType == EConfigType.CUSTOM -> profile.getServerAddressAndPort()
            else -> null
        }
    }

    private fun buildConnectionTitle(snapshot: ServersCache?): CharSequence? {
        val profile = snapshot?.profile ?: return null
        return profile.remarks.trim().ifEmpty { profile.configType.name }
    }

    private fun buildConfigBadgeText(snapshot: ServersCache?): String? {
        val network = snapshot?.profile?.network?.trim().orEmpty()
        return network.takeIf { it.isNotEmpty() }?.uppercase()
    }

    private fun buildConnectionLatencyText(snapshot: ServersCache?): String? {
        val delayMillis = snapshot?.testDelayMillis ?: 0L
        return when {
            delayMillis > 0L -> "$delayMillis ms"
            else -> null
        }
    }

    private fun buildConnectionMetaLine(snapshot: ServersCache?, state: ServiceUiState): String? {
        val profile = snapshot?.profile ?: return null
        val details = linkedSetOf<String>()
        profile.security?.trim()?.takeIf { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }?.let {
            details += it.uppercase()
        }
        profile.flow?.trim()?.takeIf { it.isNotEmpty() }?.let { details += it }
        profile.method?.trim()?.takeIf { it.isNotEmpty() }?.let { details += it }
        profile.host?.trim()?.takeIf { it.isNotEmpty() }?.let { details += it }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING) &&
            (state == ServiceUiState.STARTING || state == ServiceUiState.RUNNING)
        ) {
            details += context.getString(R.string.toast_warning_pref_proxysharing_short)
        }
        return details.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

}

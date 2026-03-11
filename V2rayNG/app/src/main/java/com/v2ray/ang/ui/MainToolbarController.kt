package com.v2ray.ang.ui

import android.animation.Animator
import android.animation.Keyframe
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.Interpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding

class MainToolbarController(
    private val activity: AppCompatActivity,
    private val binding: ActivityMainBinding,
    private val motionInterpolator: Interpolator,
    private val onOpenDrawer: () -> Unit,
    private val statusProvider: () -> ToolbarStatusState
) {
    private var statusText: TextView? = null
    private var appIcon: ImageView? = null
    private var waveAnimator: Animator? = null
    private var transientMessage: CharSequence? = null
    private var transientClearRunnable: Runnable? = null

    fun attach() {
        val actionView = activity.layoutInflater.inflate(R.layout.item_toolbar_app_action, binding.toolbar, false)
        val layoutParams = Toolbar.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        binding.toolbar.addView(actionView, 0, layoutParams)
        statusText = actionView.findViewById(R.id.toolbar_status_text)
        appIcon = actionView.findViewById(R.id.toolbar_app_icon)
        val openDrawerAction: (View) -> Unit = { view ->
            view.hapticClick()
            onOpenDrawer()
        }
        actionView.setOnClickListener { openDrawerAction(actionView) }
        appIcon?.setOnClickListener { icon -> openDrawerAction(icon) }
    }

    fun updateStatus(serviceUiState: ServiceUiState, isTesting: Boolean) {
        transientMessage?.let { message ->
            applyStatus(message)
            return
        }
        val statusResId = when {
            isTesting -> R.string.connection_test_testing
            serviceUiState == ServiceUiState.STARTING -> R.string.connection_starting_short
            serviceUiState == ServiceUiState.STOPPING -> R.string.connection_stopping_short
            else -> null
        }
        val message = statusResId?.let { activity.getString(it) } ?: activity.getString(R.string.app_name)
        applyStatus(message)
    }

    fun showTransientMessage(message: CharSequence, duration: Long = 2200L) {
        transientMessage = message
        applyStatus(message)
        val targetView = statusText
        transientClearRunnable?.let { targetView?.removeCallbacks(it) }
        val runnable = Runnable {
            transientMessage = null
            val status = statusProvider()
            updateStatus(status.serviceUiState, status.isTesting)
        }
        transientClearRunnable = runnable
        targetView?.postDelayed(runnable, duration)
    }

    fun clear() {
        transientClearRunnable?.let { statusText?.removeCallbacks(it) }
        transientClearRunnable = null
        stopWaveAnimation()
    }

    private fun applyStatus(message: CharSequence?) {
        val view = statusText ?: return
        if (message.isNullOrBlank()) {
            view.isVisible = false
            stopWaveAnimation()
            return
        }
        view.text = message
        view.isVisible = true
        startWaveAnimation()
    }

    private fun startWaveAnimation() {
        val iconView = appIcon ?: return
        val running = waveAnimator?.isRunning == true
        if (running) return

        val scaleX = PropertyValuesHolder.ofKeyframe(
            View.SCALE_X,
            Keyframe.ofFloat(0f, 1f),
            Keyframe.ofFloat(0.32f, 1.08f),
            Keyframe.ofFloat(0.58f, 1f),
            Keyframe.ofFloat(0.8f, 1.04f),
            Keyframe.ofFloat(1f, 1f)
        )
        val scaleY = PropertyValuesHolder.ofKeyframe(
            View.SCALE_Y,
            Keyframe.ofFloat(0f, 1f),
            Keyframe.ofFloat(0.32f, 1.08f),
            Keyframe.ofFloat(0.58f, 1f),
            Keyframe.ofFloat(0.8f, 1.04f),
            Keyframe.ofFloat(1f, 1f)
        )
        val alpha = PropertyValuesHolder.ofKeyframe(
            View.ALPHA,
            Keyframe.ofFloat(0f, 1f),
            Keyframe.ofFloat(0.32f, 0.82f),
            Keyframe.ofFloat(0.58f, 1f),
            Keyframe.ofFloat(0.8f, 0.9f),
            Keyframe.ofFloat(1f, 1f)
        )
        val translateY = PropertyValuesHolder.ofKeyframe(
            View.TRANSLATION_Y,
            Keyframe.ofFloat(0f, 0f),
            Keyframe.ofFloat(0.32f, -1.4f),
            Keyframe.ofFloat(0.58f, 0f),
            Keyframe.ofFloat(0.8f, -0.6f),
            Keyframe.ofFloat(1f, 0f)
        )
        val rotation = PropertyValuesHolder.ofKeyframe(
            View.ROTATION,
            Keyframe.ofFloat(0f, 0f),
            Keyframe.ofFloat(0.32f, -1.8f),
            Keyframe.ofFloat(0.58f, 0f),
            Keyframe.ofFloat(0.8f, 1.2f),
            Keyframe.ofFloat(1f, 0f)
        )
        val animator = ObjectAnimator.ofPropertyValuesHolder(iconView, scaleX, scaleY, alpha, translateY, rotation).apply {
            duration = MotionTokens.TOOLBAR_WAVE_DURATION
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = motionInterpolator
        }
        waveAnimator = animator
        animator.start()
    }

    private fun stopWaveAnimation() {
        waveAnimator?.cancel()
        waveAnimator = null
        appIcon?.apply {
            scaleX = 1f
            scaleY = 1f
            alpha = 1f
            translationY = 0f
            rotation = 0f
        }
    }
}

data class ToolbarStatusState(
    val serviceUiState: ServiceUiState,
    val isTesting: Boolean
)

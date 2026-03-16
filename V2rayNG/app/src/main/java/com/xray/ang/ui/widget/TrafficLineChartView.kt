package com.xray.ang.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.util.AttributeSet
import android.view.View
import com.xray.ang.R
import kotlin.math.max

class TrafficLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class TrafficPoint(
        val timestamp: Long,
        val upRate: Long,
        val downRate: Long
    )

    private var series: List<TrafficPoint> = emptyList()
    private var focusedIndex: Int = -1
    private var focusListener: ((TrafficPoint) -> Unit)? = null

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = context.getColor(R.color.color_card_outline)
        alpha = 110
    }

    private val upPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = context.getColor(R.color.color_home_metric_warn_text)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = context.getColor(R.color.color_home_metric_good_text)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val focusLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = context.getColor(R.color.color_home_on_surface_muted)
        alpha = 140
    }

    private val upDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.color_home_metric_warn_text)
    }

    private val downDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = context.getColor(R.color.color_home_metric_good_text)
    }

    fun setSeries(points: List<TrafficPoint>) {
        series = points
        focusedIndex = series.lastIndex
        invalidate()
    }

    fun clearFocusPoint() {
        focusedIndex = -1
        invalidate()
    }

    fun setOnPointFocusListener(listener: ((TrafficPoint) -> Unit)?) {
        focusListener = listener
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val left = dp(6f)
        val right = width - dp(6f)
        val top = dp(6f)
        val bottom = height - dp(6f)
        if (right <= left || bottom <= top) return

        drawGrid(canvas, left, top, right, bottom)

        val size = series.size
        if (size == 0) return

        val maxValue = max(
            series.maxOfOrNull { it.upRate } ?: 1L,
            series.maxOfOrNull { it.downRate } ?: 1L
        ).coerceAtLeast(1L)

        if (size == 1) {
            val point = series[0]
            val x = right
            val upY = toY(point.upRate, top, bottom, maxValue)
            val downY = toY(point.downRate, top, bottom, maxValue)
            canvas.drawCircle(x, downY, dp(3.2f), downDotPaint)
            canvas.drawCircle(x, upY, dp(3.2f), upDotPaint)
            return
        }

        val upPath = buildPath(series, size, left, top, right, bottom, maxValue, useUp = true)
        val downPath = buildPath(series, size, left, top, right, bottom, maxValue, useUp = false)

        canvas.drawPath(downPath, downPaint)
        canvas.drawPath(upPath, upPaint)

        if (focusedIndex in 0 until size) {
            val step = (right - left) / (size - 1).coerceAtLeast(1)
            val x = left + focusedIndex * step
            val point = series[focusedIndex]
            val upY = toY(point.upRate, top, bottom, maxValue)
            val downY = toY(point.downRate, top, bottom, maxValue)
            canvas.drawLine(x, top, x, bottom, focusLinePaint)
            canvas.drawCircle(x, downY, dp(3.2f), downDotPaint)
            canvas.drawCircle(x, upY, dp(3.2f), upDotPaint)
        }
    }

    private fun drawGrid(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float) {
        val midY = top + (bottom - top) * 0.5f
        val highY = top + (bottom - top) * 0.25f
        canvas.drawLine(left, midY, right, midY, gridPaint)
        canvas.drawLine(left, highY, right, highY, gridPaint)
    }

    private fun buildPath(
        series: List<TrafficPoint>,
        size: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        maxValue: Long,
        useUp: Boolean
    ): Path {
        val path = Path()
        val step = (right - left) / (size - 1).coerceAtLeast(1)
        var prevX = left
        val firstValue = if (useUp) series[0].upRate else series[0].downRate
        var prevY = toY(firstValue, top, bottom, maxValue)
        path.moveTo(prevX, prevY)
        for (i in 1 until size) {
            val x = left + i * step
            val value = if (useUp) series[i].upRate else series[i].downRate
            val y = toY(value, top, bottom, maxValue)
            val midX = (prevX + x) * 0.5f
            val midY = (prevY + y) * 0.5f
            path.quadTo(prevX, prevY, midX, midY)
            if (i == size - 1) {
                path.lineTo(x, y)
            }
            prevX = x
            prevY = y
        }
        return path
    }

    private fun toY(value: Long, top: Float, bottom: Float, maxValue: Long): Float {
        val ratio = (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
        return bottom - (bottom - top) * ratio
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (series.size <= 1) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                val index = resolveIndex(event.x)
                if (index in series.indices) {
                    focusedIndex = index
                    focusListener?.invoke(series[index])
                    invalidate()
                }
                parent.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun resolveIndex(x: Float): Int {
        val left = dp(6f)
        val right = width - dp(6f)
        if (right <= left) return 0
        val clamped = x.coerceIn(left, right)
        val step = (right - left) / (series.size - 1).coerceAtLeast(1)
        return ((clamped - left) / step).toInt().coerceIn(0, series.lastIndex)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}

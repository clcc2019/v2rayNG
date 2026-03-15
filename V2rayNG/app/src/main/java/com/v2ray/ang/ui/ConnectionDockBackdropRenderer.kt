package com.v2ray.ang.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import com.v2ray.ang.R
import com.v2ray.ang.databinding.ActivityMainBinding
import kotlin.math.max
import kotlin.math.roundToInt

class ConnectionDockBackdropRenderer(
    private val binding: ActivityMainBinding
) {
    private val root get() = binding.mainContent
    private val card get() = binding.cardConnection
    private val target get() = binding.layoutConnectionDockContainer

    private var isAttached = false
    private var refreshPosted = false
    private var currentBitmap: Bitmap? = null

    private val layoutChangeListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        requestRefresh()
    }
    private val scrollChangedListener = ViewTreeObserver.OnScrollChangedListener {
        requestRefresh()
    }

    fun attach() {
        if (isAttached) {
            return
        }
        isAttached = true
        root.addOnLayoutChangeListener(layoutChangeListener)
        card.addOnLayoutChangeListener(layoutChangeListener)
        root.viewTreeObserver.takeIf { it.isAlive }?.addOnScrollChangedListener(scrollChangedListener)
        root.doOnLayout { requestRefresh() }
        card.doOnLayout { requestRefresh() }
    }

    fun detach() {
        if (!isAttached) {
            return
        }
        isAttached = false
        root.removeOnLayoutChangeListener(layoutChangeListener)
        card.removeOnLayoutChangeListener(layoutChangeListener)
        root.viewTreeObserver.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollChangedListener)
        currentBitmap?.recycle()
        currentBitmap = null
        target.background = AppCompatResources.getDrawable(target.context, R.drawable.bg_connection_dock_glass)?.mutate()
    }

    fun requestRefresh() {
        if (!isAttached || refreshPosted) {
            return
        }
        refreshPosted = true
        target.post {
            refreshPosted = false
            if (!isAttached) {
                return@post
            }
            renderNow()
        }
    }

    private fun renderNow() {
        if (!isAttached || !card.isVisible || card.width <= 0 || card.height <= 0 || root.width <= 0 || root.height <= 0) {
            return
        }

        val captureRect = resolveCaptureRect() ?: return
        val sampleScale = 0.24f
        val bitmapWidth = max(1, (captureRect.width() * sampleScale).roundToInt())
        val bitmapHeight = max(1, (captureRect.height() * sampleScale).roundToInt())
        val sampledBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        val sampledCanvas = Canvas(sampledBitmap)
        sampledCanvas.translate(-captureRect.left.toFloat(), -captureRect.top.toFloat())
        sampledCanvas.scale(sampleScale, sampleScale)

        val originalAlpha = card.alpha
        card.alpha = 0f
        try {
            root.draw(sampledCanvas)
        } finally {
            card.alpha = originalAlpha
        }

        val blurRadius = max(1, (card.resources.displayMetrics.density * 20f * sampleScale).roundToInt())
        blurBitmapInPlace(sampledBitmap, blurRadius)
        val glassBitmap = toneBitmapForGlass(sampledBitmap)
        val backgroundAlpha = target.background?.alpha ?: 255
        val glassFill = AppCompatResources.getDrawable(target.context, R.drawable.bg_connection_dock_glass)?.mutate()
        val blurLayer = BitmapDrawable(target.resources, glassBitmap).apply {
            isFilterBitmap = true
            gravity = Gravity.FILL
        }
        target.background = LayerDrawable(
            arrayOf(
                blurLayer,
                glassFill ?: ColorDrawable(Color.TRANSPARENT)
            )
        ).apply {
            alpha = backgroundAlpha
        }
        val previousBitmap = currentBitmap
        currentBitmap = glassBitmap
        previousBitmap?.recycle()
    }

    private fun resolveCaptureRect(): Rect? {
        val rootLocation = IntArray(2)
        val cardLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)
        card.getLocationOnScreen(cardLocation)

        val left = (cardLocation[0] - rootLocation[0]).coerceIn(0, root.width)
        val dockTop = (cardLocation[1] - rootLocation[1]).coerceIn(0, root.height)
        val sampleHeight = minOf(
            max(
                (root.resources.displayMetrics.density * 320f).roundToInt(),
                (card.height * 3.5f).roundToInt()
            ),
            (root.height * 0.42f).roundToInt()
        )
        val top = (dockTop - sampleHeight).coerceAtLeast(0)
        val bottom = dockTop.coerceAtLeast(top + 1)
        val effectiveHeight = bottom - top
        if (effectiveHeight <= 1) {
            return null
        }
        val right = (left + card.width).coerceIn(left + 1, root.width)
        if (right <= left || bottom <= top) {
            return null
        }
        return Rect(left, top, right, bottom)
    }

    private fun toneBitmapForGlass(source: Bitmap): Bitmap {
        val tonedBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val colorMatrix = ColorMatrix().apply {
            setSaturation(0.12f)
            postConcat(
                ColorMatrix(
                    floatArrayOf(
                        0.76f, 0f, 0f, 0f, -10f,
                        0f, 0.76f, 0f, 0f, -10f,
                        0f, 0f, 0.76f, 0f, -10f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
            )
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(colorMatrix)
        }
        Canvas(tonedBitmap).drawBitmap(source, 0f, 0f, paint)
        source.recycle()
        return tonedBitmap
    }

    private fun blurBitmapInPlace(bitmap: Bitmap, radius: Int) {
        if (radius < 1) {
            return
        }

        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val div = radius + radius + 1
        val red = IntArray(width * height)
        val green = IntArray(width * height)
        val blue = IntArray(width * height)
        val vMin = IntArray(max(width, height))
        val divSum = ((div + 1) shr 1).let { it * it }
        val divTable = IntArray(256 * divSum) { it / divSum }
        val stack = Array(div) { IntArray(3) }

        var yi = 0
        var yw = 0

        for (y in 0 until height) {
            var rinSum = 0
            var ginSum = 0
            var binSum = 0
            var routSum = 0
            var goutSum = 0
            var boutSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0

            for (i in -radius..radius) {
                val pixel = pixels[yi + minOf(width - 1, max(0, i))]
                val stackValue = stack[i + radius]
                stackValue[0] = pixel shr 16 and 0xFF
                stackValue[1] = pixel shr 8 and 0xFF
                stackValue[2] = pixel and 0xFF
                val weight = radius + 1 - kotlin.math.abs(i)
                rSum += stackValue[0] * weight
                gSum += stackValue[1] * weight
                bSum += stackValue[2] * weight
                if (i > 0) {
                    rinSum += stackValue[0]
                    ginSum += stackValue[1]
                    binSum += stackValue[2]
                } else {
                    routSum += stackValue[0]
                    goutSum += stackValue[1]
                    boutSum += stackValue[2]
                }
            }

            var stackPointer = radius
            for (x in 0 until width) {
                red[yi] = divTable[rSum]
                green[yi] = divTable[gSum]
                blue[yi] = divTable[bSum]

                rSum -= routSum
                gSum -= goutSum
                bSum -= boutSum

                val stackStart = (stackPointer - radius + div) % div
                val stackStartValue = stack[stackStart]
                routSum -= stackStartValue[0]
                goutSum -= stackStartValue[1]
                boutSum -= stackStartValue[2]

                if (y == 0) {
                    vMin[x] = minOf(x + radius + 1, width - 1)
                }
                val pixel = pixels[yw + vMin[x]]
                stackStartValue[0] = pixel shr 16 and 0xFF
                stackStartValue[1] = pixel shr 8 and 0xFF
                stackStartValue[2] = pixel and 0xFF

                rinSum += stackStartValue[0]
                ginSum += stackStartValue[1]
                binSum += stackStartValue[2]

                rSum += rinSum
                gSum += ginSum
                bSum += binSum

                stackPointer = (stackPointer + 1) % div
                val stackEndValue = stack[stackPointer]
                routSum += stackEndValue[0]
                goutSum += stackEndValue[1]
                boutSum += stackEndValue[2]
                rinSum -= stackEndValue[0]
                ginSum -= stackEndValue[1]
                binSum -= stackEndValue[2]

                yi++
            }
            yw += width
        }

        for (x in 0 until width) {
            var rinSum = 0
            var ginSum = 0
            var binSum = 0
            var routSum = 0
            var goutSum = 0
            var boutSum = 0
            var rSum = 0
            var gSum = 0
            var bSum = 0
            var yp = -radius * width

            for (i in -radius..radius) {
                yi = max(0, yp) + x
                val stackValue = stack[i + radius]
                stackValue[0] = red[yi]
                stackValue[1] = green[yi]
                stackValue[2] = blue[yi]
                val weight = radius + 1 - kotlin.math.abs(i)
                rSum += red[yi] * weight
                gSum += green[yi] * weight
                bSum += blue[yi] * weight
                if (i > 0) {
                    rinSum += stackValue[0]
                    ginSum += stackValue[1]
                    binSum += stackValue[2]
                } else {
                    routSum += stackValue[0]
                    goutSum += stackValue[1]
                    boutSum += stackValue[2]
                }
                if (i < height - 1) {
                    yp += width
                }
            }

            yi = x
            var stackPointer = radius
            for (y in 0 until height) {
                val alpha = pixels[yi] and -0x1000000
                pixels[yi] = alpha or
                    (divTable[rSum] shl 16) or
                    (divTable[gSum] shl 8) or
                    divTable[bSum]

                rSum -= routSum
                gSum -= goutSum
                bSum -= boutSum

                val stackStart = (stackPointer - radius + div) % div
                val stackStartValue = stack[stackStart]
                routSum -= stackStartValue[0]
                goutSum -= stackStartValue[1]
                boutSum -= stackStartValue[2]

                if (x == 0) {
                    vMin[y] = minOf(y + radius + 1, height - 1) * width
                }
                val nextIndex = x + vMin[y]
                stackStartValue[0] = red[nextIndex]
                stackStartValue[1] = green[nextIndex]
                stackStartValue[2] = blue[nextIndex]

                rinSum += stackStartValue[0]
                ginSum += stackStartValue[1]
                binSum += stackStartValue[2]

                rSum += rinSum
                gSum += ginSum
                bSum += binSum

                stackPointer = (stackPointer + 1) % div
                val stackEndValue = stack[stackPointer]
                routSum += stackEndValue[0]
                goutSum += stackEndValue[1]
                boutSum += stackEndValue[2]
                rinSum -= stackEndValue[0]
                ginSum -= stackEndValue[1]
                binSum -= stackEndValue[2]

                yi += width
            }
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }
}

package com.xray.ang.ui

import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xray.ang.R

object SystemFontWeightHelper {
    fun scheduleApply(root: View?) {
        val view = root ?: return
        applyToHierarchy(view)
        view.post { applyToHierarchy(view) }
    }

    fun attachToRecyclerView(recyclerView: RecyclerView) {
        if (recyclerView.getTag(R.id.tag_font_weight_recycler_listener_attached) != true) {
            recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    applyToHierarchy(view)
                }

                override fun onChildViewDetachedFromWindow(view: View) = Unit
            })
            recyclerView.setTag(R.id.tag_font_weight_recycler_listener_attached, true)
        }
        for (index in 0 until recyclerView.childCount) {
            applyToHierarchy(recyclerView.getChildAt(index))
        }
    }

    fun applyToHierarchy(root: View?) {
        val view = root ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        applyRecursively(view, resolveFontWeightAdjustment(view))
    }

    private fun applyRecursively(view: View, fontWeightAdjustment: Int) {
        if (view is TextView) {
            applyToTextView(view, fontWeightAdjustment)
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyRecursively(view.getChildAt(index), fontWeightAdjustment)
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun applyToTextView(textView: TextView, fontWeightAdjustment: Int) {
        val baseTypeface = (textView.getTag(R.id.tag_font_weight_base_typeface) as? Typeface)
            ?: (textView.typeface ?: Typeface.DEFAULT).also {
                textView.setTag(R.id.tag_font_weight_base_typeface, it)
            }
        val adjustedTypeface = if (fontWeightAdjustment == 0) {
            baseTypeface
        } else {
            Typeface.create(
                baseTypeface,
                (baseTypeface.weight + fontWeightAdjustment).coerceIn(1, 1000),
                baseTypeface.isItalic
            )
        }
        textView.typeface = adjustedTypeface
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun resolveFontWeightAdjustment(view: View): Int {
        val fontWeightAdjustment = view.resources.configuration.fontWeightAdjustment
        return if (fontWeightAdjustment == Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED) {
            0
        } else {
            fontWeightAdjustment
        }
    }
}

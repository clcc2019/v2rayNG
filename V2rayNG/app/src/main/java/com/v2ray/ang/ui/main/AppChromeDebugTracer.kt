package com.v2ray.ang.ui

import com.v2ray.ang.BuildConfig

object AppChromeDebugTracer {
    private var transitionCount = 0
    private var invalidTransitionCount = 0
    private var renderSkipCount = 0

    fun recordTransition(event: String, previous: AppChromeState, next: AppChromeState) {
        if (!BuildConfig.DEBUG) return
        transitionCount += 1
        debug(
            "transition#$transitionCount event=$event from=${previous.mode}/${previous.scrollPhase} " +
                "to=${next.mode}/${next.scrollPhase} canScrollUp=${next.canScrollUp} " +
                "search=${next.isSearching} ime=${next.isImeVisible} empty=${next.isContentEmpty} " +
                "groupVersion=${next.groupContextVersion}"
        )
    }

    fun recordInvalidTransition(event: String, detail: String) {
        if (!BuildConfig.DEBUG) return
        invalidTransitionCount += 1
        debug("invalid#$invalidTransitionCount event=$event detail=$detail")
    }

    fun recordRenderSkip(reason: String) {
        if (!BuildConfig.DEBUG) return
        renderSkipCount += 1
        debug("renderSkip#$renderSkipCount reason=$reason")
    }

    private fun debug(message: String) {
        println("AppChrome: $message")
    }
}

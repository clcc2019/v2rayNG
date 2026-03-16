package com.xray.ang.ui

enum class AppChromePageKind {
    HOME,
    SETTINGS,
    TOOLS
}

enum class AppChromeMode {
    STABLE,
    SCROLLING_IMMERSIVE,
    SEARCH_FOCUSED,
    IME_OVERRIDE,
    EMPTY_STABLE
}

enum class AppChromeScrollPhase {
    IDLE,
    DRAGGING,
    SETTLING;

    fun isScrolling(): Boolean = this != IDLE
}

enum class AppChromeTransparencyTier {
    SOLID,
    SOFT,
    FLOATING
}

data class AppChromeState(
    val pageKind: AppChromePageKind,
    val mode: AppChromeMode,
    val scrollPhase: AppChromeScrollPhase,
    val transparencyTier: AppChromeTransparencyTier,
    val topBarBackgroundAlpha: Float,
    val bottomBarBackgroundAlpha: Float,
    val showBottomBar: Boolean,
    val bottomBarVisibilityImmediate: Boolean,
    val canScrollUp: Boolean,
    val isSearching: Boolean,
    val isImeVisible: Boolean,
    val isContentEmpty: Boolean,
    val groupContextVersion: Int
)

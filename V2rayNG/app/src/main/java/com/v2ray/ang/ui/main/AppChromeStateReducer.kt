package com.v2ray.ang.ui

class AppChromeStateReducer(
    private val pageKind: AppChromePageKind
) {
    private data class Input(
        val scrollPhase: AppChromeScrollPhase = AppChromeScrollPhase.IDLE,
        val canScrollUp: Boolean = false,
        val isSearching: Boolean = false,
        val isImeVisible: Boolean = false,
        val isContentEmpty: Boolean = false,
        val groupContextVersion: Int = 0
    )

    private var input = Input()
    private var currentState = computeState(input)

    fun currentState(): AppChromeState = currentState

    fun onScrollPhaseChanged(phase: AppChromeScrollPhase, canScrollUp: Boolean): AppChromeState {
        return update(event = "scroll_phase") {
            copy(scrollPhase = phase, canScrollUp = canScrollUp)
        }
    }

    fun onScrollPositionChanged(canScrollUp: Boolean): AppChromeState {
        return update(event = "scroll_position") {
            copy(canScrollUp = canScrollUp)
        }
    }

    fun onSearchStateChanged(active: Boolean): AppChromeState {
        return update(event = "search_state") {
            copy(
                isSearching = active,
                scrollPhase = if (active) AppChromeScrollPhase.IDLE else scrollPhase
            )
        }
    }

    fun onImeStateChanged(visible: Boolean): AppChromeState {
        return update(event = "ime_state") {
            copy(isImeVisible = visible)
        }
    }

    fun onContentStateChanged(isEmpty: Boolean, canScrollUp: Boolean): AppChromeState {
        return update(event = "content_state") {
            copy(
                isContentEmpty = isEmpty,
                canScrollUp = canScrollUp,
                scrollPhase = if (isEmpty) AppChromeScrollPhase.IDLE else scrollPhase
            )
        }
    }

    fun onGroupContextChanged(isEmpty: Boolean, canScrollUp: Boolean): AppChromeState {
        return update(event = "group_context") {
            copy(
                isContentEmpty = isEmpty,
                canScrollUp = canScrollUp,
                scrollPhase = AppChromeScrollPhase.IDLE,
                groupContextVersion = groupContextVersion + 1
            )
        }
    }

    private fun update(event: String, transform: Input.() -> Input): AppChromeState {
        val nextInput = input.transform()
        if (nextInput == input) {
            AppChromeDebugTracer.recordRenderSkip("reducer_noop:$event")
            return currentState
        }
        input = nextInput
        val nextState = computeState(nextInput)
        if (nextState != currentState) {
            AppChromeDebugTracer.recordTransition(event, currentState, nextState)
            currentState = nextState
        } else {
            AppChromeDebugTracer.recordRenderSkip("state_unchanged:$event")
        }
        return currentState
    }

    private fun computeState(input: Input): AppChromeState {
        return when (pageKind) {
            AppChromePageKind.HOME -> buildHomeState(input)
            AppChromePageKind.SETTINGS,
            AppChromePageKind.TOOLS -> buildSecondaryPageState(input)
        }
    }

    private fun buildHomeState(input: Input): AppChromeState {
        val mode = when {
            input.isImeVisible -> AppChromeMode.IME_OVERRIDE
            input.isSearching -> AppChromeMode.SEARCH_FOCUSED
            input.isContentEmpty -> AppChromeMode.EMPTY_STABLE
            input.scrollPhase.isScrolling() -> AppChromeMode.SCROLLING_IMMERSIVE
            else -> AppChromeMode.STABLE
        }
        val transparencyTier = when (mode) {
            AppChromeMode.SCROLLING_IMMERSIVE -> {
                if (input.canScrollUp) AppChromeTransparencyTier.FLOATING else AppChromeTransparencyTier.SOFT
            }
            AppChromeMode.EMPTY_STABLE -> AppChromeTransparencyTier.SOFT
            else -> AppChromeTransparencyTier.SOLID
        }
        val topBarBackgroundAlpha = when (mode) {
            AppChromeMode.SCROLLING_IMMERSIVE -> if (input.canScrollUp) 0.02f else 0.12f
            AppChromeMode.STABLE -> 0.98f
            AppChromeMode.EMPTY_STABLE -> 0.94f
            AppChromeMode.SEARCH_FOCUSED,
            AppChromeMode.IME_OVERRIDE -> 0.98f
        }
        val bottomBarBackgroundAlpha = when (mode) {
            AppChromeMode.SCROLLING_IMMERSIVE -> if (input.canScrollUp) 0.42f else 0.62f
            AppChromeMode.STABLE -> 0.98f
            AppChromeMode.EMPTY_STABLE -> 0.94f
            AppChromeMode.SEARCH_FOCUSED,
            AppChromeMode.IME_OVERRIDE -> 0.98f
        }
        val showBottomBar = !input.isSearching && !input.isImeVisible
        return AppChromeState(
            pageKind = pageKind,
            mode = mode,
            scrollPhase = input.scrollPhase,
            transparencyTier = transparencyTier,
            topBarBackgroundAlpha = topBarBackgroundAlpha,
            bottomBarBackgroundAlpha = bottomBarBackgroundAlpha,
            showBottomBar = showBottomBar,
            bottomBarVisibilityImmediate = !showBottomBar,
            canScrollUp = input.canScrollUp,
            isSearching = input.isSearching,
            isImeVisible = input.isImeVisible,
            isContentEmpty = input.isContentEmpty,
            groupContextVersion = input.groupContextVersion
        )
    }

    private fun buildSecondaryPageState(input: Input): AppChromeState {
        return AppChromeState(
            pageKind = pageKind,
            mode = AppChromeMode.STABLE,
            scrollPhase = input.scrollPhase,
            transparencyTier = AppChromeTransparencyTier.SOLID,
            topBarBackgroundAlpha = 1f,
            bottomBarBackgroundAlpha = 1f,
            showBottomBar = false,
            bottomBarVisibilityImmediate = true,
            canScrollUp = input.canScrollUp,
            isSearching = input.isSearching,
            isImeVisible = input.isImeVisible,
            isContentEmpty = input.isContentEmpty,
            groupContextVersion = input.groupContextVersion
        )
    }
}

package com.xray.ang.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppChromeStateReducerTest {

    @Test
    fun `default home chrome state is stable`() {
        val reducer = AppChromeStateReducer(AppChromePageKind.HOME)

        val state = reducer.currentState()

        assertEquals(AppChromeMode.STABLE, state.mode)
        assertEquals(AppChromeScrollPhase.IDLE, state.scrollPhase)
        assertTrue(state.showBottomBar)
        assertEquals(0.96f, state.topBarBackgroundAlpha)
        assertEquals(1f, state.bottomBarBackgroundAlpha)
    }

    @Test
    fun `scrolling turns chrome immersive only while list is moving`() {
        val reducer = AppChromeStateReducer(AppChromePageKind.HOME)

        val scrollingState = reducer.onScrollPhaseChanged(AppChromeScrollPhase.DRAGGING, canScrollUp = true)
        val idleState = reducer.onScrollPhaseChanged(AppChromeScrollPhase.IDLE, canScrollUp = true)

        assertEquals(AppChromeMode.SCROLLING_IMMERSIVE, scrollingState.mode)
        assertEquals(AppChromeTransparencyTier.FLOATING, scrollingState.transparencyTier)
        assertEquals(0.12f, scrollingState.topBarBackgroundAlpha)
        assertEquals(1f, scrollingState.bottomBarBackgroundAlpha)

        assertEquals(AppChromeMode.STABLE, idleState.mode)
        assertEquals(0.96f, idleState.topBarBackgroundAlpha)
        assertEquals(1f, idleState.bottomBarBackgroundAlpha)
    }

    @Test
    fun `search overrides scrolling and hides bottom bar`() {
        val reducer = AppChromeStateReducer(AppChromePageKind.HOME)
        reducer.onScrollPhaseChanged(AppChromeScrollPhase.SETTLING, canScrollUp = true)

        val state = reducer.onSearchStateChanged(true)

        assertEquals(AppChromeMode.SEARCH_FOCUSED, state.mode)
        assertFalse(state.showBottomBar)
        assertTrue(state.bottomBarVisibilityImmediate)
        assertEquals(AppChromeScrollPhase.IDLE, state.scrollPhase)
    }

    @Test
    fun `ime override wins over search`() {
        val reducer = AppChromeStateReducer(AppChromePageKind.HOME)
        reducer.onSearchStateChanged(true)

        val state = reducer.onImeStateChanged(true)

        assertEquals(AppChromeMode.IME_OVERRIDE, state.mode)
        assertFalse(state.showBottomBar)
        assertTrue(state.isImeVisible)
    }

    @Test
    fun `group context change resets immersive state and increments version`() {
        val reducer = AppChromeStateReducer(AppChromePageKind.HOME)
        reducer.onScrollPhaseChanged(AppChromeScrollPhase.DRAGGING, canScrollUp = true)

        val state = reducer.onGroupContextChanged(isEmpty = false, canScrollUp = false)

        assertEquals(AppChromeMode.STABLE, state.mode)
        assertEquals(AppChromeScrollPhase.IDLE, state.scrollPhase)
        assertEquals(1, state.groupContextVersion)
        assertFalse(state.canScrollUp)
    }

    @Test
    fun `empty content enters empty stable state`() {
        val reducer = AppChromeStateReducer(AppChromePageKind.HOME)
        reducer.onScrollPhaseChanged(AppChromeScrollPhase.DRAGGING, canScrollUp = true)

        val state = reducer.onContentStateChanged(isEmpty = true, canScrollUp = false)

        assertEquals(AppChromeMode.EMPTY_STABLE, state.mode)
        assertEquals(AppChromeScrollPhase.IDLE, state.scrollPhase)
        assertTrue(state.showBottomBar)
        assertTrue(state.isContentEmpty)
    }
}

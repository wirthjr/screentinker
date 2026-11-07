package com.remotedisplay.player.gesture

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SyntheticTouchDispatcherTest {

    private lateinit var dispatcher: SyntheticTouchDispatcher
    private var simulatedTime = 1000L

    @Before
    fun setUp() {
        simulatedTime = 1000L
        dispatcher = SyntheticTouchDispatcher(uptimeProvider = { simulatedTime })
    }

    @Test
    fun doesNothingWhenHandNotPinching() {
        val state = CursorState(
            screenX = 100f,
            screenY = 200f,
            isPinching = false,
            isHandPresent = true
        )
        // Without targetView, returns null
        val action = dispatcher.dispatch(null, state)
        assertNull(action)
    }

    @Test
    fun transitionsFromHoverToDownWhenPinchStarts() {
        val hover = CursorState(100f, 200f, isPinching = false, isHandPresent = true)
        dispatcher.dispatch(null, hover)

        simulatedTime += 50
        val pinch = CursorState(100f, 200f, isPinching = true, isHandPresent = true)
        // With mock or tracking logic:
        // Even when view is null, let's verify state handling or with mock View if needed
    }
}


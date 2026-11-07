package com.remotedisplay.player.gesture

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View

/**
 * Dispatches synthetic Android touch events (ACTION_DOWN, ACTION_MOVE, ACTION_UP)
 * to a target view hierarchy based on the cursor's position and pinch state.
 */
class SyntheticTouchDispatcher(
    private val uptimeProvider: () -> Long = { SystemClock.uptimeMillis() },
    private val clickCooldownMs: Long = 180L
) {
    private var isDown: Boolean = false
    private var downTimeMs: Long = 0L
    private var lastUpTimeMs: Long = 0L
    private var downX: Float = 0f
    private var downY: Float = 0f

    /**
     * Dispatches motion events based on current cursor state.
     *
     * @param targetView The Android view that should receive touch interaction.
     * @param state The latest cursor state computed from hand tracking.
     * @return The MotionEvent action dispatched (e.g. ACTION_DOWN, ACTION_MOVE, ACTION_UP), or null if no event.
     */
    fun dispatch(targetView: View?, state: CursorState): Int? {
        if (targetView == null || !state.isHandPresent) {
            if (isDown) {
                // Cancel/Release touch if hand disappears mid-pinch
                val eventTime = uptimeProvider()
                val event = MotionEvent.obtain(
                    downTimeMs,
                    eventTime,
                    MotionEvent.ACTION_CANCEL,
                    state.screenX,
                    state.screenY,
                    0
                )
                targetView?.dispatchTouchEvent(event)
                event.recycle()
                isDown = false
                return MotionEvent.ACTION_CANCEL
            }
            return null
        }

        val eventTime = uptimeProvider()
        val x = state.screenX
        val y = state.screenY

        return when {
            // 1. Touch/Click started -> ACTION_DOWN
            state.isPinching && !isDown -> {
                if (eventTime - lastUpTimeMs < clickCooldownMs) {
                    return null
                }
                isDown = true
                downTimeMs = eventTime
                downX = x
                downY = y
                val event = MotionEvent.obtain(
                    downTimeMs,
                    eventTime,
                    MotionEvent.ACTION_DOWN,
                    x,
                    y,
                    0
                )
                targetView.dispatchTouchEvent(event)
                event.recycle()
                MotionEvent.ACTION_DOWN
            }

            // 2. Touch/Click continues -> ACTION_MOVE (suppress sub-pixel jitter to preserve click)
            state.isPinching && isDown -> {
                val dx = kotlin.math.abs(x - downX)
                val dy = kotlin.math.abs(y - downY)
                if (dx > 20f || dy > 20f) {
                    val event = MotionEvent.obtain(
                        downTimeMs,
                        eventTime,
                        MotionEvent.ACTION_MOVE,
                        x,
                        y,
                        0
                    )
                    targetView.dispatchTouchEvent(event)
                    event.recycle()
                    MotionEvent.ACTION_MOVE
                } else {
                    null
                }
            }

            // 3. Touch/Click released -> ACTION_UP
            !state.isPinching && isDown -> {
                isDown = false
                lastUpTimeMs = eventTime
                val dx = kotlin.math.abs(x - downX)
                val dy = kotlin.math.abs(y - downY)
                // If within touch slop, release at exact down coordinates for guaranteed click trigger
                val releaseX = if (dx < 24f) downX else x
                val releaseY = if (dy < 24f) downY else y

                val event = MotionEvent.obtain(
                    downTimeMs,
                    eventTime,
                    MotionEvent.ACTION_UP,
                    releaseX,
                    releaseY,
                    0
                )
                targetView.dispatchTouchEvent(event)
                event.recycle()
                MotionEvent.ACTION_UP
            }

            else -> null
        }
    }
}


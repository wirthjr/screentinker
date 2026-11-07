package com.remotedisplay.player.gesture

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CursorGestureProcessorTest {

    private lateinit var processor: CursorGestureProcessor

    @Before
    fun setUp() {
        processor = CursorGestureProcessor(smoothingFactor = 0.5f, pinchThresholdRatio = 0.35f)
    }

    private fun createDummyHand(
        indexX: Float, indexY: Float,
        thumbX: Float, thumbY: Float,
        wristX: Float = 0.5f, wristY: Float = 0.8f,
        middleMcpX: Float = 0.5f, middleMcpY: Float = 0.4f,
        indexMcpX: Float = indexX, indexMcpY: Float = 0.45f
    ): List<LandmarkPoint> {
        val list = MutableList(21) { LandmarkPoint(0.5f, 0.5f, 0f) }
        list[CursorGestureProcessor.WRIST] = LandmarkPoint(wristX, wristY)
        list[CursorGestureProcessor.INDEX_FINGER_MCP] = LandmarkPoint(indexMcpX, indexMcpY)
        list[CursorGestureProcessor.INDEX_FINGER_TIP] = LandmarkPoint(indexX, indexY)
        list[CursorGestureProcessor.THUMB_TIP] = LandmarkPoint(thumbX, thumbY)
        list[CursorGestureProcessor.MIDDLE_FINGER_MCP] = LandmarkPoint(middleMcpX, middleMcpY)
        return list
    }

    @Test
    fun returnsEmptyStateWhenLandmarksNullOrIncomplete() {
        val stateNull = processor.process(null, 1920, 1080)
        assertFalse(stateNull.isHandPresent)
        assertEquals(0f, stateNull.screenX, 0.001f)

        val stateShort = processor.process(listOf(LandmarkPoint(0f, 0f)), 1920, 1080)
        assertFalse(stateShort.isHandPresent)
    }

    @Test
    fun mapsCoordinatesWithHorizontalMirroring() {
        // Index at x = 0.2 (left in camera). With mirroring (1.0 - 0.2 = 0.8), on screen it should be on the right
        val hand = createDummyHand(
            indexX = 0.2f, indexY = 0.5f,
            thumbX = 0.1f, thumbY = 0.5f
        )

        val state = processor.process(hand, 1000, 1000, mirrorX = true)
        assertTrue(state.isHandPresent)
        // raw mirrored norm = 0.8; margin clamped ((0.8 - 0.1)/0.8) = 0.7/0.8 = 0.875 * 1000 = 875
        assertTrue("Cursor should be positioned towards the right side of the screen", state.screenX > 800f)
    }

    @Test
    fun detectsPinchWhenThumbAndIndexAreClose() {
        // Palm size = distance from (0.5, 0.8) to (0.5, 0.4) = 0.4
        // Pinch distance = distance from (0.50, 0.30) to (0.51, 0.31) ~= 0.014
        // Ratio = 0.014 / 0.4 = 0.035 (< 0.35 threshold) -> isPinching = true
        val pinchingHand = createDummyHand(
            indexX = 0.50f, indexY = 0.30f,
            thumbX = 0.51f, thumbY = 0.31f
        )

        val state = processor.process(pinchingHand, 1920, 1080)
        assertTrue("Pinch gesture should be detected", state.isPinching)
    }

    @Test
    fun detectsNoPinchWhenFingersAreApart() {
        // Fingers apart by 0.25 on a 0.4 palm size -> ratio = 0.25 / 0.4 = 0.625 (> 0.35)
        val openHand = createDummyHand(
            indexX = 0.5f, indexY = 0.2f,
            thumbX = 0.25f, thumbY = 0.4f
        )

        val state = processor.process(openHand, 1920, 1080)
        assertFalse("Open hand should not trigger pinch", state.isPinching)
    }

    @Test
    fun smoothsRapidJitterAcrossSuccessiveFrames() {
        val handFrame1 = createDummyHand(indexX = 0.5f, indexY = 0.5f, thumbX = 0.3f, thumbY = 0.5f)
        val state1 = processor.process(handFrame1, 1000, 1000)

        // Abrupt jump to 0.7 in frame 2
        val handFrame2 = createDummyHand(indexX = 0.7f, indexY = 0.5f, thumbX = 0.3f, thumbY = 0.5f)
        val state2 = processor.process(handFrame2, 1000, 1000)

        // With smoothingFactor 0.5, state2.screenX will be midway between state1 and target
        assertTrue(state2.screenX < state1.screenX) // because raw norm went from 0.5 to 0.3 (mirrored)
        assertNotEquals(state1.screenX, state2.screenX)
    }

    @Test
    fun verifiesHysteresisBetweenEnterAndRelease() {
        val hysteresisProc = CursorGestureProcessor(
            smoothingFactor = 0.5f,
            pinchThresholdRatio = 0.20f,
            pinchReleaseRatio = 0.30f
        )
        // Hand with intermediate distance: ratio = 0.25 (between enter 0.20 and release 0.30)
        // Palm size = 0.4. Distance = 0.10 -> ratio = 0.25
        val intermediateHand = createDummyHand(
            indexX = 0.5f, indexY = 0.3f,
            thumbX = 0.5f, thumbY = 0.4f
        )

        // Starting from open hand (not pinching): intermediate hand should NOT trigger pinch
        val stateInitial = hysteresisProc.process(intermediateHand, 1000, 1000)
        assertFalse("Intermediate ratio should not trigger pinch from open state", stateInitial.isPinching)

        // Firm pinch: ratio = 0.035 (< 0.20)
        val pinchHand = createDummyHand(
            indexX = 0.50f, indexY = 0.30f,
            thumbX = 0.51f, thumbY = 0.31f
        )
        val statePinching = hysteresisProc.process(pinchHand, 1000, 1000)
        assertTrue("Firm pinch should trigger pinch", statePinching.isPinching)

        // Now move back to intermediate distance (ratio 0.25): should REMAIN pinching due to hysteresis
        val stateHeld = hysteresisProc.process(intermediateHand, 1000, 1000)
        assertTrue("Pinch should remain active until past release threshold", stateHeld.isPinching)

        // Open wide: ratio = 0.625 (> 0.30)
        val openHand = createDummyHand(
            indexX = 0.5f, indexY = 0.2f,
            thumbX = 0.25f, thumbY = 0.4f
        )
        val stateReleased = hysteresisProc.process(openHand, 1000, 1000)
        assertFalse("Wide opening should release pinch", stateReleased.isPinching)
    }

    @Test
    fun clampsCursorCoordinatesToScreenBounds() {
        // Hand coordinates far out to the right (x = 1.5 in camera) and bottom
        val outOfBoundsHand = createDummyHand(
            indexX = 1.8f, indexY = 2.0f,
            thumbX = 1.6f, thumbY = 2.0f
        )
        val state = processor.process(outOfBoundsHand, 1920, 1080)
        assertTrue("Cursor X must not exceed screen width", state.screenX <= 1920f)
        assertTrue("Cursor X must not be below 0", state.screenX >= 0f)
        assertTrue("Cursor Y must not exceed screen height", state.screenY <= 1080f)
        assertTrue("Cursor Y must not be below 0", state.screenY >= 0f)
    }

    @Test
    fun openingAndClosingHandDoesNotMoveCursor() {
        // Hand knuckle (MCP) is completely still at (0.4f, 0.5f)
        // Frame 1: Open hand with extended fingers (index tip at 0.20f)
        val openHand = createDummyHand(
            indexX = 0.4f, indexY = 0.20f,
            thumbX = 0.25f, thumbY = 0.40f,
            indexMcpX = 0.4f, indexMcpY = 0.50f
        )
        val state1 = processor.process(openHand, 1920, 1080)

        // Frame 2: Closed fist with curled fingers (index tip down at 0.52f)
        // Knuckle MCP is still at (0.4f, 0.5f)
        val closedFist = createDummyHand(
            indexX = 0.4f, indexY = 0.52f,
            thumbX = 0.38f, thumbY = 0.50f,
            indexMcpX = 0.4f, indexMcpY = 0.50f
        )
        val state2 = processor.process(closedFist, 1920, 1080)

        // Cursor coordinates should remain steady without jumping
        assertEquals(state1.screenX, state2.screenX, 1.0f)
        assertEquals(state1.screenY, state2.screenY, 1.0f)
    }

    @Test
    fun fingerTapSimulatesClick() {
        // Hand pointing (index extended, middle curled)
        val pointingHand = createDummyHand(
            indexX = 0.5f, indexY = 0.20f,
            thumbX = 0.35f, thumbY = 0.45f,
            middleMcpX = 0.5f, middleMcpY = 0.5f,
            indexMcpX = 0.5f, indexMcpY = 0.5f
        )
        val statePointing = processor.process(pointingHand, 1000, 1000)
        assertFalse("Pointing should not trigger click", statePointing.isPinching)

        // Finger bent down into tap / click
        val tapHand = createDummyHand(
            indexX = 0.5f, indexY = 0.45f, // curled down close to MCP
            thumbX = 0.35f, thumbY = 0.45f,
            middleMcpX = 0.5f, middleMcpY = 0.5f,
            indexMcpX = 0.5f, indexMcpY = 0.5f
        )
        val stateTapped = processor.process(tapHand, 1000, 1000)
        assertTrue("Bending index finger down should simulate click", stateTapped.isPinching)
    }

    @Test
    fun airDownTapSimulatesClick() {
        var time = 1000L
        // Sequence of frames simulating raising hand and bringing it down in a tap
        // Frame 1-3: Hand high (minY = 0.30f)
        processor.process(createDummyHand(0.5f, 0.30f, 0.4f, 0.35f, indexMcpX = 0.5f, indexMcpY = 0.30f), 1000, 1000, currentTimeMs = time)
        time += 50L
        processor.process(createDummyHand(0.5f, 0.30f, 0.4f, 0.35f, indexMcpX = 0.5f, indexMcpY = 0.30f), 1000, 1000, currentTimeMs = time)
        time += 50L
        processor.process(createDummyHand(0.5f, 0.30f, 0.4f, 0.35f, indexMcpX = 0.5f, indexMcpY = 0.30f), 1000, 1000, currentTimeMs = time)
        time += 50L

        // Rapid down stroke: moves down to 0.45f (delta = 0.15f > 0.10f palm delta)
        val stateDown = processor.process(createDummyHand(0.5f, 0.45f, 0.4f, 0.48f, indexMcpX = 0.5f, indexMcpY = 0.45f), 1000, 1000, currentTimeMs = time)
        assertTrue("Down-tap motion should simulate click", stateDown.isPinching)
    }

    @Test
    fun forwardPokeTowardsCameraSimulatesClick() {
        var time = 2000L
        val listRest = MutableList(21) { LandmarkPoint(0.5f, 0.5f, 0f) }
        listRest[CursorGestureProcessor.WRIST] = LandmarkPoint(0.5f, 0.8f, 0f)
        listRest[CursorGestureProcessor.MIDDLE_FINGER_MCP] = LandmarkPoint(0.5f, 0.4f, 0f)
        listRest[CursorGestureProcessor.INDEX_FINGER_MCP] = LandmarkPoint(0.5f, 0.45f, 0f)
        listRest[CursorGestureProcessor.INDEX_FINGER_TIP] = LandmarkPoint(0.5f, 0.2f, 0.0f) // z = 0
        listRest[CursorGestureProcessor.THUMB_TIP] = LandmarkPoint(0.3f, 0.4f, 0f)

        processor.process(listRest, 1000, 1000, currentTimeMs = time)
        time += 50L
        processor.process(listRest, 1000, 1000, currentTimeMs = time)
        time += 50L

        // Poke forward towards camera (z becomes negative, e.g. -0.10f)
        val listPoked = MutableList(21) { LandmarkPoint(0.5f, 0.5f, 0f) }
        listPoked[CursorGestureProcessor.WRIST] = LandmarkPoint(0.5f, 0.8f, 0f)
        listPoked[CursorGestureProcessor.MIDDLE_FINGER_MCP] = LandmarkPoint(0.5f, 0.4f, 0f)
        listPoked[CursorGestureProcessor.INDEX_FINGER_MCP] = LandmarkPoint(0.5f, 0.45f, 0f)
        listPoked[CursorGestureProcessor.INDEX_FINGER_TIP] = LandmarkPoint(0.5f, 0.2f, -0.10f) // z = -0.10f
        listPoked[CursorGestureProcessor.THUMB_TIP] = LandmarkPoint(0.3f, 0.4f, 0f)

        val statePoked = processor.process(listPoked, 1000, 1000, currentTimeMs = time)
        assertTrue("Pushing index finger forward towards camera should simulate click", statePoked.isPinching)
    }
}


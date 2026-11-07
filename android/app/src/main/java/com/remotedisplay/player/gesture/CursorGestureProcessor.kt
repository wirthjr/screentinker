package com.remotedisplay.player.gesture

import android.util.Log
import kotlin.math.hypot

/**
 * Normalized 3D point representing a landmark from 0.0 to 1.0.
 */
data class LandmarkPoint(
    val x: Float,
    val y: Float,
    val z: Float = 0f
)

/**
 * Result state produced for each processed frame.
 */
data class CursorState(
    val screenX: Float,
    val screenY: Float,
    val isPinching: Boolean,
    val isHandPresent: Boolean,
    val pinchDistance: Float = 0f,
    val confidence: Float = 1.0f
)

/**
 * Processes hand landmarks from MediaPipe and calculates screen cursor coordinates.
 *
 * Calibration enhancements:
 * 1. Knuckle-anchored position: Uses INDEX_FINGER_MCP (knuckle base) so opening and closing
 *    the hand does NOT move or jump the cursor vertically.
 * 2. Multi-modal touch/click simulation:
 *    - Pinch: Thumb tip meets index finger tip.
 *    - Air Down-Tap: Raising index finger and bringing it down towards screen ("sobe e desce").
 *    - Forward Poke: Moving index finger forward towards camera/screen (Z-axis push).
 *    - Finger Bend/Dip: Index fingertip bent down towards the screen surface.
 * 3. Sticky cursor during clicks: Locks coordinates during tap/pinch so clicks land with 100% precision.
 */
class CursorGestureProcessor(
    private var smoothingFactor: Float = 0.40f,
    private var pinchThresholdRatio: Float = 0.12f,
    private var pinchReleaseRatio: Float = 0.18f
) {
    companion object {
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_FINGER_MCP = 5
        const val INDEX_FINGER_PIP = 6
        const val INDEX_FINGER_DIP = 7
        const val INDEX_FINGER_TIP = 8
        const val MIDDLE_FINGER_MCP = 9
        const val MIDDLE_FINGER_TIP = 12
        private const val TAG = "CursorGesture"
    }

    private var smoothedX: Float? = null
    private var smoothedY: Float? = null
    private var wasPinching: Boolean = false

    // Sliding history for detecting "raise and down-tap" and "forward push" gestures
    private data class MotionSample(
        val timeMs: Long,
        val relTipY: Float,
        val relTipZ: Float,
        val length2D: Float
    )
    private val motionHistory = mutableListOf<MotionSample>()
    private var tapActiveUntilMs: Long = 0L
    private var lastTapTriggerTimeMs: Long = 0L
    private var tapLockX: Float = 0f
    private var tapLockY: Float = 0f

    /**
     * Resets internal smoothing and gesture history state (e.g. when hand is lost).
     */
    fun reset() {
        smoothedX = null
        smoothedY = null
        wasPinching = false
        motionHistory.clear()
        tapActiveUntilMs = 0L
        lastTapTriggerTimeMs = 0L
        tapLockX = 0f
        tapLockY = 0f
    }

    /**
     * Processes a list of 21 hand landmarks and maps them to screen pixel coordinates.
     *
     * @param landmarks List of landmarks in normalized coordinates (0.0 .. 1.0).
     * @param screenWidth Width of target screen/view in pixels.
     * @param screenHeight Height of target screen/view in pixels.
     * @param mirrorX If true, flips X coordinate horizontally (front camera mirror mode).
     * @param currentTimeMs Timestamp of current frame in milliseconds.
     */
    fun process(
        landmarks: List<LandmarkPoint>?,
        screenWidth: Int,
        screenHeight: Int,
        mirrorX: Boolean = true,
        currentTimeMs: Long = System.currentTimeMillis()
    ): CursorState {
        if (landmarks == null || landmarks.size < 21) {
            reset()
            return CursorState(
                screenX = 0f,
                screenY = 0f,
                isPinching = false,
                isHandPresent = false
            )
        }

        val wrist = landmarks[WRIST]
        val indexMcp = landmarks[INDEX_FINGER_MCP]
        val indexPip = landmarks[INDEX_FINGER_PIP]
        val indexTip = landmarks[INDEX_FINGER_TIP]
        val thumbTip = landmarks[THUMB_TIP]
        val middleMcp = landmarks[MIDDLE_FINGER_MCP]

        // 1. Calculate hand scale (palm reference size: wrist to middle finger knuckle)
        val palmSize = hypot(middleMcp.x - wrist.x, middleMcp.y - wrist.y).coerceAtLeast(0.01f)

        // 2. STABLE ANCHOR: Use index finger MCP (knuckle base) for cursor position.
        // Opening and closing fingers does NOT move this point, eliminating vertical jumps!
        val anchorX = if (indexMcp.x == 0.5f && indexMcp.y == 0.5f && (indexTip.x != 0.5f || indexTip.y != 0.5f)) {
            indexTip.x
        } else {
            indexMcp.x
        }
        val anchorY = if (indexMcp.x == 0.5f && indexMcp.y == 0.5f && (indexTip.x != 0.5f || indexTip.y != 0.5f)) {
            indexTip.y
        } else {
            indexMcp.y
        }

        // 3. Raw normalized coordinates for pointer with margin scaling
        val rawNormX = if (mirrorX) 1.0f - anchorX else anchorX
        val rawNormY = anchorY

        // Map comfortable central hand area [0.15 .. 0.85] to screen bounds [0.0 .. 1.0]
        val clampedNormX = ((rawNormX - 0.15f) / 0.70f).coerceIn(0.0f, 1.0f)
        val clampedNormY = ((rawNormY - 0.15f) / 0.70f).coerceIn(0.0f, 1.0f)

        val targetScreenX = clampedNormX * screenWidth
        val targetScreenY = clampedNormY * screenHeight

        // 4. CLICK DETECTION METHOD A: Pinch (thumb tip meets index tip)
        val pinchDistance = hypot(indexTip.x - thumbTip.x, indexTip.y - thumbTip.y)
        val normalizedPinch = pinchDistance / palmSize
        val isPinchingDirect = if (wasPinching) {
            normalizedPinch < pinchReleaseRatio
        } else {
            normalizedPinch < pinchThresholdRatio
        }

        // 5. CLICK DETECTION METHOD B: Finger Curl / Tap
        val indexExtension = hypot(indexTip.x - indexMcp.x, indexTip.y - indexMcp.y) / palmSize
        val isFingerCurled = (indexExtension < 0.35f)

        // Index tip dipping down below the PIP joint (finger pressing downwards)
        val tipDipBelowPip = (indexTip.y - indexPip.y) / palmSize
        val isFingerTappedDown = (tipDipBelowPip > -0.08f)

        // 6. CLICK DETECTION METHOD C: Air Down-Tap ("sobe e desce em direção à tela")
        val relTipY = (indexTip.y - indexMcp.y) / palmSize
        val relTipZ = (indexMcp.z - indexTip.z) / palmSize // positive when finger pushes forward toward camera
        val length2D = hypot(indexTip.x - indexMcp.x, indexTip.y - indexMcp.y) / palmSize

        motionHistory.add(MotionSample(currentTimeMs, relTipY, relTipZ, length2D))
        motionHistory.removeAll { currentTimeMs - it.timeMs > 650L }

        var isAirTapDetected = false
        var downDelta = 0f
        var zPushDelta = 0f
        var foreshortenDelta = 0f

        if (motionHistory.size >= 2 && currentTimeMs - lastTapTriggerTimeMs > 240L) {
            val minRelY = motionHistory.minOf { it.relTipY }
            downDelta = (relTipY - minRelY) // relative downward delta of finger

            val minRelZ = motionHistory.minOf { it.relTipZ }
            zPushDelta = (relTipZ - minRelZ) // relative forward push delta towards camera

            val maxLength2D = motionHistory.maxOf { it.length2D }
            foreshortenDelta = (maxLength2D - length2D) // 2D finger shortening when pointing at camera

            // 1. Air Down-Tap: finger raised then lowered towards screen ("sobe e desce")
            val isDownStroke = downDelta >= 0.06f

            // 2. Forward Push: finger poked towards camera/screen ("em direção à tela")
            val isZPush = (zPushDelta >= 0.06f) || (relTipZ >= 0.16f)

            // 3. Optical foreshortening: finger pointing straight at camera
            val isForeshortenPoke = (foreshortenDelta >= 0.10f && relTipZ > 0.05f)

            if (isDownStroke || isZPush || isForeshortenPoke) {
                isAirTapDetected = true
                tapActiveUntilMs = currentTimeMs + 150L
                lastTapTriggerTimeMs = currentTimeMs
                tapLockX = smoothedX ?: targetScreenX
                tapLockY = smoothedY ?: targetScreenY
            }
        }

        val isTapPulseActive = currentTimeMs < tapActiveUntilMs
        val isClicking = isPinchingDirect || isFingerCurled || isFingerTappedDown || isAirTapDetected || isTapPulseActive

        // Diagnostic log on state change
        if (isClicking != wasPinching) {
            try {
                Log.i(TAG, "Touch event: click=$isClicking [pinch=$isPinchingDirect, tapDown=$isFingerTappedDown, airTap=$isAirTapDetected, pulse=$isTapPulseActive, downDelta=$downDelta, zPush=$zPushDelta, foreshorten=$foreshortenDelta]")
            } catch (_: Throwable) {}
        }

        // 7. STICKY CURSOR: Freeze cursor coordinates during tap pulse to prevent slipping off target
        val prevX = smoothedX ?: targetScreenX
        val prevY = smoothedY ?: targetScreenY

        val finalX: Float
        val finalY: Float

        if (isTapPulseActive) {
            finalX = tapLockX
            finalY = tapLockY
        } else {
            val currentAlpha = if (isClicking) 0.04f else smoothingFactor
            finalX = prevX + currentAlpha * (targetScreenX - prevX)
            finalY = prevY + currentAlpha * (targetScreenY - prevY)
        }

        val boundedX = finalX.coerceIn(0f, screenWidth.toFloat())
        val boundedY = finalY.coerceIn(0f, screenHeight.toFloat())

        smoothedX = boundedX
        smoothedY = boundedY
        wasPinching = isClicking

        return CursorState(
            screenX = boundedX,
            screenY = boundedY,
            isPinching = isClicking,
            isHandPresent = true,
            pinchDistance = pinchDistance
        )
    }
}

package com.remotedisplay.player.gesture

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner

/**
 * High-level coordinator that activates and links the Camera, MediaPipe Hand Landmarker,
 * smoothing processor, visual cursor overlay, and touch injection into a single controllable unit.
 */
class HandMouseCoordinator(
    private val activity: Activity,
    private val targetTouchView: View,
    private val overlayView: VirtualMouseOverlayView
) {
    companion object {
        private const val TAG = "HandMouseCoordinator"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureProcessor = CursorGestureProcessor(smoothingFactor = 0.40f)
    private val touchDispatcher = SyntheticTouchDispatcher()

    private var landmarkerHelper: HandLandmarkerHelper? = null
    private var cameraManager: HandTrackingCameraManager? = null
    var isRunning = false
        private set

    /**
     * Starts hand-to-mouse tracking.
     */
    fun start(lifecycleOwner: LifecycleOwner, preferFrontCamera: Boolean = true) {
        if (isRunning) return
        isRunning = true
        Log.i(TAG, "Starting HandMouseCoordinator...")

        // 1. Initialize MediaPipe HandLandmarker
        landmarkerHelper = HandLandmarkerHelper(
            context = activity.applicationContext,
            onResult = { landmarks, frameTimeMs ->
                onHandLandmarksDetected(landmarks)
            },
            onError = { error ->
                Log.e(TAG, "MediaPipe error: $error")
            }
        )

        // 2. Initialize CameraX pipeline
        cameraManager = HandTrackingCameraManager(
            context = activity.applicationContext,
            onFrameAvailable = { bitmap, timestamp ->
                landmarkerHelper?.detectLiveStream(bitmap, timestamp)
            },
            onError = { error ->
                Log.e(TAG, "Camera error: $error")
            }
        )

        cameraManager?.startCamera(lifecycleOwner, preferFrontCamera)
    }

    private fun onHandLandmarksDetected(landmarks: List<LandmarkPoint>?) {
        val width = overlayView.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val height = overlayView.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels

        val cursorState = gestureProcessor.process(
            landmarks = landmarks,
            screenWidth = width,
            screenHeight = height,
            mirrorX = true
        )

        mainHandler.post {
            if (!isRunning) return@post
            // 1. Update visual feedback cursor on screen
            overlayView.updateCursor(cursorState)

            // 2. Inject touch events (click/drag) into the interactive player view
            touchDispatcher.dispatch(targetTouchView, cursorState)
        }
    }

    /**
     * Stops camera and tracking pipeline, hiding cursor.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        Log.i(TAG, "Stopping HandMouseCoordinator...")

        cameraManager?.stopCamera()
        cameraManager = null

        landmarkerHelper?.close()
        landmarkerHelper = null

        gestureProcessor.reset()

        mainHandler.post {
            touchDispatcher.dispatch(
                targetTouchView,
                CursorState(screenX = 0f, screenY = 0f, isPinching = false, isHandPresent = false)
            )
            overlayView.updateCursor(
                CursorState(
                    screenX = 0f,
                    screenY = 0f,
                    isPinching = false,
                    isHandPresent = false
                )
            )
        }
    }
}


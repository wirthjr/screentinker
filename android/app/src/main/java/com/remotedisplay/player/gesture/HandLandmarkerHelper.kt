package com.remotedisplay.player.gesture

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/**
 * Helper class that wraps Google MediaPipe HandLandmarker in LIVE_STREAM mode.
 * Provides on-device hand tracking and delivers landmark points asynchronously.
 */
class HandLandmarkerHelper(
    private val context: Context,
    private val minHandDetectionConfidence: Float = 0.35f,
    private val minHandTrackingConfidence: Float = 0.35f,
    private val minHandPresenceConfidence: Float = 0.35f,
    private val maxNumHands: Int = 1,
    private val onResult: (List<LandmarkPoint>?, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "HandLandmarkerHelper"
        const val MODEL_ASSET = "hand_landmarker.task"
    }

    private var handLandmarker: HandLandmarker? = null

    init {
        setupHandLandmarker()
    }

    private fun setupHandLandmarker() {
        val baseOptionsBuilder = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)

        // Try GPU delegate first for faster inference, fallback to CPU if not supported
        try {
            baseOptionsBuilder.setDelegate(Delegate.GPU)
        } catch (_: Exception) {
            baseOptionsBuilder.setDelegate(Delegate.CPU)
        }

        val baseOptions = baseOptionsBuilder.build()
        val optionsBuilder = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setMinHandDetectionConfidence(minHandDetectionConfidence)
            .setMinTrackingConfidence(minHandTrackingConfidence)
            .setMinHandPresenceConfidence(minHandPresenceConfidence)
            .setNumHands(maxNumHands)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result: HandLandmarkerResult, _: MPImage ->
                val landmarks = result.landmarks().firstOrNull()?.map {
                    LandmarkPoint(it.x(), it.y(), it.z())
                }
                onResult(landmarks, SystemClock.uptimeMillis())
            }
            .setErrorListener { error: RuntimeException ->
                Log.w(TAG, "MediaPipe error: ${error.message}")
                onError(error.message ?: "MediaPipe detection error")
            }

        try {
            handLandmarker = HandLandmarker.createFromOptions(context, optionsBuilder.build())
        } catch (e: Exception) {
            Log.w(TAG, "Failed with GPU delegate, retrying with CPU: ${e.message}")
            try {
                baseOptionsBuilder.setDelegate(Delegate.CPU)
                val cpuOptions = optionsBuilder.setBaseOptions(baseOptionsBuilder.build()).build()
                handLandmarker = HandLandmarker.createFromOptions(context, cpuOptions)
            } catch (fallbackEx: Exception) {
                Log.e(TAG, "Failed to initialize HandLandmarker: ${fallbackEx.message}")
                onError("Failed to initialize HandLandmarker: ${fallbackEx.message}")
            }
        }
    }

    /**
     * Feeds a single live video frame to MediaPipe.
     */
    fun detectLiveStream(bitmap: Bitmap, frameTimeMs: Long) {
        val landmarker = handLandmarker ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            landmarker.detectAsync(mpImage, frameTimeMs)
        } catch (e: Exception) {
            Log.w(TAG, "detectLiveStream failed: ${e.message}")
        }
    }

    fun close() {
        try {
            handLandmarker?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close error: ${e.message}")
        }
        handLandmarker = null
    }
}


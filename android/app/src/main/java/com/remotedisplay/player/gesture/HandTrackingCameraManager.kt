package com.remotedisplay.player.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manages the CameraX lifecycle and feeds video frames into MediaPipe analysis pipeline.
 */
class HandTrackingCameraManager(
    private val context: Context,
    private val onFrameAvailable: (Bitmap, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "HandTrackingCameraMgr"
    }

    private var cameraExecutor: ExecutorService? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var isFrontFacing = true
    private var lastFrameTimeMs = 0L
    private val minFrameIntervalMs = 50L // Cap at 20 FPS to prevent CPU starvation and allow smooth video decoding

    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        preferFrontCamera: Boolean = true
    ) {
        this.isFrontFacing = preferFrontCamera
        cameraExecutor = Executors.newSingleThreadExecutor()

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(lifecycleOwner)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider: ${e.message}")
                onError("Failed to start camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner) {
        val provider = cameraProvider ?: return
        val executor = cameraExecutor ?: return

        provider.unbindAll()

        // Select front camera if available, otherwise fallback to any available camera (e.g. external USB on TV Box)
        val cameraSelector = try {
            if (isFrontFacing && provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                isFrontFacing = false
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                // TV Box with external USB webcam
                CameraSelector.Builder().build()
            }
        } catch (_: Exception) {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(480, 360))
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        imageAnalysis.setAnalyzer(executor) { imageProxy: ImageProxy ->
            processImage(imageProxy)
        }

        try {
            provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                imageAnalysis
            )
            Log.i(TAG, "Camera successfully bound to lifecycle")
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed: ${e.message}")
            onError("Camera bind failed: ${e.message}")
        }
    }

    private fun processImage(imageProxy: ImageProxy) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastFrameTimeMs < minFrameIntervalMs) {
            imageProxy.close()
            return
        }
        lastFrameTimeMs = now

        try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val finalBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle() // Recycle unrotated bitmap immediately
                rotated
            } else {
                bitmap
            }

            val timestamp = imageProxy.imageInfo.timestamp / 1_000_000 // Convert ns to ms
            onFrameAvailable(finalBitmap, timestamp)
        } catch (e: Exception) {
            Log.w(TAG, "Error converting image frame: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            cameraExecutor?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping camera: ${e.message}")
        } finally {
            cameraProvider = null
            cameraExecutor = null
        }
    }
}


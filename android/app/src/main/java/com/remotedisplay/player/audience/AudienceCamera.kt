package com.remotedisplay.player.audience

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PointF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.FaceDetector
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.nio.ShortBuffer

/*
 * The camera half of audience counting. ⚠️ READ THIS BEFORE CHANGING ANYTHING HERE.
 *
 * Frame in, face BOXES out, frame gone:
 *   - Camera2 delivers a small YUV frame into an ImageReader. Only the luma (brightness) plane is
 *     read, downscaled to ~320px wide, and the Image is closed at once.
 *   - android.media.FaceDetector — the PLATFORM's detector, on the device since API 1 — finds frontal
 *     faces in that grey picture. Frontal only is the point: an impression is someone LOOKING at the
 *     screen, not walking past it.
 *   - The result is a handful of numbers (centre and size of each face). The grey buffer is
 *     overwritten by the next frame and zeroed on stop. Nothing is written to disk, logged, sent,
 *     shown on screen or reachable by screenshots or the live view — no Surface ever draws a frame.
 *
 * Why the platform detector rather than MediaPipe or ML Kit: it adds NOTHING to the APK, needs no
 * licence beyond the platform's own, and works without Google Play services — which Fire TV and most
 * signage boxes do not have. Its accuracy is lower than a modern model's, and the docs say so.
 */
class AudienceCamera(
    private val context: Context,
    private val onFaces: (faces: List<FaceBox>, hasMotion: Boolean, nowMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    @Volatile private var frameIntervalMs = 500L
    @Volatile private var lastFrameMs = 0L
    @Volatile var running = false; private set

    // Reused working buffers: one grey frame, packed as RGB_565 for FaceDetector. Never persisted.
    private var luma = ByteArray(0)
    private var prevLuma = ByteArray(0)
    private var rotated = ByteArray(0)
    private var packed = ShortArray(0)
    private var bitmap: Bitmap? = null
    private var detector: FaceDetector? = null
    private var sensorRotation = 0
    private var activeRotation = -1
    private var emptyFramesCount = 0

    /**
     * Never throws. getCameraCharacteristics, getOutputSizes and the like can throw on a device whose
     * camera is busy, vanished (a USB camera unplugged) or misreports itself; any of that is a failed
     * start — logged, cleaned up, reported through onError — not a crashed player.
     */
    fun start(fps: Int) {
        try { startCamera(fps) } catch (e: Throwable) { fail("start: ${e.message}") }
    }

    @SuppressLint("MissingPermission")   // the controller checks CAMERA before calling start()
    private fun startCamera(fps: Int) {
        if (running) { setFps(fps); return }
        setFps(fps)
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return onError("no camera service")
        val id = pickCamera(mgr) ?: return onError("no camera")
        val chars = mgr.getCameraCharacteristics(id)
        /*
         * FaceDetector only finds UPRIGHT faces, so the frame is turned to match how the panel is
         * mounted: the sensor's own mounting, corrected for the display's rotation (front and USB
         * cameras mirror the correction).
         */
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val display = try {
            @Suppress("DEPRECATION")
            when ((context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation) {
                android.view.Surface.ROTATION_90 -> 90
                android.view.Surface.ROTATION_180 -> 180
                android.view.Surface.ROTATION_270 -> 270
                else -> 0
            }
        } catch (_: Throwable) { 0 }
        val back = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        sensorRotation = if (back) (sensor - display + 360) % 360 else (360 - ((sensor + display) % 360)) % 360
        activeRotation = -1
        emptyFramesCount = 0
        Log.i(TAG, "camera open: sensor=$sensor, display=$display, back=$back, calculatedRotation=$sensorRotation")
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return onError("no stream config")
        val sizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: return onError("no YUV output")
        // Target ~640x480 so faces at 1-3 metres have > 20px eye distance for FaceDetector
        val size = sizes.filter { it.width in 640..1280 && it.height in 480..960 }.minByOrNull { it.width * it.height }
            ?: sizes.filter { it.width >= 640 && it.height >= 480 }.minByOrNull { it.width * it.height }
            ?: sizes.maxByOrNull { it.width * it.height } ?: return onError("no usable size")

        val t = HandlerThread("audience-camera").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        handler = h
        val r = ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ rd -> onImage(rd) }, h)
        running = true
        try {
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(cam: CameraDevice) {
                    if (!running) { cam.close(); return }
                    device = cam
                    try {
                        @Suppress("DEPRECATION")   // the List<Surface> form is API 21; the OutputConfiguration one is 28
                        cam.createCaptureSession(listOf(r.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                if (!running) { s.close(); return }
                                session = s
                                try {
                                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(r.surface) }.build()
                                    s.setRepeatingRequest(req, null, h)
                                } catch (e: Exception) { fail("repeating request: ${e.message}") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) = fail("session configure failed")
                        }, h)
                    } catch (e: Exception) { fail("session: ${e.message}") }
                }
                override fun onDisconnected(cam: CameraDevice) { cam.close(); fail("camera disconnected") }
                override fun onError(cam: CameraDevice, error: Int) { cam.close(); fail("camera error $error") }
            }, h)
        } catch (e: Exception) { fail("open: ${e.message}") }
    }

    fun setFps(fps: Int) { frameIntervalMs = 1000L / fps.coerceIn(1, 5) }

    private fun fail(msg: String) { Log.w(TAG, msg); stop(); onError(msg) }

    fun stop() {
        running = false
        try { session?.close() } catch (_: Throwable) {}
        try { device?.close() } catch (_: Throwable) {}
        try { reader?.close() } catch (_: Throwable) {}
        session = null; device = null; reader = null
        val t = thread
        thread = null; handler = null
        try { t?.quitSafely() } catch (_: Throwable) {}
        // Nothing of the last frame survives the camera being switched off.
        luma.fill(0); prevLuma.fill(0); rotated.fill(0); packed.fill(0)
        try { bitmap?.eraseColor(0) } catch (_: Throwable) {}
    }

    private fun pickCamera(mgr: CameraManager): String? = try {
        val ids = mgr.cameraIdList.toList()
        fun facing(id: String) = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        // A camera that faces the viewer: the front camera on a tablet, a USB camera on a TV box.
        ids.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
            ?: ids.firstOrNull { facing(it) == CameraCharacteristics.LENS_FACING_EXTERNAL }
            ?: ids.firstOrNull()
    } catch (_: Exception) { null }

    private fun onImage(rd: ImageReader) {
        val img = try { rd.acquireLatestImage() } catch (_: Exception) { null } ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameMs < frameIntervalMs) { img.close(); return }
        lastFrameMs = now
        val w: Int; val h: Int
        try {
            // Luma only, downscaled to ~640px wide so faces at 1-3m have > 20px eye distance
            val plane = img.planes[0]
            val step = (img.width / 640).coerceAtLeast(1)
            w = (img.width / step) and 1.inv()            // FaceDetector needs an even width
            h = img.height / step
            if (luma.size != w * h) luma = ByteArray(w * h)
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            for (y in 0 until h) {
                val row = y * step * rowStride
                for (x in 0 until w) luma[y * w + x] = buf.get(row + x * step * pixStride)
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame read: ${e.message}"); return
        } finally {
            try { img.close() } catch (_: Throwable) {}   // the camera's frame is gone from here on
        }
        var hasMotion = false
        if (prevLuma.size == w * h) {
            var changed = 0
            val totalSamples = (h / 8) * (w / 8)
            for (y in 0 until h step 8) {
                val row = y * w
                for (x in 0 until w step 8) {
                    val curr = luma[row + x].toInt() and 0xFF
                    val prev = prevLuma[row + x].toInt() and 0xFF
                    if (Math.abs(curr - prev) > 20) {
                        changed++
                    }
                }
            }
            if (totalSamples > 0) {
                val ratio = changed.toFloat() / totalSamples
                hasMotion = ratio > 0.025f // >2.5% of sample points changed
                if (hasMotion) Log.d(TAG, "motion detected: ${(ratio * 100).toInt()}% change")
            }
        } else {
            prevLuma = ByteArray(w * h)
        }
        System.arraycopy(luma, 0, prevLuma, 0, w * h)

        val faces = detect(w, h)
        try { onFaces(faces, hasMotion, System.currentTimeMillis()) } catch (e: Throwable) { Log.w(TAG, "aggregate: ${e.message}") }
    }

    private class DetectionResult(val faces: List<FaceBox>, val maxConfidence: Float)

    /** Find faces using primary expected rotation, falling back to alternative rotations if needed. */
    private fun detect(w0: Int, h0: Int): List<FaceBox> {
        val primary = if (activeRotation >= 0) activeRotation else sensorRotation
        val primaryRes = detectRotated(w0, h0, primary)
        if (primaryRes.faces.isNotEmpty()) {
            activeRotation = primary
            emptyFramesCount = 0
            return primaryRes.faces
        }

        emptyFramesCount++
        // Don't search alternative rotations immediately on a single missed frame — wait for at least 3 misses
        if (activeRotation >= 0 && emptyFramesCount < 3) {
            return emptyList()
        }

        // Test candidate rotations and find the one with the strongest face
        val candidates = listOf(
            sensorRotation,
            (sensorRotation + 90) % 360,
            (sensorRotation + 270) % 360,
            (sensorRotation + 180) % 360
        ).filter { it != primary }

        var bestRot = -1
        var bestConf = 0f
        var bestFaces = emptyList<FaceBox>()

        for (rot in candidates) {
            val res = detectRotated(w0, h0, rot)
            if (res.faces.isNotEmpty() && res.maxConfidence > bestConf) {
                bestRot = rot
                bestConf = res.maxConfidence
                bestFaces = res.faces
            }
        }

        if (bestFaces.isNotEmpty() && bestConf >= 0.35f) {
            Log.i(TAG, "Switching active rotation to $bestRot (conf=$bestConf, primary was $primary)")
            activeRotation = bestRot
            emptyFramesCount = 0
            return bestFaces
        }

        if (emptyFramesCount > 8) {
            activeRotation = -1
        }
        return emptyList()
    }

    /** Upright the grey frame for the given rotation, find faces, return normalised boxes. */
    private fun detectRotated(w0: Int, h0: Int, rotDeg: Int): DetectionResult {
        val rot = ((rotDeg % 360) + 360) % 360
        val (w, h) = if (rot == 90 || rot == 270) h0 to w0 else w0 to h0
        val ew = w and 1.inv()
        if (rotated.size != ew * h) rotated = ByteArray(ew * h)
        for (y in 0 until h) for (x in 0 until ew) {
            val (sx, sy) = when (rot) {
                90 -> y to (h0 - 1 - x)
                180 -> (w0 - 1 - x) to (h0 - 1 - y)
                270 -> (w0 - 1 - y) to x
                else -> x to y
            }
            rotated[y * ew + x] = luma[sy * w0 + sx]
        }
        if (packed.size != ew * h) packed = ShortArray(ew * h)
        for (i in 0 until ew * h) {
            val p = rotated[i].toInt() and 0xFF
            packed[i] = (((p shr 3) shl 11) or ((p shr 2) shl 5) or (p shr 3)).toShort()
        }
        var bmp = bitmap
        if (bmp == null || bmp.width != ew || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(ew, h, Bitmap.Config.RGB_565)
            bitmap = bmp
            detector = FaceDetector(ew, h, MAX_FACES)
        }
        bmp!!.copyPixelsFromBuffer(ShortBuffer.wrap(packed))
        val found = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
        val n = try { detector!!.findFaces(bmp, found) } catch (e: Exception) { Log.w(TAG, "detect: ${e.message}"); 0 }
        val out = ArrayList<FaceBox>(n)
        val mid = PointF()
        var maxConf = 0f
        for (i in 0 until n) {
            val f = found[i] ?: continue
            val conf = f.confidence()
            if (conf > maxConf) maxConf = conf
            if (conf < MIN_CONFIDENCE) continue
            f.getMidPoint(mid)
            // Width-normalised in both axes, so a box is square in the units the tracker compares.
            out += FaceBox(mid.x / ew, mid.y / ew, (f.eyesDistance() * 2.5f) / ew)
        }
        if (n > 0) Log.d(TAG, "rot=$rot: found $n, maxConf=$maxConf, valid=${out.size}")
        return DetectionResult(out, maxConf)
    }

    companion object {
        private const val TAG = "AudienceCamera"
        private const val MAX_FACES = 10
        private const val MIN_CONFIDENCE = 0.28f
    }
}

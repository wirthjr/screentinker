package com.remotedisplay.player.gesture

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator

/**
 * Transparent overlay view that renders an elegant golden-yellow hand virtual mouse cursor:
 * 1. Modeled directly from the user's reference designs (golden hand pointer + concentric touch shockwaves).
 * 2. Boundary clamping: entire hand icon stays strictly within the visible screen.
 * 3. Animated click feedback: press-down squish, fingertip glow, and dual concentric touch rings.
 * 4. Auto-hide when idle (1.2s without hand detected), instant wake-up when hand appears.
 */
class VirtualMouseOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var cursorX = -100f
    private var cursorY = -100f
    private var isPinching = false
    private var wasPinching = false
    private var cursorAlpha = 0f
    private var isIdle = true

    // Visual animation states
    private var iconScale = 1.0f
    private var rippleProgress = 0f
    private var rippleAlpha = 0

    // Animators
    private var fadeAnimator: ValueAnimator? = null
    private var scaleAnimator: ValueAnimator? = null
    private var rippleAnimator: ValueAnimator? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val idleTimeoutMs = 1200L

    private val hideRunnable = Runnable {
        isIdle = true
        animateVisibility(false)
    }

    // Geometry coordinates based on reference hand design
    // The index fingertip hotspot is at (x=24.0, y=6.0)
    private val rawTipX = 24.0f
    private val rawTipY = 6.0f
    private val rawWidth = 56.0f
    private val rawHeight = 72.0f

    private val density = resources.displayMetrics.density
    private val baseScale = density * 0.95f
    private val iconWidth = rawWidth * baseScale
    private val iconHeight = rawHeight * baseScale
    private val tipOffsetX = rawTipX * baseScale
    private val tipOffsetY = rawTipY * baseScale

    private val handPath: Path
    private val seamPath: Path

    // Paints
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = Color.parseColor("#1C1917") // Deep charcoal outline
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#38000000") // Soft drop shadow
    }

    private val fingertipPadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FFF9C4") // Creamy light touch pad at the fingertip
    }

    private val seamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f * density
        color = Color.parseColor("#E65100") // Warm amber seam under index finger
        strokeCap = Paint.Cap.ROUND
    }

    // Concentric touch shockwave rings (Image 2)
    private val innerRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.6f * density
        color = Color.parseColor("#FF9800")
    }

    private val outerRipplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.0f * density
        color = Color.parseColor("#FFC107")
    }

    init {
        isClickable = false
        isFocusable = false

        // Construct hand pointer path matching reference Image 1
        handPath = Path().apply {
            moveTo(18f, 34f)
            // Left edge of index finger straight up
            lineTo(18f, 12f)
            // Semicircle top cap of index finger (tip at 24, 6)
            arcTo(RectF(18f, 6f, 30f, 18f), 180f, 180f, false)
            // Right edge of index finger straight down
            lineTo(30f, 35f)
            // Top slope of palm to the right
            lineTo(48f, 38f)
            // Top-right rounded corner of palm
            quadTo(56f, 40f, 56f, 48f)
            // Right edge of palm down
            lineTo(56f, 62f)
            // Bottom-right rounded corner
            quadTo(56f, 72f, 46f, 72f)
            // Bottom edge of palm
            lineTo(30f, 72f)
            // Curve into thumb base
            quadTo(22f, 72f, 19f, 64f)
            // Outer thumb edge up-left
            lineTo(8f, 47f)
            // Thumb tip rounded cap
            quadTo(4f, 40f, 10f, 37f)
            // Inner thumb curve back to index base
            quadTo(16f, 35f, 18f, 41f)
            lineTo(18f, 34f)
            close()
        }

        // Inner shadow seam where index finger meets palm
        seamPath = Path().apply {
            moveTo(30f, 35f)
            quadTo(31f, 43f, 30f, 48f)
        }

        // Rich golden-amber gradient (top-to-bottom)
        fillPaint.shader = LinearGradient(
            0f, 0f, 0f, 72f * baseScale,
            Color.parseColor("#FFCA28"), // Bright amber yellow
            Color.parseColor("#FFA000"), // Warm golden amber
            Shader.TileMode.CLAMP
        )
    }

    /**
     * Updates cursor state from gesture processor.
     * Enforces bounds detection and controls wake-up / idle auto-hide.
     */
    fun updateCursor(state: CursorState) {
        if (state.isHandPresent) {
            this.cursorX = state.screenX
            this.cursorY = state.screenY
            this.isPinching = state.isPinching

            // Cancel any pending auto-hide
            mainHandler.removeCallbacks(hideRunnable)

            // Wake up smoothly if idle or fading out
            if (isIdle || cursorAlpha < 0.99f) {
                isIdle = false
                animateVisibility(true)
            }

            // Animate click / touch state transitions
            if (isPinching != wasPinching) {
                wasPinching = isPinching
                onPinchStateChanged(isPinching)
            }

            invalidate()
        } else {
            // Hand not detected: start auto-hide timeout if still active
            if (!isIdle) {
                mainHandler.removeCallbacks(hideRunnable)
                mainHandler.postDelayed(hideRunnable, idleTimeoutMs)
            }
        }
    }

    private fun onPinchStateChanged(pinching: Boolean) {
        scaleAnimator?.cancel()
        if (pinching) {
            // 1. Press-down squish animation (physical tap feeling)
            scaleAnimator = ValueAnimator.ofFloat(iconScale, 0.86f).apply {
                duration = 80
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    iconScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }

            // 2. Dual concentric shockwave ripple animation from the fingertip (Image 2)
            rippleAnimator?.cancel()
            rippleAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 320
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    rippleProgress = it.animatedValue as Float
                    rippleAlpha = ((1f - rippleProgress) * 230).toInt()
                    invalidate()
                }
                start()
            }
        } else {
            // Spring bounce-back release
            scaleAnimator = ValueAnimator.ofFloat(iconScale, 1.0f).apply {
                duration = 180
                interpolator = OvershootInterpolator(1.8f)
                addUpdateListener {
                    iconScale = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    private fun animateVisibility(visible: Boolean) {
        fadeAnimator?.cancel()
        val targetAlpha = if (visible) 1f else 0f
        val animDuration = if (visible) 160L else 350L
        fadeAnimator = ValueAnimator.ofFloat(cursorAlpha, targetAlpha).apply {
            duration = animDuration
            addUpdateListener {
                cursorAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cursorAlpha <= 0.01f || width <= 0 || height <= 0) return

        val viewW = width.toFloat()
        val viewH = height.toFloat()

        // 1. BOUNDARY CLAMPING: The entire hand icon never leaves the screen!
        val minX = tipOffsetX
        val maxX = (viewW - (iconWidth - tipOffsetX)).coerceAtLeast(minX)
        val minY = tipOffsetY
        val maxY = (viewH - (iconHeight - tipOffsetY)).coerceAtLeast(minY)

        val clampedX = cursorX.coerceIn(minX, maxX)
        val clampedY = cursorY.coerceIn(minY, maxY)

        val alphaInt = (cursorAlpha * 255).toInt().coerceIn(0, 255)
        fillPaint.alpha = alphaInt
        outlinePaint.alpha = alphaInt
        fingertipPadPaint.alpha = alphaInt

        // 2. Draw dual concentric touch shockwave rings when clicking (Image 2)
        if (rippleAlpha > 0 && isPinching) {
            val netRippleAlpha = (rippleAlpha * (alphaInt / 255f)).toInt()
            val innerR = (8f + rippleProgress * 22f) * density
            val outerR = (16f + rippleProgress * 36f) * density

            innerRipplePaint.alpha = netRippleAlpha
            canvas.drawCircle(clampedX, clampedY, innerR, innerRipplePaint)

            outerRipplePaint.alpha = (netRippleAlpha * 0.75f).toInt()
            canvas.drawCircle(clampedX, clampedY, outerR, outerRipplePaint)
        }

        canvas.save()

        // Translate to clamped fingertip hotspot with press scaling
        canvas.translate(clampedX, clampedY)
        canvas.scale(iconScale, iconScale, 0f, 0f)
        canvas.translate(-tipOffsetX, -tipOffsetY)
        canvas.scale(baseScale, baseScale)

        // Draw soft drop shadow behind the hand
        shadowPaint.alpha = (alphaInt * 0.40f).toInt()
        canvas.save()
        canvas.translate(0f, 2f)
        canvas.drawPath(handPath, shadowPaint)
        canvas.restore()

        // Fill golden-yellow hand
        canvas.drawPath(handPath, fillPaint)

        // Draw fingertip touch pad dot (from Image 1!)
        if (isPinching) {
            fingertipPadPaint.color = Color.parseColor("#FFFFFF") // Bright white pulse when clicking
        } else {
            fingertipPadPaint.color = Color.parseColor("#FFF9C4") // Soft cream pad
        }
        canvas.drawCircle(24f, 12f, 4.0f, fingertipPadPaint)

        // Draw subtle index finger seam shadow
        seamPaint.alpha = (alphaInt * 0.60f).toInt()
        canvas.drawPath(seamPath, seamPaint)

        // Draw crisp charcoal outline
        canvas.drawPath(handPath, outlinePaint)

        canvas.restore()
    }
}

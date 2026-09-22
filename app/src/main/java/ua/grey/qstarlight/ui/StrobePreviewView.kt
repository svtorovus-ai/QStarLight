package ua.grey.qstarlight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import ua.grey.qstarlight.R
import ua.grey.qstarlight.ble.BlePrefs
import ua.grey.qstarlight.ble.StrobeTimeline
import kotlin.math.min

/**
 * Small, self-contained preview of the two-lamp strobe timeline.  It deliberately
 * uses the same StrobeTimeline as the BLE runner, so the preview is not a pretty
 * animation that lies about what the controllers will actually do.
 */
class StrobePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lampPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val housingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val housingPath = Path()
    private val innerPath = Path()
    private val accentPath = Path()
    private val beamPath = Path()
    private val lampRect = RectF()
    private val lensRect = RectF()

    private var mode = BlePrefs.StrobeMode.CLASSIC
    private var white = 100
    private var brightness = 100
    private var onMs = 120
    private var offMs = 110
    private var pauseMs = 420
    private var startedAt = System.currentTimeMillis()
    private var attached = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        backgroundPaint.color = color(R.color.control_group)
        borderPaint.style = Paint.Style.STROKE
        borderPaint.strokeWidth = dp(1f)
        borderPaint.color = color(R.color.control_group_stroke)
        textPaint.typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
        textPaint.color = color(R.color.muted)
        textPaint.textAlign = Paint.Align.CENTER
        isFocusable = false
    }

    fun setConfig(
        mode: BlePrefs.StrobeMode,
        white: Int,
        brightness: Int,
        onMs: Int,
        offMs: Int,
        pauseMs: Int
    ) {
        if (this.mode != mode) startedAt = System.currentTimeMillis()
        this.mode = mode
        this.white = white.coerceIn(0, 100)
        this.brightness = brightness.coerceIn(5, 100)
        this.onMs = onMs
        this.offMs = offMs
        this.pauseMs = pauseMs
        invalidate()
        scheduleFrame()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        startedAt = System.currentTimeMillis()
        scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        attached = false
        removeCallbacks(frame)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val radius = dp(14f)
        canvas.drawRoundRect(0f, 0f, w, h, radius, radius, backgroundPaint)
        canvas.drawRoundRect(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f), radius, radius, borderPaint)

        textPaint.textSize = dp(11f)
        canvas.drawText("ПЕРЕГЛЯД • ${modeLabel(mode)}", w / 2f, dp(18f), textPaint)

        val phase = StrobeTimeline.phaseAt(
            System.currentTimeMillis(), startedAt, mode, 2, onMs, offMs, pauseMs
        )
        val step = StrobeTimeline.sequence(mode, 2, onMs, offMs, pauseMs)
            .getOrNull(phase.stepIndex)
        val leftWhite = step?.whites?.getOrNull(0) ?: white
        val rightWhite = step?.whites?.getOrNull(1) ?: white

        val lampWidth = min(dp(150f), w * 0.38f)
        val lampHeight = min(dp(62f), h * 0.37f)
        val gap = min(dp(18f), w * 0.06f)
        val leftX = w / 2f - gap / 2f - lampWidth
        val rightX = w / 2f + gap / 2f
        val top = dp(39f)
        drawLamp(canvas, leftX, top, lampWidth, lampHeight, phase.leftOn, leftWhite, "ЛІВА", mirrored = false)
        drawLamp(canvas, rightX, top, lampWidth, lampHeight, phase.rightOn, rightWhite, "ПРАВА", mirrored = true)

        textPaint.textSize = dp(10f)
        textPaint.color = color(R.color.muted)
        val timing = "ON ${onMs}мс  •  OFF ${offMs}мс  •  ПАУЗА ${pauseMs}мс"
        canvas.drawText(timing, w / 2f, h - dp(11f), textPaint)
    }

    private fun drawLamp(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        on: Boolean,
        whiteValue: Int,
        label: String,
        mirrored: Boolean
    ) {
        val activeColor = mix(Color.rgb(255, 193, 7), Color.rgb(242, 249, 255), whiteValue / 100f)
        val level = if (on) brightness / 100f else 0.16f
        val alpha = (255f * level).toInt().coerceIn(18, 255)
        val outline = Color.argb(if (on) 220 else 82, 70, 180, 199)
        val centerY = y + height * 0.52f
        val innerSide = if (mirrored) x + width * 0.28f else x + width * 0.72f
        val outerSide = if (mirrored) x + width * 0.90f else x + width * 0.10f

        // Angular, tapered housing — closer to a real modern projector headlamp than a card.
        housingPath.reset()
        if (!mirrored) {
            housingPath.moveTo(x + width * 0.03f, y + height * 0.56f)
            housingPath.quadTo(x + width * 0.02f, y + height * 0.22f, x + width * 0.20f, y + height * 0.10f)
            housingPath.lineTo(x + width * 0.88f, y + height * 0.25f)
            housingPath.quadTo(x + width * 0.98f, y + height * 0.36f, x + width * 0.95f, y + height * 0.55f)
            housingPath.quadTo(x + width * 0.90f, y + height * 0.78f, x + width * 0.68f, y + height * 0.91f)
            housingPath.lineTo(x + width * 0.15f, y + height * 0.83f)
        } else {
            housingPath.moveTo(x + width * 0.97f, y + height * 0.56f)
            housingPath.quadTo(x + width * 0.98f, y + height * 0.22f, x + width * 0.80f, y + height * 0.10f)
            housingPath.lineTo(x + width * 0.12f, y + height * 0.25f)
            housingPath.quadTo(x + width * 0.02f, y + height * 0.36f, x + width * 0.05f, y + height * 0.55f)
            housingPath.quadTo(x + width * 0.10f, y + height * 0.78f, x + width * 0.32f, y + height * 0.91f)
            housingPath.lineTo(x + width * 0.85f, y + height * 0.83f)
        }
        housingPath.close()
        housingPaint.style = Paint.Style.FILL
        housingPaint.shader = LinearGradient(0f, y, 0f, y + height,
            Color.rgb(24, 37, 47), Color.rgb(3, 8, 13), Shader.TileMode.CLAMP)
        canvas.drawPath(housingPath, housingPaint)
        housingPaint.shader = null
        borderPaint.color = outline
        borderPaint.strokeWidth = dp(1.2f)
        borderPaint.style = Paint.Style.STROKE
        canvas.drawPath(housingPath, borderPaint)

        // Recess and projector lens.
        innerPath.reset()
        if (!mirrored) {
            innerPath.moveTo(x + width * 0.10f, y + height * 0.55f)
            innerPath.lineTo(x + width * 0.23f, y + height * 0.22f)
            innerPath.lineTo(x + width * 0.84f, y + height * 0.31f)
            innerPath.lineTo(x + width * 0.78f, y + height * 0.78f)
            innerPath.lineTo(x + width * 0.22f, y + height * 0.73f)
        } else {
            innerPath.moveTo(x + width * 0.90f, y + height * 0.55f)
            innerPath.lineTo(x + width * 0.77f, y + height * 0.22f)
            innerPath.lineTo(x + width * 0.16f, y + height * 0.31f)
            innerPath.lineTo(x + width * 0.22f, y + height * 0.78f)
            innerPath.lineTo(x + width * 0.78f, y + height * 0.73f)
        }
        innerPath.close()
        lampPaint.style = Paint.Style.FILL
        lampPaint.color = Color.argb(if (on) 115 else 48, 2, 9, 15)
        canvas.drawPath(innerPath, lampPaint)
        borderPaint.color = Color.argb(if (on) 170 else 70, 50, 108, 124)
        borderPaint.strokeWidth = dp(0.8f)
        canvas.drawPath(innerPath, borderPaint)

        val beamStart = innerSide + if (mirrored) width * 0.04f else -width * 0.04f
        beamPaint.style = Paint.Style.FILL
        beamPaint.color = Color.argb((alpha * 0.46f).toInt(), Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        beamPaint.setShadowLayer(if (on) dp(9f) else 0f, 0f, 0f, activeColor)
        beamPath.reset()
        if (!mirrored) {
            beamPath.moveTo(beamStart, centerY - height * 0.12f)
            beamPath.lineTo(outerSide, centerY - height * 0.045f)
            beamPath.lineTo(outerSide, centerY + height * 0.045f)
            beamPath.lineTo(beamStart, centerY + height * 0.12f)
        } else {
            beamPath.moveTo(beamStart, centerY - height * 0.12f)
            beamPath.lineTo(outerSide, centerY - height * 0.045f)
            beamPath.lineTo(outerSide, centerY + height * 0.045f)
            beamPath.lineTo(beamStart, centerY + height * 0.12f)
        }
        beamPath.close()
        canvas.drawPath(beamPath, beamPaint)
        beamPaint.clearShadowLayer()

        lensRect.set(innerSide - width * 0.12f, centerY - height * 0.27f, innerSide + width * 0.12f, centerY + height * 0.27f)
        lensPaint.style = Paint.Style.FILL
        lensPaint.color = Color.rgb(10, 18, 25)
        canvas.drawOval(lensRect, lensPaint)
        lensPaint.style = Paint.Style.STROKE
        lensPaint.strokeWidth = dp(1.2f)
        lensPaint.color = Color.argb(if (on) 220 else 92, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        canvas.drawOval(lensRect, lensPaint)
        lensPaint.style = Paint.Style.FILL
        lensPaint.color = Color.argb(alpha, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        lensPaint.setShadowLayer(if (on) dp(10f) else 0f, 0f, 0f, activeColor)
        canvas.drawOval(RectF(innerSide - width * 0.06f, centerY - height * 0.18f, innerSide + width * 0.06f, centerY + height * 0.18f), lensPaint)
        lensPaint.clearShadowLayer()

        // Thin DRL eyebrow and lower LED accent.
        accentPaint.style = Paint.Style.STROKE
        accentPaint.strokeCap = Paint.Cap.ROUND
        accentPaint.strokeWidth = dp(2.2f)
        accentPaint.color = Color.argb(if (on) 245 else 70, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        accentPaint.setShadowLayer(if (on) dp(5f) else 0f, 0f, 0f, activeColor)
        accentPath.reset()
        if (!mirrored) {
            accentPath.moveTo(x + width * 0.17f, y + height * 0.21f)
            accentPath.quadTo(x + width * 0.48f, y + height * 0.13f, x + width * 0.83f, y + height * 0.28f)
        } else {
            accentPath.moveTo(x + width * 0.83f, y + height * 0.21f)
            accentPath.quadTo(x + width * 0.52f, y + height * 0.13f, x + width * 0.17f, y + height * 0.28f)
        }
        canvas.drawPath(accentPath, accentPaint)
        accentPaint.strokeWidth = dp(1.2f)
        accentPaint.color = Color.argb(if (on) 180 else 48, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        accentPath.reset()
        accentPath.moveTo(x + width * 0.20f, y + height * 0.76f)
        accentPath.lineTo(x + width * 0.60f, y + height * 0.83f)
        canvas.drawPath(accentPath, accentPaint)
        accentPaint.clearShadowLayer()

        textPaint.color = if (on) Color.argb(240, 244, 248, 250) else color(R.color.muted)
        textPaint.textSize = dp(9f)
        canvas.drawText(label, x + width / 2f, y + height + dp(15f), textPaint)
    }

    private val frame = object : Runnable {
        override fun run() {
            if (!attached) return
            invalidate()
            scheduleFrame()
        }
    }

    private fun scheduleFrame() {
        if (attached) {
            removeCallbacks(frame)
            postDelayed(frame, 40L)
        }
    }

    private fun modeLabel(value: BlePrefs.StrobeMode): String = when (value) {
        BlePrefs.StrobeMode.CLASSIC -> "КЛАСИЧНИЙ"
        BlePrefs.StrobeMode.DOUBLE -> "ПОДВІЙНИЙ"
        BlePrefs.StrobeMode.TRIPLE -> "ПОТРІЙНИЙ"
        BlePrefs.StrobeMode.ALTERNATE -> "ЛІВО ↔ ПРАВО"
        BlePrefs.StrobeMode.DOUBLE_ALTERNATE -> "ПОДВІЙНИЙ ЛІВО ↔ ПРАВО"
        BlePrefs.StrobeMode.YELLOW_WHITE_SWAP -> "ЖОВТИЙ ↔ БІЛИЙ"
    }

    private fun mix(first: Int, second: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) + (Color.red(second) - Color.red(first)) * t).toInt(),
            (Color.green(first) + (Color.green(second) - Color.green(first)) * t).toInt(),
            (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * t).toInt()
        )
    }

    private fun color(id: Int): Int = resources.getColor(id, context.theme)
    private fun dp(value: Float): Float = value * density
}

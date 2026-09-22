package ua.grey.qstarlight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
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
    private val beamPath = Path()
    private val lampRect = RectF()

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

        val lampWidth = min(dp(138f), w * 0.37f)
        val lampHeight = min(dp(53f), h * 0.34f)
        val gap = min(dp(18f), w * 0.06f)
        val leftX = w / 2f - gap / 2f - lampWidth
        val rightX = w / 2f + gap / 2f
        val top = dp(39f)
        drawLamp(canvas, leftX, top, lampWidth, lampHeight, phase.leftOn, leftWhite, "ЛІВА")
        drawLamp(canvas, rightX, top, lampWidth, lampHeight, phase.rightOn, rightWhite, "ПРАВА")

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
        label: String
    ) {
        val activeColor = mix(Color.rgb(255, 193, 7), Color.rgb(242, 249, 255), whiteValue / 100f)
        val level = if (on) brightness / 100f else 0.16f
        val alpha = (255f * level).toInt().coerceIn(18, 255)
        val outline = Color.argb(if (on) 220 else 90, 90, 170, 187)

        lampPaint.style = Paint.Style.FILL
        lampPaint.color = Color.argb(if (on) 120 else 32, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        lampPaint.setShadowLayer(if (on) dp(12f) else 0f, 0f, 0f, activeColor)
        lampRect.set(x, y, x + width, y + height)
        canvas.drawRoundRect(lampRect, dp(12f), dp(12f), lampPaint)
        lampPaint.clearShadowLayer()

        borderPaint.color = outline
        borderPaint.strokeWidth = dp(1f)
        borderPaint.style = Paint.Style.STROKE
        canvas.drawRoundRect(lampRect, dp(12f), dp(12f), borderPaint)

        val lensX = x + width * 0.18f
        val centerY = y + height / 2f
        lensPaint.style = Paint.Style.FILL
        lensPaint.color = Color.argb(alpha, Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        lensPaint.setShadowLayer(if (on) dp(8f) else 0f, 0f, 0f, activeColor)
        canvas.drawCircle(lensX, centerY, height * 0.28f, lensPaint)
        lensPaint.clearShadowLayer()

        beamPaint.style = Paint.Style.FILL
        beamPaint.color = Color.argb((alpha * 0.72f).toInt(), Color.red(activeColor), Color.green(activeColor), Color.blue(activeColor))
        beamPath.reset()
        beamPath.moveTo(lensX + height * 0.12f, centerY - height * 0.18f)
        beamPath.lineTo(x + width * 0.88f, centerY - height * 0.07f)
        beamPath.lineTo(x + width * 0.88f, centerY + height * 0.07f)
        beamPath.lineTo(lensX + height * 0.12f, centerY + height * 0.18f)
        beamPath.close()
        canvas.drawPath(beamPath, beamPaint)

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

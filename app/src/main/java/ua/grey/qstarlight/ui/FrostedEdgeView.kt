package ua.grey.qstarlight.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import ua.grey.qstarlight.R

/**
 * Frosted overlay for the fixed header/footer.  The page is rendered into a
 * small cached bitmap, blurred on Android 12+, then faded out toward the page.
 * Older Android versions retain the same translucent gradient without the blur.
 */
class FrostedEdgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Edge { TOP, BOTTOM }

    private val density = resources.displayMetrics.density
    private val snapshotPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sourceRect = Rect()
    private val destRect = RectF()
    private var source: View? = null
    private var snapshot: Bitmap? = null
    private var edge = Edge.TOP
    private var blurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (blurEnabled) {
            snapshotPaint.renderEffect = RenderEffect.createBlurEffect(
                dp(18f), dp(18f), Shader.TileMode.CLAMP
            )
        }
    }

    fun setEdge(value: Edge) {
        edge = value
        invalidate()
    }

    fun setSource(view: View) {
        source = view
        invalidate()
    }

    fun invalidateFromScroll() {
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val fullWidth = width.toFloat()
        val fullHeight = height.toFloat()
        if (fullWidth <= 0f || fullHeight <= 0f) return

        drawBlurredSource(canvas, fullWidth, fullHeight)

        val fade = if (edge == Edge.TOP) {
            intArrayOf(color(R.color.header_fade_start), color(R.color.header_fade_end))
        } else {
            intArrayOf(color(R.color.bottom_fade_start), color(R.color.bottom_fade_end))
        }
        val fadeStart = fadeStart(fullHeight)
        tintPaint.shader = if (edge == Edge.TOP) {
            LinearGradient(
                0f, 0f, 0f, fullHeight,
                intArrayOf(Color.TRANSPARENT, fade[0], fade[1]),
                floatArrayOf(0f, fadeStart, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, 0f, 0f, fullHeight,
                intArrayOf(fade[0], fade[1], fade[1]),
                floatArrayOf(0f, fadeStart, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, fullWidth, fullHeight, tintPaint)
        tintPaint.shader = null
    }

    private fun drawBlurredSource(canvas: Canvas, width: Float, height: Float) {
        val page = source ?: return
        if (page.width <= 0 || page.height <= 0) return

        val bitmap = ensureSnapshot(page.width, page.height) ?: return
        bitmap.eraseColor(Color.TRANSPARENT)
        page.draw(Canvas(bitmap))

        val sourceLocation = IntArray(2)
        val overlayLocation = IntArray(2)
        page.getLocationOnScreen(sourceLocation)
        getLocationOnScreen(overlayLocation)
        val top = (overlayLocation[1] - sourceLocation[1]).coerceIn(0, page.height - 1)
        val bottom = (top + height.toInt()).coerceAtMost(page.height)
        if (bottom <= top) return

        sourceRect.set(0, top, page.width, bottom)
        destRect.set(0f, 0f, width, height)

        val save = canvas.saveLayer(0f, 0f, width, height, null)
        canvas.drawBitmap(bitmap, sourceRect, destRect, snapshotPaint)
        maskPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        val fadeStart = fadeStart(height)
        maskPaint.shader = if (edge == Edge.TOP) {
            LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
                floatArrayOf(0f, fadeStart, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(Color.TRANSPARENT, Color.WHITE, Color.WHITE),
                floatArrayOf(0f, fadeStart, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width, height, maskPaint)
        maskPaint.shader = null
        maskPaint.xfermode = null
        canvas.restoreToCount(save)
    }

    private fun ensureSnapshot(width: Int, height: Int): Bitmap? {
        val old = snapshot
        if (old != null && old.width == width && old.height == height) return old
        old?.recycle()
        return runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrNull()
            .also { snapshot = it }
    }

    override fun onDetachedFromWindow() {
        snapshot?.recycle()
        snapshot = null
        super.onDetachedFromWindow()
    }

    private fun color(id: Int): Int = resources.getColor(id, context.theme)
    private fun dp(value: Float): Float = value * density

    private fun fadeStart(height: Float): Float {
        val fixed = if (edge == Edge.TOP) {
            resources.getDimension(R.dimen.header_height)
        } else {
            resources.getDimension(R.dimen.header_fade_height)
        }
        return (fixed / height).coerceIn(0f, 1f)
    }
}

package ua.grey.qstarlight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import ua.grey.qstarlight.R

/**
 * Lightweight edge fade used behind the fixed header and footer.
 *
 * This intentionally does not snapshot or blur the whole page. That approach
 * looked like a moving smear and forced a full view render on every scroll.
 * The fixed glass panels already provide the frosted surface; this view only
 * fades the scrolling page into the same dark palette at a constant cost.
 */
class FrostedEdgeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    enum class Edge { TOP, BOTTOM }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var edge = Edge.TOP
    private var source: View? = null

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setEdge(value: Edge) {
        edge = value
        invalidate()
    }

    /** Kept as an API boundary for the activity; no expensive page snapshot is needed. */
    fun setSource(view: View) {
        source = view
    }

    /** Scrolling does not change a static fade, so this is intentionally cheap. */
    fun invalidateFromScroll() = Unit

    override fun onDraw(canvas: Canvas) {
        val fullWidth = width.toFloat()
        val fullHeight = height.toFloat()
        if (fullWidth <= 0f || fullHeight <= 0f) return

        val shader = if (edge == Edge.TOP) {
            val start = resources.getDimension(R.dimen.header_height).coerceIn(0f, fullHeight)
            LinearGradient(
                0f, start, 0f, fullHeight,
                color(R.color.header_fade_start),
                color(R.color.header_fade_end),
                Shader.TileMode.CLAMP
            )
        } else {
            val fadeHeight = resources.getDimension(R.dimen.header_fade_height).coerceIn(0f, fullHeight)
            LinearGradient(
                0f, 0f, 0f, fadeHeight,
                color(R.color.bottom_fade_start),
                color(R.color.bottom_fade_end),
                Shader.TileMode.CLAMP
            )
        }
        paint.shader = shader
        canvas.drawRect(0f, 0f, fullWidth, fullHeight, paint)
        paint.shader = null
    }

    private fun color(id: Int): Int = resources.getColor(id, context.theme)
}

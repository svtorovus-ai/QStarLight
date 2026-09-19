package ua.grey.qstarlight.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import ua.grey.qstarlight.R

/** Keeps the same controls/listeners; uses the available width for a head-unit dashboard. */
class DashboardLayout(context: Context, attrs: AttributeSet?) : LinearLayout(context, attrs) {
    private var cards: List<View> = emptyList()
    private var originalParams: List<LayoutParams> = emptyList()
    private var wide = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        cards = (0 until childCount).map { getChildAt(it) }
        originalParams = cards.map { LayoutParams(it.layoutParams as LayoutParams) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val useColumns = resources.getBoolean(R.bool.light_system_bars) &&
            available >= 700 * resources.displayMetrics.density
        if (cards.isNotEmpty() && useColumns != wide) arrange(useColumns)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun arrange(useColumns: Boolean) {
        cards.forEach { (it.parent as? ViewGroup)?.removeView(it) }
        removeAllViews()
        wide = useColumns
        orientation = if (wide) HORIZONTAL else VERTICAL
        if (!wide) {
            cards.forEachIndexed { index, card -> addView(card, LayoutParams(originalParams[index])) }
            return
        }
        val gap = resources.getDimensionPixelSize(R.dimen.group_gap)
        val controls = LinearLayout(context).apply { orientation = VERTICAL }
        val status = LinearLayout(context).apply { orientation = VERTICAL }
        addView(controls, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.25f).apply { marginEnd = gap })
        addView(status, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        val light = cards.first { it.id == R.id.lightControls }
        controls.addView(light, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        cards.filter { it !== light }.forEachIndexed { index, card ->
            status.addView(card, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                if (index > 0) topMargin = gap
            })
        }
    }
}

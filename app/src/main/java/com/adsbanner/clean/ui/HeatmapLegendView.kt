package com.adsbanner.clean.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatTextView
import com.adsbanner.clean.R
import kotlin.math.roundToInt

/** Each swatch and label stays together; complete entries wrap to the available width. */
class HeatmapLegendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ViewGroup(context, attrs) {
    private data class Row(val items: MutableList<View> = mutableListOf(), var width: Int = 0, var height: Int = 0)
    private val itemGap = dp(12)
    private val rowGap = dp(8)
    private val rows = mutableListOf<Row>()
    private var entries: List<Pair<String, Int>> = emptyList()

    fun setEntries(values: List<Pair<String, Int>>) {
        if (entries == values) return
        entries = values.toList()
        removeAllViews()
        entries.forEach { (label, color) ->
            addView(AppCompatTextView(context).apply {
                setTextAppearance(R.style.Text_RowSub)
                text = label
                setSingleLine(true)
                includeFontPadding = false
                setLineSpacing(0f, 1f)
                gravity = Gravity.CENTER_VERTICAL
                compoundDrawablePadding = dp(6)
                val swatch = GradientDrawable().apply {
                    setColor(color)
                    cornerRadius = dp(3).toFloat()
                    setBounds(0, 0, dp(12), dp(12))
                }
                setCompoundDrawablesRelative(swatch, null, null, null)
                contentDescription = if (label.endsWith("+")) "${label.dropLast(1)} 次及以上"
                    else "${label.replace("–", "至")} 次"
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
            else (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        rows.clear()
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            var row = rows.lastOrNull()
            if (row == null || (row.items.isNotEmpty() && row.width + itemGap + child.measuredWidth > available)) {
                row = Row().also(rows::add)
            }
            if (row.items.isNotEmpty()) row.width += itemGap
            row.items.add(child)
            row.width += child.measuredWidth
            row.height = maxOf(row.height, child.measuredHeight)
        }
        val contentWidth = rows.maxOfOrNull { it.width } ?: 0
        val contentHeight = rows.sumOf { it.height } + (rows.size - 1).coerceAtLeast(0) * rowGap
        setMeasuredDimension(
            resolveSize(maxOf(suggestedMinimumWidth, contentWidth + paddingLeft + paddingRight), widthMeasureSpec),
            resolveSize(maxOf(suggestedMinimumHeight, contentHeight + paddingTop + paddingBottom), heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = paddingTop
        rows.forEach { row ->
            var x = if (rtl) width - paddingRight else paddingLeft
            row.items.forEach { child ->
                val childLeft = if (rtl) x - child.measuredWidth else x
                val childTop = y + (row.height - child.measuredHeight) / 2
                child.layout(childLeft, childTop, childLeft + child.measuredWidth, childTop + child.measuredHeight)
                x += (child.measuredWidth + itemGap) * if (rtl) -1 else 1
            }
            y += row.height + rowGap
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
}

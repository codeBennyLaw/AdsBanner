package com.adsbanner.clean.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.adsbanner.clean.R
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 按天拦截量的热力图。列是一周，行是周一到周日，从安装日期那天开始画，
 * 安装日所在周之前的格子不绘制。
 *
 * 直接 onDraw 画方块而不是塞一堆子 View：数据量可能到几百天，
 * 用 View 堆叠会拖慢测量与布局。
 */
class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Cell(val week: Int, val weekday: Int, val count: Int)

    private val density = resources.displayMetrics.density
    private val cellSize = 12f * density
    private val gap = 3f * density
    private val corner = 2.5f * density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val scale = intArrayOf(
        ContextCompat.getColor(context, R.color.heat_empty),
        ContextCompat.getColor(context, R.color.heat_1),
        ContextCompat.getColor(context, R.color.heat_2),
        ContextCompat.getColor(context, R.color.heat_3),
        ContextCompat.getColor(context, R.color.heat_4)
    )

    private var cells: List<Cell> = emptyList()
    private var weeks = 1

    fun submit(
        counts: Map<LocalDate, Int>,
        installedOn: LocalDate,
        until: LocalDate = LocalDate.now()
    ) {
        val start = if (until.isBefore(installedOn)) until else installedOn
        val firstMonday = start.minusDays((start.dayOfWeek.value - 1).toLong())
        val lastMonday = until.minusDays((until.dayOfWeek.value - 1).toLong())
        weeks = (ChronoUnit.DAYS.between(firstMonday, lastMonday) / 7).toInt() + 1

        val list = ArrayList<Cell>()
        var day = start
        while (!day.isAfter(until)) {
            val week = (ChronoUnit.DAYS.between(firstMonday, day) / 7).toInt()
            list.add(Cell(week, day.dayOfWeek.value - 1, counts[day] ?: 0))
            day = day.plusDays(1)
        }
        cells = list
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (weeks * (cellSize + gap)).toInt() + paddingLeft + paddingRight
        val height = (7 * (cellSize + gap)).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(width, widthMeasureSpec),
            resolveSize(height, heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (cell in cells) {
            val left = paddingLeft + cell.week * (cellSize + gap)
            val top = paddingTop + cell.weekday * (cellSize + gap)
            paint.color = levelOf(cell.count)
            canvas.drawRoundRect(left, top, left + cellSize, top + cellSize, corner, corner, paint)
        }
    }

    private fun levelOf(count: Int): Int = when {
        count <= 0 -> scale[0]
        count <= 2 -> scale[1]
        count <= 5 -> scale[2]
        count <= 9 -> scale[3]
        else -> scale[4]
    }
}

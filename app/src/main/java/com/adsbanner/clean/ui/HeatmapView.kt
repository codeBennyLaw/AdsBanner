package com.adsbanner.clean.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.adsbanner.clean.R
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.floor

/**
 * 按天拦截量的热力图。列是一周，行是周一到周日，从安装日期那天开始画，
 * 安装日所在周之前的格子不绘制。
 *
 * 直接 onDraw 画方块而不是塞一堆子 View：数据量可能到几百天，
 * 用 View 堆叠会拖慢测量与布局。
 *
 * 触摸交给 GestureDetector 识别单击，横向拖动仍由外层 HorizontalScrollView 接管 ——
 * HorizontalScrollView 在滑动距离超过阈值时会拦截事件并发 ACTION_CANCEL，
 * GestureDetector 收到 cancel 就作废本次点击，不会出现"滑动时误触格子"。
 */
class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class Cell(
        val date: LocalDate,
        val week: Int,
        val weekday: Int,
        val count: Int,
        val color: Int
    )

    /** 点击某一天时回调：日期与该天拦截次数 */
    var onCellClick: ((LocalDate, Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val cellSize = 12f * density
    private val gap = 3f * density
    private val corner = 2.5f * density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.brand)
    }

    private val scale = intArrayOf(
        ContextCompat.getColor(context, R.color.heat_empty),
        ContextCompat.getColor(context, R.color.heat_1),
        ContextCompat.getColor(context, R.color.heat_2),
        ContextCompat.getColor(context, R.color.heat_3),
        ContextCompat.getColor(context, R.color.heat_4)
    )

    private var cells: List<Cell> = emptyList()
    private var weeks = 1
    private var selected: LocalDate? = null
    private var previousCounts: Map<LocalDate, Int>? = null
    private var previousStart: LocalDate? = null
    private var previousEnd: LocalDate? = null
    private var firstWeekday = 0
    private val visibleBounds = Rect()

    private val gesture = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val hit = hitTest(e.x, e.y) ?: return false
                selected = hit.date
                contentDescription = "${hit.date}，拦截 ${hit.count} 次"
                performClick()
                invalidate()
                onCellClick?.invoke(hit.date, hit.count)
                return true
            }
        }
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 必须返回 true 才能收到完整的按下/抬起序列，父容器的拦截不受影响
        gesture.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun submit(
        counts: Map<LocalDate, Int>,
        installedOn: LocalDate,
        until: LocalDate = LocalDate.now()
    ): Boolean {
        val start = if (until.isBefore(installedOn)) until else installedOn
        if (previousStart == start && previousEnd == until && previousCounts == counts) return false
        previousStart = start
        previousEnd = until
        previousCounts = counts.toMap()
        firstWeekday = start.dayOfWeek.value - 1
        val firstMonday = start.minusDays((start.dayOfWeek.value - 1).toLong())
        val lastMonday = until.minusDays((until.dayOfWeek.value - 1).toLong())
        val oldWeeks = weeks
        weeks = (ChronoUnit.DAYS.between(firstMonday, lastMonday) / 7).toInt() + 1

        val list = ArrayList<Cell>()
        var day = start
        while (!day.isAfter(until)) {
            val week = (ChronoUnit.DAYS.between(firstMonday, day) / 7).toInt()
            val count = (counts[day] ?: 0).coerceAtLeast(0)
            list.add(Cell(day, week, day.dayOfWeek.value - 1, count, levelOf(count)))
            day = day.plusDays(1)
        }
        cells = list
        if (oldWeeks != weeks) requestLayout()
        invalidate()
        return true
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
        if (!getLocalVisibleRect(visibleBounds)) return
        val stride = cellSize + gap
        val firstWeek = floor((visibleBounds.left - paddingLeft) / stride).toInt().coerceAtLeast(0)
        val lastWeek = floor((visibleBounds.right - paddingLeft) / stride).toInt().coerceAtMost(weeks - 1)
        val first = (firstWeek * 7 - firstWeekday).coerceAtLeast(0)
        val end = ((lastWeek + 1) * 7 - firstWeekday).coerceAtMost(cells.size)
        for (index in first until end) {
            val cell = cells[index]
            val left = paddingLeft + cell.week * (cellSize + gap)
            val top = paddingTop + cell.weekday * (cellSize + gap)
            val right = left + cellSize
            val bottom = top + cellSize

            fillPaint.color = cell.color
            canvas.drawRoundRect(left, top, right, bottom, corner, corner, fillPaint)

            if (cell.date == selected) {
                canvas.drawRoundRect(left, top, right, bottom, corner, corner, strokePaint)
            }
        }
    }

    private fun hitTest(x: Float, y: Float): Cell? {
        if (x < paddingLeft || y < paddingTop) return null
        val column = ((x - paddingLeft) / (cellSize + gap)).toInt()
        val row = ((y - paddingTop) / (cellSize + gap)).toInt()
        if (column < 0 || row !in 0..6) return null
        return cells.getOrNull(column * 7 + row - firstWeekday)
    }

    /** Labels describe actual count ranges, using the exact calendar palette. */
    fun legendEntries(): List<Pair<String, Int>> = HeatmapScale.bands.mapIndexed { level, band -> band.label to scale[level] }

    private fun levelOf(count: Int): Int = scale[HeatmapScale.level(count)]
}

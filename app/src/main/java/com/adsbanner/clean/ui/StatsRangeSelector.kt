package com.adsbanner.clean.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.widget.RadioGroup
import androidx.core.content.ContextCompat
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.adsbanner.clean.R
import kotlin.math.min

/** Native single selection, stable touch targets and one interruptible drawing-only indicator. */
class StatsRangeSelector @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    RadioGroup(context, attrs) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand_light)
    }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.brand)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val bounds = RectF()
    private val radius = resources.getDimension(R.dimen.corner_card)
    private var indicatorLeft = 0f
    private var indicatorWidth = 0f
    private var indicatorTop = 0f
    private var indicatorBottom = 0f
    private var laidOutWidth = 0
    private var laidOutHeight = 0
    private var laidOutDirection = -1
    private var ready = false
    private var listener: OnCheckedChangeListener? = null
    private val spring = SpringAnimation(FloatValueHolder()).apply {
        spring = SpringForce().setStiffness(1000f).setDampingRatio(1f)
        setMinimumVisibleChange(0.5f)
        addUpdateListener { _, value, _ -> indicatorLeft = value; invalidate() }
    }

    init {
        super.setOnCheckedChangeListener { group, id ->
            moveIndicator(animated = true)
            listener?.onCheckedChanged(group, id)
        }
    }

    override fun setOnCheckedChangeListener(listener: OnCheckedChangeListener?) {
        this.listener = listener
    }

    private fun moveIndicator(animated: Boolean) {
        val selected = findViewById<android.view.View>(checkedRadioButtonId) ?: return
        if (selected.width == 0 || selected.height == 0) return
        indicatorWidth = selected.width.toFloat()
        indicatorTop = selected.top.toFloat()
        indicatorBottom = selected.bottom.toFloat()
        val target = selected.left.toFloat()
        if (animated && ready && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            if (!spring.isRunning) spring.setStartValue(indicatorLeft)
            spring.animateToFinalPosition(target)
        } else {
            spring.cancel()
            indicatorLeft = target
            invalidate()
        }
        ready = true
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (!ready || width != laidOutWidth || height != laidOutHeight || layoutDirection != laidOutDirection) {
            laidOutWidth = width
            laidOutHeight = height
            laidOutDirection = layoutDirection
            moveIndicator(animated = false)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (ready) {
            val inset = border.strokeWidth / 2
            bounds.set(indicatorLeft + inset, indicatorTop + inset,
                indicatorLeft + indicatorWidth - inset, indicatorBottom - inset)
            val corner = min(radius, bounds.height() / 2)
            canvas.drawRoundRect(bounds, corner, corner, fill)
            canvas.drawRoundRect(bounds, corner, corner, border)
        }
        super.dispatchDraw(canvas)
    }

    override fun onDetachedFromWindow() {
        spring.cancel()
        moveIndicator(animated = false)
        super.onDetachedFromWindow()
    }
}

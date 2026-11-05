package com.adsbanner.clean.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.adsbanner.clean.R
import kotlin.math.sin

/** Short waves run only while the visible page waits for local data. */
class LocalLoadingDots @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.brand) }
    private val density = resources.displayMetrics.density
    var phase: Float = 0f
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val spacing = 16f * density
        val eased = phase * phase * (3f - 2f * phase)
        for (index in 0..2) {
            val wave = ((sin(eased * Math.PI * 2 - index * Math.PI / 2) + 1) / 2).toFloat()
            paint.alpha = (100 + wave * 155).toInt()
            canvas.drawCircle(width / 2f + (index - 1) * spacing,
                height / 2f - wave * 3f * density, 3.5f * density, paint)
        }
    }
}

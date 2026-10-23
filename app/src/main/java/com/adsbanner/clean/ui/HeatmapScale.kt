package com.adsbanner.clean.ui

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** A logarithmic scale keeps quieter days distinguishable when one day has a large spike. */
internal class HeatmapScale(peak: Int) {
    val maximum = peak.coerceAtLeast(0)
    private val logMaximum = if (maximum > 1) ln(maximum.toDouble()) else 0.0

    fun fraction(count: Int): Float = when {
        count <= 0 || maximum == 0 -> 0f
        maximum == 1 -> 1f
        else -> (ln(count.coerceAtMost(maximum).toDouble()) / logMaximum).toFloat()
    }

    /** Values for the same four positive palette stops, plus the separate zero color. */
    fun references(): List<Int> = if (maximum == 0) listOf(0) else
        (listOf(0) + (0..3).map {
            maximum.toDouble().pow(it / 3.0).roundToInt().coerceIn(1, maximum)
        }).distinct()
}

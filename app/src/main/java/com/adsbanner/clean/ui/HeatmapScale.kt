package com.adsbanner.clean.ui

/** Fixed daily-count ranges keep both the calendar and its legend comparable over time. */
internal object HeatmapScale {
    data class Band(val label: String, val minimum: Int)

    val bands = listOf(
        Band("0", 0),
        Band("1–9", 1),
        Band("10–49", 10),
        Band("50–99", 50),
        Band("100+", 100)
    )

    fun level(count: Int): Int = bands.indexOfLast { count >= it.minimum }.coerceAtLeast(0)
}

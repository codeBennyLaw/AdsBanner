package com.adsbanner.clean.ui

import org.junit.Assert.*
import org.junit.Test

class HeatmapScaleTest {
    @Test fun countBoundariesBelongToTheDisplayedRanges() {
        listOf(0 to 0, 1 to 1, 9 to 1, 10 to 2, 24 to 2, 25 to 3, 49 to 3,
            50 to 4, 99 to 4, 100 to 5, 199 to 5, 200 to 6, 499 to 6, 500 to 7)
            .forEach { (count, expected) -> assertEquals("count=$count", expected, HeatmapScale.level(count)) }
    }

    @Test fun legendUsesFixedReadableRangesIncludingTheOpenEndedLastBand() {
        assertEquals(listOf("0", "1–9", "10–24", "25–49", "50–99", "100–199", "200–499", "500+"),
            HeatmapScale.bands.map { it.label })
        HeatmapScale.bands.forEachIndexed { level, band -> assertEquals(level, HeatmapScale.level(band.minimum)) }
    }

    @Test fun malformedAndVeryLargeCountsStayWithinThePalette() {
        assertEquals(0, HeatmapScale.level(Int.MIN_VALUE))
        assertEquals(7, HeatmapScale.level(Int.MAX_VALUE))
        assertEquals(7, HeatmapScale.level(1_000_000))
    }
}

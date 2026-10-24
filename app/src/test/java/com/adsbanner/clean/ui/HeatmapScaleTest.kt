package com.adsbanner.clean.ui

import org.junit.Assert.*
import org.junit.Test

class HeatmapScaleTest {
    @Test fun countBoundariesBelongToTheDisplayedRanges() {
        listOf(0 to 0, 1 to 1, 9 to 1, 10 to 2, 49 to 2, 50 to 3, 99 to 3, 100 to 4)
            .forEach { (count, expected) -> assertEquals("count=$count", expected, HeatmapScale.level(count)) }
    }

    @Test fun legendUsesFixedReadableRangesIncludingTheOpenEndedLastBand() {
        assertEquals(listOf("0", "1–9", "10–49", "50–99", "100+"), HeatmapScale.bands.map { it.label })
        HeatmapScale.bands.forEachIndexed { level, band -> assertEquals(level, HeatmapScale.level(band.minimum)) }
    }

    @Test fun malformedAndVeryLargeCountsStayWithinThePalette() {
        assertEquals(0, HeatmapScale.level(Int.MIN_VALUE))
        assertEquals(4, HeatmapScale.level(Int.MAX_VALUE))
        assertEquals(4, HeatmapScale.level(1_000_000))
    }
}

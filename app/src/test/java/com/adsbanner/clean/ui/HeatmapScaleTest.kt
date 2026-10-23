package com.adsbanner.clean.ui

import org.junit.Assert.*
import org.junit.Test

class HeatmapScaleTest {
    @Test fun separatesElevenAndSixtyTwoWithoutSaturatingAtTen() {
        val scale = HeatmapScale(62)
        assertTrue(scale.fraction(62) - scale.fraction(11) > 0.4f)
        assertEquals(1f, scale.fraction(62), 0f)
        assertEquals(listOf(0, 1, 4, 16, 62), scale.references())
    }

    @Test fun highOutliersDoNotFlattenAllTheQuieterDays() {
        val scale = HeatmapScale(10_000)
        assertTrue(scale.fraction(62) - scale.fraction(11) > 0.18f)
        val values = listOf(1, 2, 11, 62, 100, 1000, 10_000).map(scale::fraction)
        assertTrue(values.zipWithNext().all { (a, b) -> a < b })
    }

    @Test fun handlesEmptyAndSmallHistoriesWithDistinctNumericReferences() {
        assertEquals(listOf(0), HeatmapScale(0).references())
        assertEquals(0f, HeatmapScale(0).fraction(0), 0f)
        assertEquals(listOf(0, 1), HeatmapScale(1).references())
        assertEquals(1f, HeatmapScale(1).fraction(1), 0f)
        assertEquals(listOf(0, 1, 2), HeatmapScale(2).references())
    }

    @Test fun clampsMalformedCountsAndSupportsLargeTotalsWithoutOverflow() {
        val scale = HeatmapScale(Int.MAX_VALUE)
        assertEquals(0f, scale.fraction(-1), 0f)
        assertEquals(1f, scale.fraction(Int.MAX_VALUE), 0f)
        assertEquals(Int.MAX_VALUE, scale.references().last())
        assertEquals(1f, HeatmapScale(62).fraction(100), 0f)
        assertEquals(listOf(0), HeatmapScale(-1).references())
    }
}

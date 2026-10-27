package com.adsbanner.clean.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class StatsSortTest {
    private fun row(key: String, count: Int, lastAt: Long) =
        StatAppRow(key, key, "", "", count, lastAt = lastAt)

    private val rows = listOf(row("many", 20, 100), row("recent", 3, 900), row("middle", 8, 500))
    private fun keys(sort: StatsSort, source: List<StatAppRow> = rows) = sort.apply(source).map { it.pkg }

    @Test fun defaultIsCountDescendingAndDirectionCanBeReversed() {
        assertEquals(listOf("many", "middle", "recent"), keys(StatsSort()))
        assertEquals(listOf("recent", "middle", "many"), keys(StatsSort(descending = false)))
    }

    @Test fun timeUsesRawTimestampsRatherThanCountsOrFormattedDates() {
        assertEquals(listOf("recent", "middle", "many"), keys(StatsSort(StatsSort.Field.TIME)))
        assertEquals(listOf("many", "middle", "recent"), keys(StatsSort(StatsSort.Field.TIME, false)))
    }

    @Test fun missingTimesStayLastInEitherDirection() {
        val source = rows + row("unknown", 100, 0)
        assertEquals(listOf("recent", "middle", "many", "unknown"), keys(StatsSort(StatsSort.Field.TIME), source))
        assertEquals(listOf("many", "middle", "recent", "unknown"), keys(StatsSort(StatsSort.Field.TIME, false), source))
    }

    @Test fun equalValuesHaveDeterministicSecondaryAndIdentityOrder() {
        val tied = listOf(row("b", 4, 200), row("a", 4, 200), row("older", 4, 100), row("more", 6, 200))
        assertEquals(listOf("more", "a", "b", "older"), keys(StatsSort(), tied))
        assertEquals(listOf("a", "b", "older", "more"), keys(StatsSort(descending = false), tied))
        assertEquals(listOf("more", "a", "b", "older"), keys(StatsSort(StatsSort.Field.TIME), tied))
    }

    @Test fun largeCountsAndTimestampsDoNotOverflowComparisons() {
        val source = listOf(row("small", 1, 1), row("large", Int.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(listOf("large", "small"), keys(StatsSort(), source))
        assertEquals(listOf("small", "large"), keys(StatsSort(StatsSort.Field.TIME, false), source))
    }

    @Test fun changingOrderPreservesCachedRowsAndHistoricalIdentities() {
        val source = listOf(row("miniapp:one", 5, 50), row("miniapp:two", 9, 80))
        val sorted = StatsSort().apply(source)
        assertEquals(listOf("miniapp:two", "miniapp:one"), sorted.map { it.pkg })
        assertEquals(listOf("miniapp:one", "miniapp:two"), source.map { it.pkg })
        assertEquals(source.sumOf { it.count }, sorted.sumOf { it.count })
        assertNotSame(source, sorted)
    }
}

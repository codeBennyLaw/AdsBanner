package com.adsbanner.clean.ui

internal data class StatsSort(val field: Field = Field.COUNT, val descending: Boolean = true) {
    enum class Field { COUNT, TIME }

    fun apply(rows: List<StatAppRow>): List<StatAppRow> = rows.sortedWith { first, second ->
        // Older records can lack a timestamp. Keep those at the end in either direction.
        val unknownTime = if (field == Field.TIME) {
            (first.lastAt <= 0).compareTo(second.lastAt <= 0)
        } else 0
        val primary = when (field) {
            Field.COUNT -> first.count.compareTo(second.count)
            Field.TIME -> first.lastAt.compareTo(second.lastAt)
        }.let { if (descending) -it else it }
        val secondary = when (field) {
            Field.COUNT -> second.lastAt.compareTo(first.lastAt)
            Field.TIME -> second.count.compareTo(first.count)
        }
        when {
            unknownTime != 0 -> unknownTime
            primary != 0 -> primary
            secondary != 0 -> secondary
            else -> first.pkg.compareTo(second.pkg)
        }
    }
}

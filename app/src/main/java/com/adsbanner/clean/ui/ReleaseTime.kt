package com.adsbanner.clean.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object ReleaseTime {
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun format(instant: Instant?, zone: ZoneId = ZoneId.systemDefault()): String =
        instant?.atZone(zone)?.format(format) ?: "暂无"
}

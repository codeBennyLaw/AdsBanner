package com.adsbanner.clean.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReleaseTimeTest {
    @Test fun showsSecondsInTheChosenTimeZone() {
        val instant = Instant.parse("2026-10-23T05:06:07Z")
        assertEquals("2026-10-23 13:06:07", ReleaseTime.format(instant, ZoneId.of("Asia/Shanghai")))
        assertEquals("2026-10-22 22:06:07", ReleaseTime.format(instant, ZoneId.of("America/Los_Angeles")))
    }

    @Test fun missingTimestampHasAnExplicitFallback() {
        assertEquals("暂无", ReleaseTime.format(null, ZoneId.of("Asia/Shanghai")))
    }
}

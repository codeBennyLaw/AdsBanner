package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SplashScanSessionTest {
    @Test fun notificationShadeDoesNotConsumeTheSplashWindow() {
        val session = SplashScanSession()
        session.start("app", 1_000)
        session.updateCoverage(1_500, true)
        assertEquals(500L, session.age(15_000))
        assertTrue(session.pending(15_000, 10_000))
        session.updateCoverage(15_000, false)
        assertEquals(600L, session.age(15_100))
        assertTrue(session.pending(15_100, 10_000))
    }

    @Test fun repeatedSystemEventsDoNotExtendThePauseTwice() {
        val session = SplashScanSession()
        session.start("app", 0)
        session.updateCoverage(500, true)
        session.updateCoverage(1_500, true)
        session.updateCoverage(2_500, true)
        session.updateCoverage(3_000, false)
        session.updateCoverage(3_200, false)
        assertEquals(700L, session.age(3_200))
    }

    @Test fun repeatedPullDownAndCollapseAccumulateOnlyCoveredTime() {
        val session = SplashScanSession()
        session.start("app", 0)
        session.updateCoverage(500, true)
        session.updateCoverage(2_000, false)
        session.updateCoverage(2_500, true)
        session.updateCoverage(4_000, false)
        assertEquals(1_500L, session.age(4_500))
    }

    @Test fun normalSplashDetectionStillExpires() {
        val session = SplashScanSession()
        session.start("app", 1_000)
        assertTrue(session.pending(11_000, 10_000))
        assertFalse(session.pending(11_001, 10_000))
    }

    @Test fun longShadeSessionCannotKeepPollingForever() {
        val session = SplashScanSession()
        session.start("app", 1_000)
        session.updateCoverage(1_100, true)
        assertTrue(session.pending(41_000, 10_000))
        assertFalse(session.pending(41_001, 10_000))
    }

    @Test fun switchingAppsDropsTheOldLaunchAndPause() {
        val session = SplashScanSession()
        session.start("first", 0)
        session.updateCoverage(500, true)
        session.start("second", 2_000)
        assertEquals("second", session.packageName)
        assertFalse(session.covered)
        assertEquals(300L, session.age(2_300))
        session.clear()
        assertNull(session.packageName)
        assertFalse(session.pending(3_000, 10_000))
    }

    @Test fun weakCloseMatchingIsNotExtendedAcrossTheNotificationShade() {
        val session = SplashScanSession()
        session.start("app", 0)
        assertTrue(session.allowWeak(500))
        session.updateCoverage(500, true)
        assertFalse(session.allowWeak(600))
        session.updateCoverage(12_000, false)
        assertTrue(session.pending(12_000, 10_000))
        assertFalse(session.allowWeak(12_000))
    }

    @Test fun oldActiveWindowDuringLaunchGetsABoundedGracePeriod() {
        val session = SplashScanSession()
        session.start("app", 1_000)
        assertTrue(session.awaitingTarget(1_100))
        assertTrue(session.awaitingTarget(16_000))
        assertFalse(session.awaitingTarget(16_001))
        session.start("second", 7_000)
        assertTrue(session.awaitingTarget(7_100))
        session.observeTarget()
        assertFalse(session.awaitingTarget(7_200))
    }
}

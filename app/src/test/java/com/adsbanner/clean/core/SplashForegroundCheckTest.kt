package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SplashForegroundCheckTest {
    @Test fun contentEventMustAgreeWithTheActiveWindow() {
        val check = SplashForegroundCheck()
        check.request("new.app", 1_000)
        assertTrue(check.take(1_000))
        assertFalse(check.accepts("old.app"))
        assertTrue(check.accepts("new.app"))
        assertEquals(100L, check.retryDelay(1_000))
        assertFalse(check.take(1_099))
        assertTrue(check.take(1_100))
    }

    @Test fun packageLessWindowEventsDoNotDiscardTheExpectedLaunch() {
        val check = SplashForegroundCheck()
        check.request("new.app", 0)
        check.request(null, 100)
        assertFalse(check.accepts("old.app"))
        assertTrue(check.accepts("new.app"))
    }

    @Test fun repeatedEventsDoNotExtendTheSameReconciliationWindow() {
        val check = SplashForegroundCheck()
        check.request("app", 0)
        for (time in 0L..600L step 100) {
            check.request("app", time)
            assertTrue(check.take(time))
        }
        assertNull(check.retryDelay(600))
        assertFalse(check.take(601))
    }

    @Test fun completionStillLimitsAStreamOfWindowEvents() {
        val check = SplashForegroundCheck()
        check.request(null, 1_000)
        assertTrue(check.take(1_000))
        assertTrue(check.accepts("visible.app"))
        check.complete()
        assertNull(check.retryDelay(1_001))
        check.request(null, 1_001)
        assertFalse(check.take(1_001))
        assertEquals(99L, check.retryDelay(1_001))
        assertTrue(check.take(1_100))
    }

    @Test fun resetCancelsPendingWorkAndNewForegroundCanSupersedeOldRequest() {
        val check = SplashForegroundCheck()
        check.request("old", 0)
        check.request("new", 100)
        assertFalse(check.accepts("old"))
        assertTrue(check.accepts("new"))
        assertTrue(check.take(650))
        check.clear()
        assertFalse(check.take(651))
        assertNull(check.retryDelay(651))
    }
}

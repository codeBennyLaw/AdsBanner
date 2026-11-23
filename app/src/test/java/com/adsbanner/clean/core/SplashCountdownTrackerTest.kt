package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SplashCountdownTrackerTest {
    private val target = SkipTargetPolicy.Bounds(820, 1810, 960, 1890)

    @Test fun chaoxingStyleTwoSecondLabelRequiresAnActualDecrease() {
        val tracker = SplashCountdownTracker()
        assertFalse(tracker.observe(10, "跳过2s", target, 0))
        assertFalse(tracker.observe(10, "跳过2s", target, 500))
        assertTrue(tracker.observe(10, "跳过1s", target, 1_000))
        assertTrue(tracker.observe(10, "跳过1s", target, 1_050))
    }

    @Test fun staticDescriptionTextAndTooFastNumberChangesAreNotLiveTimers() {
        val tracker = SplashCountdownTracker()
        for (time in 0L..4_000L step 100) assertFalse(tracker.observe(10, "跳过2s", target, time))
        assertFalse(tracker.observe(10, "跳过1s", target, 4_100))
        tracker.clear()
        assertFalse(tracker.observe(10, "跳过3s", target, 0))
        assertFalse(tracker.observe(10, "跳过2s", target, 20))
    }

    @Test fun unrelatedActionsAndOrdinaryTimersDoNotEstablishProof() {
        val tracker = SplashCountdownTracker()
        for (label in listOf("2s", "跳过广告", "2s后可跳过", "跳过登录2s", "跳过片头2s"))
            assertFalse(label, tracker.observe(10, label, target, 0))
        assertFalse(tracker.observe(-1, "跳过2s", target, 0))
        assertFalse(tracker.observe(10, "跳过99s", target, 0))
    }

    @Test fun anotherWindowOrMovedControlCannotBorrowATimerTick() {
        val tracker = SplashCountdownTracker()
        assertFalse(tracker.observe(10, "跳过3s", target, 0))
        assertFalse(tracker.observe(11, "跳过2s", target, 1_000))
        assertFalse(tracker.observe(10, "跳过2s", target.copy(left = 200, right = 340), 1_000))
        assertFalse(tracker.observe(10, "跳过1s", target, 2_000))
    }

    @Test fun recreatedNodesAndSmallBoundsChangesKeepTheSameTimerProof() {
        val tracker = SplashCountdownTracker()
        assertFalse(tracker.observe(10, "跳过 2s", target, 0))
        assertTrue(tracker.observe(10, "跳过1s", target.copy(left = 825), 1_000))
        assertTrue(tracker.observe(10, "跳过 1 s", target, 1_100))
    }

    @Test fun expiredZeroOrResetCountdownCannotAuthorizeATouch() {
        val tracker = SplashCountdownTracker()
        tracker.observe(10, "跳过3s", target, 0)
        assertTrue(tracker.observe(10, "跳过2s", target, 1_000))
        assertFalse(tracker.observe(10, "跳过2s", target, 2_501))
        assertFalse(tracker.observe(10, "跳过5s", target, 2_600))
        assertFalse(tracker.observe(10, "跳过0s", target, 3_600))
    }

    @Test fun consumedCoordinateControlCannotBeTappedAgainAcrossTimerNodeChanges() {
        val tracker = SplashCountdownTracker()
        tracker.observe(10, "跳过3s", target, 0)
        assertTrue(tracker.observe(10, "跳过2s", target, 1_000))
        tracker.consume(10, "跳过2s", target)
        assertFalse(tracker.observe(10, "跳过1s", target, 2_000))
        assertFalse(tracker.observe(10, "跳过5s", target, 2_100))
        assertFalse(tracker.observe(10, "跳过4s", target, 3_100))
        assertFalse(tracker.observe(10, "跳过5s", target, 7_000))
        assertFalse(tracker.observe(10, "跳过4s", target, 8_000))
        assertFalse(tracker.observe(10, "跳过广告3s", target, 9_000))
    }

    @Test fun clearForUserNavigationCoverageOrNewLaunchDropsAllProof() {
        val tracker = SplashCountdownTracker()
        tracker.observe(10, "跳过3s", target, 0)
        assertTrue(tracker.observe(10, "跳过2s", target, 1_000))
        tracker.clear()
        assertFalse(tracker.observe(10, "跳过1s", target, 2_000))
    }
}

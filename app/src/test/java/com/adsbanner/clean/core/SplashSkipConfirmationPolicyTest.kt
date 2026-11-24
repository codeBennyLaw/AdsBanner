package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SplashSkipConfirmationPolicyTest {
    private val target = SkipTargetPolicy.Bounds(820, 1810, 960, 1890)
    private val parent = SkipTargetPolicy.Bounds(0, 0, 1000, 2000)

    @Test fun injectedGestureCanReportTheClickOnItsLargeAncestor() {
        assertTrue(SplashSkipConfirmationPolicy.ownGestureParentClick(true, 1000, 1060, target, parent))
        assertFalse(SplashSkipConfirmationPolicy.ownGestureParentClick(false, 1000, 1060, target, parent))
    }

    @Test fun earlierOrLaterRealUserClicksDoNotBelongToTheGesture() {
        assertFalse(SplashSkipConfirmationPolicy.ownGestureParentClick(true, 1000, 999, target, parent))
        assertFalse(SplashSkipConfirmationPolicy.ownGestureParentClick(true, 1000, 1301, target, parent))
        assertFalse(SplashSkipConfirmationPolicy.ownGestureParentClick(true, 0, 60, target, parent))
    }

    @Test fun aDifferentControlCannotBorrowTheGestureTimeWindow() {
        assertFalse(SplashSkipConfirmationPolicy.ownGestureParentClick(true, 1000, 1060, target,
            SkipTargetPolicy.Bounds(0, 0, 300, 100)))
    }

    @Test fun aStaleVisibleNodeFromAClosedWindowDoesNotPreventCounting() {
        assertFalse(SplashSkipConfirmationPolicy.targetMayRemain(true, true, false, false, false, false, activityChanged = true))
    }

    @Test fun anotherDialogOverAStillVisibleOriginalWindowDoesNotProveClosure() {
        assertTrue(SplashSkipConfirmationPolicy.targetMayRemain(true, false, false, true, false, false))
        assertTrue(SplashSkipConfirmationPolicy.targetMayRemain(false, false, false, true, false, false))
    }

    @Test fun modalWindowThatHidesTheOldAccessibilityTreeStillDoesNotProveClosure() {
        assertTrue(SplashSkipConfirmationPolicy.targetMayRemain(false, false, false, false, false, false))
        assertTrue(SplashSkipConfirmationPolicy.targetMayRemain(true, false, false, false, false, false))
    }

    @Test fun unchangedTargetsAndCoveredTargetsCannotBeCountedAsGone() {
        assertTrue(SplashSkipConfirmationPolicy.targetMayRemain(true, true, true, false, false, false))
        assertTrue(SplashSkipConfirmationPolicy.targetMayRemain(true, false, true, false, true, false))
        assertFalse(SplashSkipConfirmationPolicy.targetMayRemain(false, true, true, false, false, false))
        assertFalse(SplashSkipConfirmationPolicy.targetMayRemain(true, false, true, false, false, false))
    }
}

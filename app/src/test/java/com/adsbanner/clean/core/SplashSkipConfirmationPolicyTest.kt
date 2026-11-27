package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SplashSkipConfirmationPolicyTest {
    private val target = SkipTargetPolicy.Bounds(820, 1810, 960, 1890)
    private val parent = SkipTargetPolicy.Bounds(0, 0, 1000, 2000)

    @Test fun onlyANewWindowGetsAdditionalBoundedResultVerification() {
        assertTrue(SplashSkipConfirmationPolicy.awaitWindowResult(1200, sameWindow = false))
        assertFalse(SplashSkipConfirmationPolicy.awaitWindowResult(1200, sameWindow = true))
        assertFalse(SplashSkipConfirmationPolicy.awaitWindowResult(-1, sameWindow = false))
        assertFalse(SplashSkipConfirmationPolicy.awaitWindowResult(2000, sameWindow = false))
    }

    @Test fun aVerifiedFullActivityReplacementCanHideAnOldWindowStillListedByTheSystem() {
        assertFalse(SplashSkipConfirmationPolicy.originalWindowMayBeVisible(parent, parent, true, true))
        assertTrue(SplashSkipConfirmationPolicy.originalWindowMayBeVisible(parent, parent, true, false))
        assertTrue(SplashSkipConfirmationPolicy.originalWindowMayBeVisible(parent, parent, false, true))
        assertTrue(SplashSkipConfirmationPolicy.originalWindowMayBeVisible(parent, parent.copy(left = 200), true, true))
    }

    @Test fun missingOrEmptyWindowGeometryDoesNotInventAnActivityReplacement() {
        assertFalse(SplashSkipConfirmationPolicy.originalWindowMayBeVisible(null, parent, true, true))
        assertTrue(SplashSkipConfirmationPolicy.originalWindowMayBeVisible(parent, null, true, true))
        assertTrue(SplashSkipConfirmationPolicy.originalWindowMayBeVisible(parent.copy(right = 0), parent, true, true))
    }

    @Test fun aVerifiedActivityCanReplaceADetachedWindowWithoutChangingClass() {
        val replaced = SplashSkipConfirmationPolicy.replacedRemovedWindow(parent, parent, originalListed = false,
            originalNodeReadable = false, screenWidth = 1000, screenHeight = 2000)
        assertTrue(replaced)
        assertFalse(SplashSkipConfirmationPolicy.targetMayRemain(false, false, false, false, false, false, activityReplacement = replaced))
    }

    @Test fun unknownActivityPartialCoverageAndStillReadableSplashCannotProveClose() {
        fun replaces(original: SkipTargetPolicy.Bounds? = parent, current: SkipTargetPolicy.Bounds? = parent,
            listed: Boolean = false, readable: Boolean = false) =
            SplashSkipConfirmationPolicy.replacedRemovedWindow(original, current, listed, readable, 1000, 2000)
        assertFalse(replaces(current = null))
        assertFalse(replaces(current = parent.copy(left = 40)))
        assertFalse(replaces(original = null))
        assertFalse(replaces(original = parent.copy(bottom = 0)))
        assertFalse(replaces(listed = true))
        assertFalse(replaces(readable = true))
    }

    @Test fun windowEntryAnimationMayMovePartOfTheOriginalBoundsOutsideTheDisplay() {
        val shifted = SkipTargetPolicy.Bounds(21, 0, 981, 2099)
        val settled = SkipTargetPolicy.Bounds(0, 0, 959, 2099)
        assertTrue(SplashSkipConfirmationPolicy.replacedRemovedWindow(shifted, settled, false, false, 960, 2100))
        assertFalse(SplashSkipConfirmationPolicy.replacedRemovedWindow(shifted, settled.copy(left = 100), false, false, 960, 2100))
    }

    @Test fun anEarlyRelatedReportCanRetainEitherNativeOrGestureCompletionWithoutAuthorizingAnotherAction() {
        assertTrue(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 1060, 20, 20, relatedSource = true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 1060, 20, 20, relatedSource = false))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 1060, 20, 21, relatedSource = true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 1301, 20, 20, relatedSource = true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 999, 20, 20, relatedSource = true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(0, 60, 20, 20, relatedSource = true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 1060, -1, -1, relatedSource = true))
    }

    @Test fun injectedGestureCanReportTheClickOnItsLargeAncestor() {
        assertTrue(SplashSkipConfirmationPolicy.ownGestureParentClick(true, 1000, 1060, target, parent))
        assertFalse(SplashSkipConfirmationPolicy.ownGestureParentClick(false, 1000, 1060, target, parent))
    }

    @Test fun aLateRelatedScrollKeepsOnlyTheExistingResultUntilItsOriginalDeadline() {
        assertTrue(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 1750, 20, 20, true, scrollReport = true))
        assertTrue(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 3000, 20, 20, true, scrollReport = true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 3001, 20, 20, true, scrollReport = true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 1750, 20, 21, true, scrollReport = true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 1750, 20, 20, false, scrollReport = true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 1750, 20, 20, true))
        assertFalse(SplashSkipConfirmationPolicy.canKeepSubmittedResult(1000, 999, 20, 20, true, scrollReport = true))
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
        assertFalse(SplashSkipConfirmationPolicy.targetMayRemain(true, true, false, false, false, false, activityReplacement = true))
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

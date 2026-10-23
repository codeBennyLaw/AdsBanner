package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class JumpGuardSessionTest {
    private fun JumpGuardSession.go(pkg: String, now: Long, protected: Boolean = true, trusted: Boolean = false) =
        enter(pkg, now, protected, trusted, 10_000L, 2_000L)

    private fun sourceWithAd(): JumpGuardSession = JumpGuardSession().apply {
        go("source", 1_000)
        observeAd("source", 1_100, 10_000)
    }

    @Test fun automaticLaunchWithRecentAdEvidenceCanBeReturned() {
        val session = sourceWithAd()
        val pending = session.go("shop", 1_500)
        assertNotNull(pending)
        assertTrue(session.mayReturn(pending!!, 1_750))
    }

    @Test fun fastAppSwitchWithoutAdEvidenceIsAllowed() {
        val session = JumpGuardSession()
        session.go("source", 1_000)
        assertNull(session.go("shop", 1_100))
    }

    @Test fun userClickOrScrollAllowsShoppingEvenAfterTheOldGracePeriod() {
        val session = sourceWithAd()
        session.userInteraction("source", 1_200)
        session.observeAd("source", 5_000, 10_000)
        assertNull(session.go("shop", 5_500))
    }

    @Test fun lateSourceClickCancelsAnAlreadyScheduledReturn() {
        val session = sourceWithAd()
        val pending = session.go("shop", 1_500)!!
        session.userInteraction("source", 1_525)
        assertFalse(session.mayReturn(pending, 1_750))
    }

    @Test fun interactingWithTheShopCancelsThePendingReturn() {
        val session = sourceWithAd()
        val pending = session.go("shop", 1_500)!!
        session.userInteraction("shop", 1_600)
        assertFalse(session.mayReturn(pending, 1_750))
    }

    @Test fun targetClickBeforeDelayedForegroundConfirmationAlsoAllowsShopping() {
        val session = sourceWithAd()
        session.userInteraction("shop", 1_450)
        assertNull(session.go("shop", 1_500))
    }

    @Test fun notificationOrChooserNavigationAllowsTheJump() {
        val session = sourceWithAd()
        session.systemNavigation(1_300)
        session.observeAd("source", 1_350, 10_000)
        assertNull(session.go("shop", 1_500))
    }

    @Test fun navigationProtectionIsConsumedByTheIntendedAppEntry() {
        val session = sourceWithAd()
        session.systemNavigation(1_200)
        assertNull(session.go("shop", 1_500))
        session.observeAd("shop", 1_600, 10_000)
        assertNotNull(session.go("other", 1_700))
    }

    @Test fun systemOverlayAfterSchedulingCancelsReturn() {
        val session = sourceWithAd()
        val pending = session.go("shop", 1_500)!!
        session.systemNavigation(1_600)
        assertFalse(session.mayReturn(pending, 1_750))
    }

    @Test fun sameAppWindowEventsDoNotRenewLaunchDetection() {
        val session = sourceWithAd()
        session.go("source", 10_900)
        session.observeAd("source", 10_950, 10_000)
        assertNull(session.go("shop", 11_001))
    }

    @Test fun staleAdEvidenceCannotBlockNormalNavigation() {
        val session = sourceWithAd()
        assertNull(session.go("shop", 3_101))
    }

    @Test fun launcherAndUnlistedTargetsAreAllowed() {
        assertNull(sourceWithAd().go("shop", 1_500, trusted = true))
        assertNull(sourceWithAd().go("other", 1_500, protected = false))
    }

    @Test fun recentReturnToAnAppIsAllowed() {
        val session = JumpGuardSession()
        session.go("shop", 500)
        session.go("source", 1_000)
        session.observeAd("source", 1_100, 10_000)
        assertNull(session.go("shop", 1_500))
    }

    @Test fun leavingTheTargetInvalidatesOldCallbacksEvenIfItIsOpenedAgain() {
        val session = sourceWithAd()
        val pending = session.go("shop", 1_500)!!
        session.go("other", 1_550)
        session.go("shop", 1_600)
        assertFalse(session.mayReturn(pending, 1_750))
    }

    @Test fun delayedCallbackAndServiceRestartCannotIssueOldReturns() {
        val session = sourceWithAd()
        val pending = session.go("shop", 1_500)!!
        assertFalse(session.mayReturn(pending, 2_701))
        session.reset()
        assertFalse(session.mayReturn(pending, 1_750))
    }

    @Test fun oldUserActionsFromAnEarlierVisitDoNotDisableNewAdDetection() {
        val session = JumpGuardSession()
        session.go("source", 100)
        session.userInteraction("source", 200)
        session.go("home", 300)
        session.go("source", 7_000)
        session.observeAd("source", 7_100, 10_000)
        assertNotNull(session.go("shop", 7_200))
    }

    @Test fun backgroundAdEventsCannotMarkTheForegroundAppAsAnAd() {
        val session = JumpGuardSession()
        session.go("source", 1_000)
        session.observeAd("background", 1_100, 10_000)
        assertNull(session.go("shop", 1_500))
    }
}

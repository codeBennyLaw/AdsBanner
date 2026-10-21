package com.adsbanner.clean.core

import com.adsbanner.clean.core.BiliPauseAdRule.Kind
import com.adsbanner.clean.core.BiliPauseAdRule.Match
import com.adsbanner.clean.core.BiliPauseAdRule.Target
import org.junit.Assert.*
import org.junit.Test

class BiliPauseAdSessionTest {
    private val cancel = Target(Kind.CANCEL, "hint", 1, 1)
    private val close = Target(Kind.CLOSE, "panel", 2, 3)
    private val empty = Match("player", null, null, null, null)
    private val hint = empty.copy(hintKey = "hint", cancel = cancel)
    private val panel = empty.copy(panelKey = "panel", close = close)

    @Test fun cancellationIsCountedOnlyAfterTheAdWindowPassesAndOnlyOnce() {
        val session = BiliPauseAdSession()
        val action = session.update(hint, 1_000).action!!
        session.complete(action, true)
        assertFalse(session.update(empty, 1_400).record)
        assertFalse(session.update(empty, 4_000).record)
        assertTrue(session.update(empty, 4_500).record)
        assertFalse(session.update(empty, 5_000).record)
    }

    @Test fun acceptedButIneffectiveNodeClickFallsBackToBoundedGestureRetries() {
        val session = BiliPauseAdSession()
        val first = session.update(hint, 1_000).action!!
        assertFalse(first.gesture)
        session.complete(first, true)
        val second = session.update(hint, 1_400).action!!
        assertTrue(second.gesture)
        session.complete(second, true)
        val third = session.update(hint, 1_800).action!!
        assertTrue(third.gesture)
        session.complete(third, false)
        assertNull(session.update(hint, 2_200).action)
        assertFalse(session.update(hint, 6_000).record)
    }

    @Test fun failedCancelAndSuccessfulCloseCountAsOneAd() {
        val session = BiliPauseAdSession()
        val first = session.update(hint, 1_000).action!!
        session.complete(first, true)
        val fallback = session.update(panel, 1_500).action!!
        assertEquals(Kind.CLOSE, fallback.target.kind)
        session.complete(fallback, true)
        assertFalse(session.update(empty, 1_900).record)
        assertTrue(session.update(empty, 2_300).record)
        assertFalse(session.update(empty, 5_000).record)
    }

    @Test fun closeWorksWithoutEverSeeingTheCountdownAndRearmsAtTheSamePosition() {
        val session = BiliPauseAdSession()
        session.complete(session.update(panel, 1_000).action!!, true)
        session.update(empty, 1_400)
        assertTrue(session.update(empty, 1_800).record)
        assertEquals(Kind.CLOSE, session.update(panel, 2_000).action!!.target.kind)
    }

    @Test fun gestureInFlightBlocksAnotherActionAndDoesNotCountOnItsOwn() {
        val session = BiliPauseAdSession()
        val action = session.update(panel.copy(close = close.copy(action = null)), 1_000).action!!
        assertTrue(action.gesture)
        assertNull(session.update(panel, 1_600).action)
        assertFalse(session.update(empty, 2_000).record)
        assertFalse(session.update(empty, 2_400).record)
        session.complete(action, false)
        assertFalse(session.update(empty, 3_000).record)
    }

    @Test fun leavingThePlayerInvalidatesPendingResults() {
        val session = BiliPauseAdSession()
        val action = session.update(hint, 1_000).action!!
        session.update(null, 1_100)
        session.complete(action, true)
        assertFalse(session.update(empty, 5_000).record)
        assertNotNull(session.update(hint, 5_200).action)
    }

    @Test fun disappearingHintWithoutAnAcceptedActionIsNotAnInterception() {
        val session = BiliPauseAdSession()
        session.update(hint.copy(cancel = null), 1_000)
        session.update(empty, 2_000)
        assertFalse(session.update(empty, 5_000).record)
    }

    @Test fun panelContentRemainingPreventsFalseSuccessWhenCloseIconVanishes() {
        val session = BiliPauseAdSession()
        session.complete(session.update(panel, 1_000).action!!, true)
        val contentOnly = panel.copy(close = null)
        assertFalse(session.update(contentOnly, 1_500).record)
        assertFalse(session.update(contentOnly, 5_000).record)
    }

    @Test fun manuallyDismissedPanelDoesNotLeaveTheNextPauseStuckInOldRetries() {
        val session = BiliPauseAdSession()
        session.complete(session.update(hint, 1_000).action!!, true)
        session.update(panel.copy(close = null), 1_500)
        session.update(empty, 2_000)
        assertFalse(session.update(empty, 2_400).record)
        assertNotNull(session.update(hint, 2_600).action)
    }
}

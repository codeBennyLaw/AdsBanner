package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class MiniAppAdSessionTest {
    private val source = MiniAppSource.create("com.tencent.mm", "铁路出行")!!
    private val target = MiniAppAdRule.Target(1, 1, MiniAppAdRule.Bounds(800, 100, 900, 180), "关闭广告")
    private fun shown(app: MiniAppSource = source) = MiniAppAdRule.Match(app, "ad", target, true)
    private fun gone(app: MiniAppSource = source) = MiniAppAdRule.Match(app, null, null, false)

    @Test fun recordsOnceOnlyAfterAnAcceptedCloseAndStableDisappearance() {
        val session = MiniAppAdSession()
        val action = session.update(shown(), 0).action!!
        assertNull(session.update(shown(), 100).action)
        session.complete(action, true)
        assertNull(session.update(gone(), 200).record)
        assertNull(session.update(gone(), 400).record)
        assertEquals(source.key, session.update(gone(), 600).record!!.key)
        assertNull(session.update(gone(), 1000).record)
    }
    @Test fun automaticAdExpiryWithoutSuccessfulActionIsNotRecorded() {
        val session = MiniAppAdSession()
        val action = session.update(shown(), 0).action!!
        session.complete(action, false)
        session.update(gone(), 100)
        assertNull(session.update(gone(), 700).record)
    }
    @Test fun remainingAdMarkerCannotBeCountedAsClosed() {
        val session = MiniAppAdSession()
        val action = session.update(shown(), 0).action!!
        session.complete(action, true)
        assertNull(session.update(shown().copy(target = null), 700).record)
        assertEquals("ad", session.panelKey)
    }
    @Test fun changingMiniAppsOrLeavingTheHostCannotCreateASuccess() {
        val session = MiniAppAdSession()
        val action = session.update(shown(), 0).action!!
        session.complete(action, true)
        val other = MiniAppSource.create("com.tencent.mm", "天气查询")!!
        assertNull(session.update(gone(other), 700).record)
        assertNull(session.update(null, 1200).record)
    }
    @Test fun failedNodeActionsHaveBoundedGestureRetries() {
        val session = MiniAppAdSession()
        val first = session.update(shown(), 0).action!!
        assertFalse(first.gesture)
        session.complete(first, false)
        assertNull(session.update(shown(), 200).action)
        val second = session.update(shown(), 400).action!!
        assertTrue(second.gesture)
        session.complete(second, false)
        val third = session.update(shown(), 800).action!!
        session.complete(third, false)
        assertNull(session.update(shown(), 2000).action)
    }
    @Test fun lateGestureCallbacksDoNotModifyANewMiniAppSession() {
        val session = MiniAppAdSession()
        val old = session.update(shown(), 0).action!!
        session.reset()
        session.complete(old, true)
        session.update(gone(), 100)
        assertNull(session.update(gone(), 1000).record)
    }
    @Test fun delayedNaturalExpiryAfterAnIneffectiveAcceptedClickIsNotRecorded() {
        val session = MiniAppAdSession()
        val action = session.update(shown(), 0).action!!
        session.complete(action, true)
        session.update(shown().copy(target = null), 2000)
        session.update(gone(), 10_000)
        assertNull(session.update(gone(), 10_500).record)
    }
    @Test fun aMissingButtonDoesNotKeepAnAdOnFastPollingForever() {
        val session = MiniAppAdSession()
        val result = session.update(shown().copy(target = null), 0)
        assertFalse(result.urgent)
    }
}

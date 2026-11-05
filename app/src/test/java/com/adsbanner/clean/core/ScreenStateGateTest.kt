package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class ScreenStateGateTest {
    @Test fun missedUnlockBroadcastRecoversOnAnApplicationWindowEvent() {
        val gate = ScreenStateGate()
        gate.update(false)
        assertTrue(gate.allows(10, true) { true })
        assertTrue(gate.ready)
    }

    @Test fun contentEventsAlsoRecoverWithoutStartingAPollingTask() {
        val gate = ScreenStateGate()
        gate.update(false)
        assertFalse(gate.allows(0, false) { false })
        assertTrue(gate.allows(250, false) { true })
        assertTrue(gate.allows(251, false) { error("Ready content events should use the cached state") })
    }

    @Test fun aBackgroundEventStormDoesNotRepeatedlyQueryTheScreenState() {
        val gate = ScreenStateGate()
        gate.update(false)
        var reads = 0
        for (now in 0L until 1_000L) assertFalse(gate.allows(now, false) { reads++; false })
        assertEquals(4, reads)
    }

    @Test fun windowChangesBypassRetryDelayAndDetectAMissedScreenOff() {
        val gate = ScreenStateGate()
        assertFalse(gate.allows(0, true) { false })
        assertTrue(gate.allows(1, true) { true })
        assertFalse(gate.allows(2, true) { false })
        assertFalse(gate.ready)
    }

    @Test fun aRealBroadcastReplacesTheCachedDecisionImmediately() {
        val gate = ScreenStateGate()
        gate.update(false)
        assertFalse(gate.allows(0, false) { false })
        gate.update(true)
        assertTrue(gate.allows(1, false) { error("No extra read needed after unlock") })
        gate.update(false)
        assertFalse(gate.allows(2, false) { false })
    }
}

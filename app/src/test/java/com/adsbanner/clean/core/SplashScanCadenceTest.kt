package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SplashScanCadenceTest {
    @Test fun eventBurstCannotStarveQueriesOrForceFullWalks() {
        val cadence = SplashScanCadence()
        var queries = 0
        var trees = 0
        for (now in 0L..1_000L) {
            if (cadence.queryDelay(now) == 0L) {
                cadence.queried(now); queries++
                if (cadence.takeTreePass(now)) trees++
            }
        }
        assertEquals(32, queries)
        assertEquals(3, trees)
    }

    @Test fun quietLaunchUsesFewerPassesAndKeepsTheInitialFastPath() {
        val cadence = SplashScanCadence()
        var now = 0L
        var passes = 0
        var trees = 0
        while (now <= 10_000L) {
            cadence.queried(now); passes++
            if (cadence.takeTreePass(now)) trees++
            now += cadence.pollDelay(now, false)
        }
        assertTrue(passes < 40)
        assertTrue(trees < 20)
        assertEquals(80L, cadence.pollDelay(0, false))
        assertEquals(600L, cadence.pollDelay(9_000, false))
    }

    @Test fun shadeRemainsBoundedAndNewApplicationGetsImmediateWork() {
        val cadence = SplashScanCadence()
        cadence.queried(100)
        assertEquals(22L, cadence.queryDelay(110))
        assertEquals(600L, cadence.pollDelay(100, true))
        assertTrue(cadence.takeTreePass(100))
        assertFalse(cadence.takeTreePass(499))
        cadence.reset()
        assertEquals(0L, cadence.queryDelay(110))
        assertTrue(cadence.takeTreePass(110))
    }
}

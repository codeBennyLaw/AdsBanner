package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SkipRetryPolicyTest {
    @Test fun firstFailureAllowsAnotherRoundAfterBackoff() {
        assertFalse(SkipRetryPolicy.allows(1, 1_000, 1_349))
        assertTrue(SkipRetryPolicy.allows(1, 1_000, 1_350))
    }

    @Test fun secondFailureWaitsLonger() {
        assertFalse(SkipRetryPolicy.allows(2, 1_000, 1_699))
        assertTrue(SkipRetryPolicy.allows(2, 1_000, 1_700))
    }

    @Test fun threeFailedRoundsNeverEnableAnUnchangedTargetAgain() {
        for (time in listOf(1_000L, 2_000L, 30_000L))
            assertFalse(SkipRetryPolicy.allows(3, 1_000, time))
        assertFalse(SkipRetryPolicy.allows(4, 1_000, 50_000))
    }

    @Test fun staleClockAndInvalidFailureCountDoNotAuthorizeTaps() {
        assertFalse(SkipRetryPolicy.allows(1, 1_000, 999))
        assertFalse(SkipRetryPolicy.allows(0, 1_000, 2_000))
        assertFalse(SkipRetryPolicy.allows(-1, 1_000, 2_000))
    }
}

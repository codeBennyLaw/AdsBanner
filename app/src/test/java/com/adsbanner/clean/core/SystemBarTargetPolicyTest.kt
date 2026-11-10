package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SystemBarTargetPolicyTest {
    private val app = SkipTargetPolicy.Bounds(0, 0, 1000, 2000)
    private val bar = SkipTargetPolicy.Bounds(0, 0, 1000, 60)
    private val target = SkipTargetPolicy.Bounds(870, 10, 970, 50)

    @Test fun applicationNodeCanOverlapTheThinStatusBarBand() {
        assertTrue(SystemBarTargetPolicy.overlapsStatusBar(target, app, bar))
        assertTrue(SystemBarTargetPolicy.overlapsStatusBar(target.copy(top = 40, bottom = 100), app, bar))
    }

    @Test fun shadeAndFloatingPanelsAreNotStatusBars() {
        assertFalse(SystemBarTargetPolicy.overlapsStatusBar(target, app, bar.copy(bottom = 1200)))
        assertFalse(SystemBarTargetPolicy.overlapsStatusBar(target, app, bar.copy(left = 700)))
        assertFalse(SystemBarTargetPolicy.overlapsStatusBar(target, app, bar.copy(top = 10)))
    }

    @Test fun offscreenOrUnrelatedNodesCannotUseThisException() {
        assertFalse(SystemBarTargetPolicy.overlapsStatusBar(target.copy(top = -1), app, bar))
        assertFalse(SystemBarTargetPolicy.overlapsStatusBar(target.copy(top = 100, bottom = 150), app, bar))
        assertFalse(SystemBarTargetPolicy.overlapsStatusBar(target, app.copy(top = 60), bar))
        assertFalse(SystemBarTargetPolicy.overlapsStatusBar(target, app, bar.copy(bottom = 0)))
    }
}

package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SkipTargetPolicyTest {
    private val button = SkipTargetPolicy.Bounds(820, 100, 960, 160)
    @Test fun explicitSmallTopButtonsAreAccepted() {
        assertEquals(100, SkipTargetPolicy.score(SkipRule.STRONG, button, 1000, 2000))
        assertEquals(60, SkipTargetPolicy.score(SkipRule.WEAK, button, 1000, 2000))
    }
    @Test fun bottomRightSplashSkipIsAcceptedButGenericCloseIsNot() {
        val bili = SkipTargetPolicy.Bounds(706, 1828, 910, 1935)
        assertEquals(SkipRule.STRONG, SkipRule.match("跳过 3"))
        assertTrue(JumpAdRule.isAdSkip("跳过 3"))
        assertEquals(90, SkipTargetPolicy.score(SkipRule.STRONG, bili, 946, 2048))
        assertEquals(0, SkipTargetPolicy.score(SkipRule.WEAK, bili, 946, 2048))
    }
    @Test fun blankBottomSpaceDoesNotExcludeRaisedOrCenteredStrongTargets() {
        val bottom = SkipTargetPolicy.Bounds(800, 1700, 960, 1800)
        assertEquals(90, SkipTargetPolicy.score(SkipRule.STRONG, bottom, 1000, 2000))
        listOf(bottom.copy(left = 350, right = 510), bottom.copy(left = 650, right = 810),
            bottom.copy(top = 1200, bottom = 1300))
            .forEach { assertEquals(it.toString(), 80, SkipTargetPolicy.score(SkipRule.STRONG, it, 1000, 2000)) }
        listOf(bottom.copy(left = 600), bottom.copy(bottom = 1880))
            .forEach { assertEquals(it.toString(), 0, SkipTargetPolicy.score(SkipRule.STRONG, it, 1000, 2000)) }
    }
    @Test fun strongTargetsCoverTheScreenButGenericCloseRemainsTopRightOnly() {
        for (left in listOf(0, 420, 840)) for (top in listOf(0, 600, 1000, 1500, 1900)) {
            val target = SkipTargetPolicy.Bounds(left, top, left + 140, top + 60)
            assertTrue(target.toString(), SkipTargetPolicy.score(SkipRule.STRONG, target, 1000, 2000) > 0)
            if (left < 550 || top >= 600) assertEquals(target.toString(), 0, SkipTargetPolicy.score(SkipRule.WEAK, target, 1000, 2000))
        }
    }
    @Test fun offscreenAndLargeContainersCannotBecomeButtons() {
        listOf(button.copy(left = -1), button.copy(right = 1001), button.copy(top = -1),
            button.copy(bottom = 2001), button.copy(left = 0, right = 1000), button.copy(bottom = 600))
            .forEach { assertFalse(it.toString(), SkipTargetPolicy.smallTarget(it, 1000, 2000)) }
        assertFalse(SkipTargetPolicy.smallTarget(button, 0, 0))
    }
    @Test fun parentMustContainTheActualTargetAndStaySmall() {
        assertTrue(SkipTargetPolicy.preciseParent(button, SkipTargetPolicy.Bounds(810, 90, 970, 170)))
        assertFalse(SkipTargetPolicy.preciseParent(button, button.copy(left = 0, right = 140)))
        assertFalse(SkipTargetPolicy.preciseParent(button, SkipTargetPolicy.Bounds(0, 0, 1000, 2000)))
    }
}

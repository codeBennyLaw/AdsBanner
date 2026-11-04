package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SkipTargetPolicyTest {
    private val button = SkipTargetPolicy.Bounds(820, 100, 960, 160)
    @Test fun explicitSmallTopButtonsAreAcceptedButBottomNavigationIsNot() {
        assertEquals(100, SkipTargetPolicy.score(SkipRule.STRONG, button, 1000, 2000))
        assertEquals(60, SkipTargetPolicy.score(SkipRule.WEAK, button, 1000, 2000))
        assertEquals(0, SkipTargetPolicy.score(SkipRule.STRONG, button.copy(top = 1600, bottom = 1700), 1000, 2000))
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

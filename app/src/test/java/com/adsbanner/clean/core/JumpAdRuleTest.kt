package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class JumpAdRuleTest {
    @Test fun requiresAnAdMarkerOrCountdownAlongsideASkipButton() {
        listOf("跳过 5", "跳过5秒", "3s 跳过", "跳过丨5", "关闭广告", "skip ad", "5s skip").forEach {
            assertTrue(it, JumpAdRule.isAdSkip(it))
        }
        assertTrue(JumpAdRule.isAdSkip("跳过", hasAdMarker = true))
        assertTrue(JumpAdRule.isAdSkip("skip", hasAdMarker = true))
    }

    @Test fun onboardingVideoNavigationAndOrdinaryShoppingAreNotAdEvidence() {
        listOf("跳过", "skip", "跳过第2步", "跳过片头", "跳过剧情", "广告", "去淘宝购买", "×", "关闭").forEach {
            assertFalse(it, JumpAdRule.isAdSkip(it))
        }
        assertFalse(JumpAdRule.isAdSkip("跳过片头", hasAdMarker = true))
    }
}

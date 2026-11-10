package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class JumpAdRuleTest {
    @Test fun unitlessLeadingCountdownIsExplicitSkipEvidence() {
        for (label in listOf("2跳过", "2 跳过", "2\n跳过", "0 跳过", "10跳过", "99 跳过广告"))
            assertTrue(label, JumpAdRule.isAdSkip(label))
        for (label in listOf("2", "123跳过", "2秒后可跳过", "第2步 跳过", "2 跳过登录", "2 跳过本次闹钟")) {
            assertFalse(label, JumpAdRule.isAdSkip(label))
            assertFalse(label, JumpAdRule.isAdSkip(label, hasAdMarker = true))
        }
    }

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

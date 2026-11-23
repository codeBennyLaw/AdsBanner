package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SplashSkipPolicyTest {
    @Test fun ordinaryLabelsCannotAuthorizeATapEvenWhenTheyMatchTheSkipVocabulary() {
        for (label in listOf("跳过广告", "关闭广告", "跳过", "skip ad", "Skip", "广告 | 跳过")) {
            assertEquals(label, SkipRule.STRONG, SkipRule.match(label))
            assertFalse(label, SplashSkipPolicy.hasEvidence(label, "example.app:id/title", nearbyAd = false))
        }
        for (label in listOf("广告设置", "跳过广告说明", "跳过登录", "广告 | 5秒后可跳过"))
            assertFalse(label, SplashSkipPolicy.hasEvidence(label, "example.app:id/splash_skip", nearbyAd = true))
    }

    @Test fun actualSkipCountdownsRetainSupportedChineseAndEnglishFormats() {
        for (label in listOf("跳过5秒", "跳过广告 3", "2跳过", "2s｜跳过", "跳过（5）", "Skip ad 5s", "3s skip ad")) {
            assertTrue(label, JumpAdRule.hasSkipCountdown(label))
            assertTrue(label, SplashSkipPolicy.hasEvidence(label, null, nearbyAd = false))
        }
        for (label in listOf("5秒后可跳过", "跳过登录 5秒", "跳过片头 5秒", "skip ad in 5s"))
            assertFalse(label, JumpAdRule.hasSkipCountdown(label))
    }

    @Test fun adActionIdsAreEvidenceButSettingsIdsAreNot() {
        for (entry in listOf("splash_skip_btn", "adSkipButton", "tt_splash_skip", "ad_close", "ads_dismiss")) {
            assertTrue(entry, SkipRule.isAdSkipResourceId("example.app:id/$entry"))
            assertTrue(entry, SplashSkipPolicy.hasEvidence("跳过广告", "example.app:id/$entry", nearbyAd = false))
        }
        for (entry in listOf("skip", "title", "skip_ad_settings", "ad_skip_switch", "splash_skip_summary", "ad_skip_checkbox"))
            assertFalse(entry, SkipRule.isAdSkipResourceId("example.app:id/$entry"))
        assertFalse(SkipRule.isAdSkipResourceId(null))
        assertFalse(SkipRule.isAdSkipResourceId("splash_skip_btn"))
        assertTrue(SplashSkipPolicy.hasEvidence("跳过", null, nearbyAd = true))
    }

    @Test fun textCheckboxesAndScrollableRowsCannotMasqueradeAsButtons() {
        assertTrue(SplashSkipPolicy.actionable(true, false, false, false))
        assertFalse(SplashSkipPolicy.actionable(false, false, false, false))
        assertFalse(SplashSkipPolicy.actionable(true, true, false, false))
        assertFalse(SplashSkipPolicy.actionable(true, false, true, false))
        assertFalse(SplashSkipPolicy.actionable(true, false, false, true))
    }

    @Test fun onlyIndependentNearbyAdMarkersCanConfirmAnUnnumberedButton() {
        val target = SkipTargetPolicy.Bounds(800, 100, 950, 160)
        val marker = SkipTargetPolicy.Bounds(20, 100, 80, 140)
        assertTrue(SplashSkipPolicy.markerNearTarget("广告", marker, target, 1000, 2000))
        assertTrue(SplashSkipPolicy.markerNearTarget("AD", marker, target, 1000, 2000))
        for (label in listOf("广告设置", "跳过广告", "会员免广告", "去广告"))
            assertFalse(label, SplashSkipPolicy.markerNearTarget(label, marker, target, 1000, 2000))
        assertFalse(SplashSkipPolicy.markerNearTarget("广告", target, target, 1000, 2000))
        assertFalse(SplashSkipPolicy.markerNearTarget("广告", marker.copy(top = 1400, bottom = 1450), target, 1000, 2000))
        assertFalse(SplashSkipPolicy.markerNearTarget("广告", marker.copy(right = 900), target, 1000, 2000))
    }
}

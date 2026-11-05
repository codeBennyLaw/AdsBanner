package com.adsbanner.clean.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipRuleTest {
    @Test fun clickToSkipCountdownKeepsLegacyLabelCoverage() {
        for (label in listOf("点击跳过 5s", "轻点跳过广告 3秒", "点击跳过5")) {
            assertEquals(label, SkipRule.STRONG, SkipRule.match(label))
            assertTrue(label, JumpAdRule.isAdSkip(label))
        }
        for (label in listOf("点击跳过登录", "点击跳过教程", "点击跳过验证"))
            assertEquals(label, SkipRule.NONE, SkipRule.match(label))
    }

    @Test fun timerAndDescriptionOnTheSameControlFormAnExplicitSkip() {
        assertEquals("跳过 5s", SkipRule.buttonLabel("5s", "跳过"))
        assertEquals("跳过 5 秒", SkipRule.buttonLabel("跳过", "5 秒"))
        assertEquals("跳过广告", SkipRule.buttonLabel("跳过", "跳过广告"))
        assertEquals("跳过登录", SkipRule.buttonLabel("跳过登录", "跳过广告"))
    }

    @Test fun onlyStandaloneCountdownsCanJoinTheirSkipControl() {
        assertEquals("跳过 5s", SkipRule.withCountdown("跳过", "5s"))
        assertEquals(null, SkipRule.withCountdown("跳过", "5秒后可跳过"))
        assertEquals(null, SkipRule.withCountdown("跳过", "广告设置"))
        assertEquals(null, SkipRule.withCountdown("关闭", "5s"))
        listOf("跳过｜5", "跳过丨5s", "跳过(5秒)", "跳过（5）").forEach {
            assertEquals(it, SkipRule.STRONG, SkipRule.match(it))
            assertTrue(it, JumpAdRule.isAdSkip(it))
        }
    }
    @Test fun ordinaryFlowsAndFutureCountdownsAreNotAdSkipButtons() {
        listOf("跳过登录", "跳过设置", "不跳过", "是否跳过", "跳过教程", "跳过验证", "跳过片头", "3秒后跳过", "5秒后可跳过")
            .forEach { assertEquals(it, SkipRule.NONE, SkipRule.match(it)) }
    }

    @Test fun whitespaceVariantsRemainExplicitMatches() {
        listOf("跳过 广告", "跳过\u3000广告 3 秒", "跳过\n广告", "Skip Ad", "跳过丨5")
            .forEach { assertEquals(it, SkipRule.STRONG, SkipRule.match(it)) }
    }
    @Test
    fun recognizesCountdownAndEnglishSkipVariants() {
        listOf(
            "\u8DF3\u8FC7 \u5E7F\u544A 3 \u79D2",
            "\u8DF3\u8FC7\n5s",
            "3s skip ad",
            "skip 5 s"
        ).forEach { assertEquals(it, SkipRule.STRONG, SkipRule.match(it)) }
    }

    @Test
    fun keepsGenericCloseAsWeakMatchAndRejectsUnrelatedSkipText() {
        assertEquals(SkipRule.WEAK, SkipRule.match("\u5173\u95ED"))
        assertEquals(SkipRule.NONE, SkipRule.match("\u8DF3\u8FC7\u6B64\u5267\u60C5"))
    }

    @Test fun alarmAndCallActionsCannotUseGenericSkipFallback() {
        listOf("跳过本次闹钟", "跳过响铃", "跳过来电", "skip alarm", "Skip Alarm", "snooze")
            .forEach { assertEquals(it, SkipRule.NONE, SkipRule.match(it)) }
    }

    @Test fun timerCountdownAloneCannotAuthorizeGenericClose() {
        listOf("10秒", "5s", "30 秒后自动关闭", "稍后提醒", "再睡 5 分钟", "跳过")
            .forEach { assertFalse(it, SkipRule.hasAdEvidence(it)) }
    }

    @Test fun explicitAdsAndSkipCountdownsStillAuthorizeAdClose() {
        listOf("广告", "关闭广告", "跳过 5", "3s skip ad")
            .forEach { assertTrue(it, SkipRule.hasAdEvidence(it)) }
    }

    @Test fun standaloneAdMarkersAreDistinctFromSettingsAndOrdinaryCountdowns() {
        listOf("广告", "广告 3 秒", "广告丨5", "AD", "Advertisement")
            .forEach { assertTrue(it, SkipRule.isStandaloneAdMarker(it)) }
        listOf("广告设置", "去广告", "会员免广告", "广告权益", "10秒", "跳过", "普通内容广告介绍")
            .forEach { assertFalse(it, SkipRule.isStandaloneAdMarker(it)) }
    }

    @Test fun embeddedPagesAreExcludedWithoutBlockingHostLaunchers() {
        assertTrue(SkipRule.isEmbeddedPage("com.tencent.mm", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"))
        assertTrue(SkipRule.isEmbeddedPage("com.tencent.mobileqq", "com.tencent.qqmini.sdk.ui.MiniActivity"))
        assertTrue(SkipRule.isEmbeddedPage("com.eg.android.AlipayGphone", "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity"))
        assertFalse(SkipRule.isEmbeddedPage("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
        assertFalse(SkipRule.isEmbeddedPage("com.tencent.mobileqq", "com.tencent.mobileqq.activity.SplashActivity"))
        assertFalse(SkipRule.isEmbeddedPage("example.app", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"))
    }
}

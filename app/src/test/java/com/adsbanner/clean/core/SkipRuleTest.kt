package com.adsbanner.clean.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipRuleTest {
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

    @Test fun embeddedPagesAreExcludedWithoutBlockingHostLaunchers() {
        assertTrue(SkipRule.isEmbeddedPage("com.tencent.mm", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"))
        assertTrue(SkipRule.isEmbeddedPage("com.tencent.mobileqq", "com.tencent.qqmini.sdk.ui.MiniActivity"))
        assertTrue(SkipRule.isEmbeddedPage("com.eg.android.AlipayGphone", "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity"))
        assertFalse(SkipRule.isEmbeddedPage("com.tencent.mm", "com.tencent.mm.ui.LauncherUI"))
        assertFalse(SkipRule.isEmbeddedPage("com.tencent.mobileqq", "com.tencent.mobileqq.activity.SplashActivity"))
        assertFalse(SkipRule.isEmbeddedPage("example.app", "com.tencent.mm.plugin.appbrand.ui.AppBrandUI00"))
    }
}

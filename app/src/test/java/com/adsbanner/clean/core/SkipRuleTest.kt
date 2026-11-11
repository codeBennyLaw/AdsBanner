package com.adsbanner.clean.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkipRuleTest {
    @Test fun leadingCountdownAcceptsOneSeparatorBeforeTheSkipAction() {
        for (separator in listOf("|", "｜", "丨", "/", "·", "•")) {
            for (label in listOf("2s${separator}跳过", "2 S $separator 跳过", "2 秒\n$separator\n跳过广告", "2 $separator 跳过")) {
                assertEquals(label, SkipRule.STRONG, SkipRule.match(label))
                assertTrue(label, JumpAdRule.isAdSkip(label))
            }
        }
    }

    @Test fun countdownSeparatorsDoNotAuthorizeOtherActionsOrFutureSkips() {
        for (label in listOf("2s|跳过登录", "2s|跳过教程", "2s|跳过片头", "2s|跳过本次闹钟",
            "2s后|跳过", "2s|后可跳过", "第2步|跳过", "123s|跳过", "2s||跳过", "|2s|跳过", "2s-跳过")) {
            assertEquals(label, SkipRule.NONE, SkipRule.match(label))
            assertFalse(label, JumpAdRule.isAdSkip(label, hasAdMarker = true))
        }
    }

    @Test fun leadingCountdownWithoutAUnitIsACompleteSkipAction() {
        for (label in listOf("2跳过", "2 跳过", "2\n跳过", "0 跳过", "10跳过", "99 跳过广告"))
            assertEquals(label, SkipRule.STRONG, SkipRule.match(label))
    }

    @Test fun leadingNumbersCannotTurnInstructionsOrOtherActionsIntoSkipButtons() {
        for (label in listOf("2", "123跳过", "2秒后跳过", "2秒后可跳过", "第2步 跳过", "2 跳过登录", "2跳过教程", "2 跳过本次闹钟"))
            assertEquals(label, SkipRule.NONE, SkipRule.match(label))
        assertEquals("跳过 2", SkipRule.buttonLabel("2", "跳过"))
        assertEquals("跳过 2", SkipRule.withCountdown("跳过", "2"))
        assertEquals("2 跳过登录", SkipRule.buttonLabel("2 跳过登录", "2跳过"))
    }

    @Test fun crossGlyphsRemainWeakInsteadOfBecomingUnconditionalSkipActions() {
        for (label in listOf("×", "✕", "✖", "╳", "✗", "❌", "Ｘ", "ｘ")) {
            assertTrue(label, SkipRule.isCloseIcon(label))
            assertEquals(label, SkipRule.WEAK, SkipRule.match(label))
            assertFalse(label, JumpAdRule.isAdSkip(label))
        }
        for (label in listOf("X设置", "关闭", "不感兴趣", "×2", "下一步"))
            assertFalse(label, SkipRule.isCloseIcon(label))
    }

    @Test fun unlabeledIconsNeedAnExplicitAdCloseResourceName() {
        for (entry in listOf("splash_close_btn", "ad_close", "adCloseButton", "tt_splash_skip_btn", "gdt_ad_dismiss"))
            assertTrue(entry, SkipRule.isAdCloseResourceId("example.app:id/$entry"))
        for (entry in listOf("close", "toolbar_close", "dialog_close", "ad_settings", "download", "disclose", "splash_image", "ad_skip"))
            assertFalse(entry, SkipRule.isAdCloseResourceId("example.app:id/$entry"))
        assertFalse(SkipRule.isAdCloseResourceId(null))
        assertFalse(SkipRule.isAdCloseResourceId("splash_close_btn"))
    }

    @Test fun spacedAndInvisibleSkipLabelsRetainExplicitCountdownEvidence() {
        for (label in listOf("跳 过 5秒", "跳\n过 3s", "跳\u200B过 5秒", "跳过\u2066 5s\u2069")) {
            assertEquals(label, SkipRule.STRONG, SkipRule.match(label))
            assertTrue(label, JumpAdRule.isAdSkip(label))
        }
        assertEquals(SkipRule.STRONG, SkipRule.match("跳 过"))
        assertFalse(JumpAdRule.isAdSkip("跳 过"))
    }

    @Test fun standaloneAdPrefixAndEnglishTimerRemainAnchoredActions() {
        for (label in listOf("广告 | 跳过 5秒", "广告丨跳过", "Ad | Skip 5s", "Advertisement Skip", "Skip ad 5s", "SKIP AD 5")) {
            assertEquals(label, SkipRule.STRONG, SkipRule.match(label))
            assertTrue(label, JumpAdRule.isAdSkip(label))
        }
        for (label in listOf("广告设置 | 跳过", "广告 | 跳过登录", "广告 | 5秒后可跳过", "Skip ad in 5s", "广告 | 关闭", "跳 过 教程"))
            assertEquals(label, SkipRule.NONE, SkipRule.match(label))
    }

    @Test fun adMarkerInTextDoesNotHideTheActualSkipDescription() {
        assertEquals("跳过 5秒", SkipRule.buttonLabel("广告", "跳过 5秒"))
        assertEquals("Skip ad 5s", SkipRule.buttonLabel("AD", "Skip ad 5s"))
        assertEquals("跳过登录", SkipRule.buttonLabel("跳过登录", "跳过广告"))
        assertEquals("普通页面", SkipRule.buttonLabel("普通页面", "跳过广告"))
    }

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

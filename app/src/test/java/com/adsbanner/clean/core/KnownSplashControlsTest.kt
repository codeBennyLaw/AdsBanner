package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class KnownSplashControlsTest {
    private val button = SkipTargetPolicy.Bounds(740, 1840, 940, 1940)
    private val pkg = "tv.danmaku.bili"

    @Test fun theFirstFrameCannotUseKnownIdsBeforeItsActivityIsVerified() {
        assertFalse(KnownSplashControls.matches(pkg, "", "$pkg:id/count_down", "跳过3s", button, 1000, 2000))
        assertNull(KnownSplashControls.normalizeLabel(pkg, "", "$pkg:id/count_down", "3", button, 1000, 2000))
        assertFalse(KnownSplashControls.allowsControlActivity(pkg, "com.bilibili.app.preferences.BiliPreferencesActivity"))
        assertTrue(KnownSplashControls.allowsControlActivity(pkg, "$pkg.MainActivityV2"))
    }

    @Test fun bilibiliPlainSkipUsesItsControlIdInsteadOfAKeywordSearch() {
        for (id in KnownSplashControls.queryIds(pkg)) {
            assertTrue(id, KnownSplashControls.matches(pkg, "$pkg.MainActivityV2", id, "跳过", button, 1000, 2000))
            assertFalse(id, KnownSplashControls.matches("other.app", "other.app.Main", id, "跳过", button, 1000, 2000))
        }
        assertFalse(KnownSplashControls.matches(pkg, "$pkg.MainActivityV2", "$pkg:id/search", "跳过广告", button, 1000, 2000))
    }

    @Test fun knownCountersCanExposeOnlyATimerOrNoText() {
        val id = "$pkg:id/count_down"
        assertEquals("跳过 3s", KnownSplashControls.normalizeLabel(pkg, "$pkg.MainActivityV2", id, "3s", button, 1000, 2000))
        assertEquals("跳过 2", KnownSplashControls.normalizeLabel(pkg, "$pkg.MainActivityV2", id, "2", button, 1000, 2000))
        assertEquals("跳过", KnownSplashControls.normalizeLabel(pkg, "$pkg.MainActivityV2", id, "", button, 1000, 2000))
        assertNull(KnownSplashControls.normalizeLabel(pkg, "$pkg.MainActivityV2", "$pkg:id/search", "3s", button, 1000, 2000))
        assertNull(KnownSplashControls.normalizeLabel(pkg, "$pkg.MainActivityV2", id, "3秒后跳过", button, 1000, 2000))
    }

    @Test fun preferencesAndSearchPagesCannotUseTheKnownSplashShortcut() {
        for (activity in listOf("com.bilibili.app.preferences.BiliPreferencesActivity", "com.bilibili.search2.main.BiliMainSearchActivity"))
            assertFalse(activity, KnownSplashControls.matches(pkg, activity, "$pkg:id/skip", "跳过", button, 1000, 2000))
    }

    @Test fun knownIdsDoNotAuthorizeOtherActionsOrExpiredCountdowns() {
        for (label in listOf("跳过片头", "跳过登录", "跳过广告设置", "跳过0s", "下一步"))
            assertFalse(label, KnownSplashControls.matches(pkg, "$pkg.MainActivityV2", "$pkg:id/skip", label, button, 1000, 2000))
        assertNull(KnownSplashControls.normalizeLabel(pkg, "$pkg.MainActivityV2", "$pkg:id/count_down", "0", button, 1000, 2000))
    }

    @Test fun knownControlsStillRejectWholePageAndOffscreenBounds() {
        assertFalse(KnownSplashControls.matches(pkg, "$pkg.MainActivityV2", "$pkg:id/skip", "跳过",
            SkipTargetPolicy.Bounds(0, 0, 1000, 2000), 1000, 2000))
        assertFalse(KnownSplashControls.matches(pkg, "$pkg.MainActivityV2", "$pkg:id/skip", "跳过", button.copy(right = 1001), 1000, 2000))
    }

    @Test fun chaoxingDedicatedSplashPageCanSkipWithoutWaitingForATimerTick() {
        assertTrue(KnownSplashControls.matches("com.chaoxing.mobile", "com.chaoxing.mobile.activity.SplashActivity", null, "跳过2s", button, 1000, 2000))
        assertFalse(KnownSplashControls.matches("com.chaoxing.mobile", "com.chaoxing.mobile.MainActivity", null, "跳过广告", button, 1000, 2000))
        assertFalse(KnownSplashControls.matches("other.app", "com.chaoxing.mobile.activity.SplashActivity", null, "跳过2s", button, 1000, 2000))
    }

    @Test fun bilibiliHotSplashIsDistinctFromOrdinaryMainAndVideoPages() {
        assertTrue(KnownSplashControls.isDedicatedActivity(pkg, "$pkg.ui.splash.ad.page.HotSplashActivity"))
        assertFalse(KnownSplashControls.isDedicatedActivity(pkg, "$pkg.MainActivityV2"))
        assertFalse(KnownSplashControls.isDedicatedActivity(pkg, "com.bilibili.video.videodetail.VideoDetailsActivity"))
        assertTrue(KnownSplashControls.isMainActivity(pkg, "$pkg.MainActivityV2"))
    }

    @Test fun tabletBilibiliControlsUseTheTabletPackageNamespace() {
        val hd = "tv.danmaku.bilibilihd"
        assertTrue(KnownSplashControls.matches(hd, "$pkg.MainActivityV2", "$hd:id/count_down", "跳过3s", button, 1000, 2000))
        assertFalse(KnownSplashControls.isControlId(hd, "$pkg:id/count_down"))
    }
}

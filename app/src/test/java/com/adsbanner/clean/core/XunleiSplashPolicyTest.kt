package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class XunleiSplashPolicyTest {
    private val pkg = "com.xunlei.downloadprovider"
    private val activity = "$pkg.launch.LaunchActivity"
    private val target = SkipTargetPolicy.Bounds(947, 146, 1201, 247)
    private val marker = SkipTargetPolicy.Bounds(476, 104, 787, 175)

    private fun matches(text: String = "跳过", markerText: String = "会员去广告 >",
        button: SkipTargetPolicy.Bounds = target, pill: SkipTargetPolicy.Bounds = marker,
        page: String = activity, app: String = pkg, id: String? = null): Boolean =
        XunleiSplashPolicy.matches(app, page, text, id, button, markerText, pill, 1264, 2776)

    @Test fun screenshotPairNeedsNeitherACountdownNorAResourceId() {
        assertTrue(matches())
        assertTrue(matches(page = "$pkg.frame.MainTabActivity"))
        assertFalse(SplashSkipPolicy.hasEvidence("跳过", null, nearbyAd = false))
        assertFalse(SplashSkipPolicy.markerNearTarget("会员去广告 >", marker, target, 1264, 2776))
    }

    @Test fun onlyVerifiedXunleiLaunchAndMainWindowsCanUseThePair() {
        for (page in listOf("", "$pkg.search.ui.search.SearchOperateActivity", "$pkg.settings.SettingsActivity",
            "$pkg.download.taskdetails.newui.DownloadDetailsActivity", "com.bytedance.sdk.openadsdk.stub.activity.Stub_Standard_Portrait_Activity"))
            assertFalse(page, matches(page = page))
        assertFalse(matches(app = "other.app"))
    }

    @Test fun membershipTextIsNotAnAdMarkerForOtherAppsOrOrdinaryInstructions() {
        for (text in listOf("会员去广告", "会员去广告 >", "会 员 去 广 告 ›"))
            assertTrue(text, matches(markerText = text))
        for (text in listOf("广告", "会员免广告", "如何会员去广告", "会员去广告设置", "会员去广告说明", "点击会员去广告"))
            assertFalse(text, matches(markerText = text))
        assertFalse(XunleiSplashPolicy.isMemberAdMarker("other.app", "会员去广告"))
    }

    @Test fun aFarBottomAdOrOversizedArticleCannotAuthorizeTheSkip() {
        assertFalse(matches(markerText = "广告", pill = SkipTargetPolicy.Bounds(40, 2230, 120, 2280)))
        assertFalse(matches(pill = marker.copy(top = 1400, bottom = 1471)))
        assertFalse(matches(pill = SkipTargetPolicy.Bounds(0, 0, 1264, 1000)))
        assertFalse(matches(pill = marker.copy(right = target.right)))
        assertFalse(matches(pill = marker.copy(top = 2770, bottom = 2841)))
    }

    @Test fun onlyTheCompactUpperRightSkipIsATarget() {
        for (text in listOf("会员去广告", "点击此处", "跳过广告", "跳过登录", "关闭", "跳过0s"))
            assertFalse(text, matches(text = text))
        assertFalse(matches(button = target.copy(top = 1200, bottom = 1301)))
        assertFalse(matches(button = target.copy(left = 20, right = 274)))
        assertFalse(matches(button = SkipTargetPolicy.Bounds(0, 0, 1264, 2776)))
        assertFalse(matches(button = target.copy(right = 1300)))
    }

    @Test fun SearchAndSettingsIdsRemainExcludedEvenOnTheMainActivity() {
        for (id in listOf("$pkg:id/search", "$pkg:id/title", "$pkg:id/ad_skip_switch", "$pkg:id/skip_ad_settings"))
            assertFalse(id, matches(id = id))
    }
}

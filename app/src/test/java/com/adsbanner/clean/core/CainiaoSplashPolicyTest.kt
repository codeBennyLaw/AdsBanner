package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class CainiaoSplashPolicyTest {
    private val pkg = CainiaoSplashPolicy.PACKAGE
    private val width = 1264
    private val height = 2776
    private val circle = SkipTargetPolicy.Bounds(1114, 126, 1218, 230)
    private val ad = SkipTargetPolicy.Bounds(1110, 2320, 1220, 2360)
    private val container = SkipTargetPolicy.Bounds(0, 70, 1264, 2400)

    @Test fun currentDeviceMsSdkHasAnActionlessCircleAndSeparateAdTag() {
        val target = SkipTargetPolicy.Bounds(1112, 126, 1219, 233)
        val tag = SkipTargetPolicy.Bounds(1112, 2299, 1264, 2384)
        fun matches(id: String? = CainiaoSplashPolicy.MS_SKIP_ID, parentId: String? = CainiaoSplashPolicy.MS_SKIP_CONTAINER_ID,
            label: String = "", bounds: SkipTargetPolicy.Bounds = target) = CainiaoSplashPolicy.namedSdkTarget(pkg,
            "android.view.View", id, label, "android.widget.RelativeLayout", parentId, bounds, target, 1264, 2736)
        assertTrue(matches())
        assertFalse(matches(id = "$pkg:id/ms_splash_image"))
        assertFalse(matches(parentId = "$pkg:id/layout_root"))
        assertFalse(matches(label = "广告设置"))
        assertFalse(matches(bounds = target.copy(left = 800)))
        assertTrue(CainiaoSplashPolicy.namedSdkMarker(pkg, "android.widget.FrameLayout", CainiaoSplashPolicy.MS_AD_TAG_ID, tag, target, 1264, 2736))
        assertFalse(CainiaoSplashPolicy.namedSdkMarker(pkg, "android.widget.ImageView", CainiaoSplashPolicy.MS_AD_TAG_ID, tag, target, 1264, 2736))
        assertFalse(CainiaoSplashPolicy.namedSdkMarker(pkg, "android.widget.FrameLayout", "$pkg:id/ad_tag", tag, target, 1264, 2736))
        assertFalse(CainiaoSplashPolicy.namedSdkMarker(pkg, "android.widget.FrameLayout", CainiaoSplashPolicy.MS_AD_TAG_ID, tag.copy(top = 100), target, 1264, 2736))
        assertFalse(CainiaoSplashPolicy.namedSdkTarget("other.app", "android.view.View", CainiaoSplashPolicy.MS_SKIP_ID, "",
            "android.widget.RelativeLayout", CainiaoSplashPolicy.MS_SKIP_CONTAINER_ID, target, target, 1264, 2736))
    }

    @Test fun onlyTheExactAppOwnedFullSplashContainerSuppliesEvidence() {
        assertTrue(CainiaoSplashPolicy.isContainer(pkg, CainiaoSplashPolicy.CONTAINER_ID, container, width, height))
        assertFalse(CainiaoSplashPolicy.isContainer("other.app", CainiaoSplashPolicy.CONTAINER_ID, container, width, height))
        for (id in listOf("$pkg:id/layout_root", "$pkg:id/splash_container", "other.app:id/fl_third_splash_container"))
            assertFalse(id, CainiaoSplashPolicy.isContainer(pkg, id, container, width, height))
        assertFalse(CainiaoSplashPolicy.isContainer(pkg, CainiaoSplashPolicy.CONTAINER_ID, container.copy(bottom = 800), width, height))
        assertFalse(CainiaoSplashPolicy.isContainer(pkg, CainiaoSplashPolicy.CONTAINER_ID, container.copy(right = 1300), width, height))
    }

    @Test fun aPlainUpperRightSkipNeedsNoTextualTimerInsideTheVerifiedContainer() {
        for (label in listOf("跳过", "跳 过", "Skip"))
            assertTrue(label, CainiaoSplashPolicy.acceptsTarget(pkg, label, null, circle, width, height))
        for (label in listOf("摇一摇", "点击此处", "跳过登录", "关闭", "跳过0s"))
            assertFalse(label, CainiaoSplashPolicy.acceptsTarget(pkg, label, null, circle, width, height))
        for (id in listOf("$pkg:id/search", "$pkg:id/title", "$pkg:id/ad_skip_settings"))
            assertFalse(id, CainiaoSplashPolicy.acceptsTarget(pkg, "跳过", id, circle, width, height))
        assertFalse(CainiaoSplashPolicy.acceptsTarget("other.app", "跳过", null, circle, width, height))
    }

    @Test fun lowerLeftRequiresAnExplicitCountdownOnADedicatedCompactTarget() {
        // Approximate pixel bounds from the reported screenshot, for geometry only.
        val skip = SkipTargetPolicy.Bounds(9, 2150, 174, 2237)
        assertTrue(CainiaoSplashPolicy.acceptsTarget(pkg, "跳过 2", null, skip, width, 2736))
        assertTrue(CainiaoSplashPolicy.adLabel(pkg, "广告", ad, skip, width, 2736))
        for (label in listOf("跳过", "跳过 0", "跳过 11", "跳过登录 2", "滑动或点击有惊喜"))
            assertFalse(label, CainiaoSplashPolicy.acceptsTarget(pkg, label, null, skip, width, 2736))
        assertFalse(CainiaoSplashPolicy.acceptsTarget(pkg, "跳过 2", "$pkg:id/search", skip, width, 2736))
        assertFalse(CainiaoSplashPolicy.acceptsTarget("other.app", "跳过 2", null, skip, width, 2736))
        assertFalse(CainiaoSplashPolicy.acceptsTarget(pkg, "跳过 2", null, skip.copy(right = 900), width, 2736))
        assertFalse(CainiaoSplashPolicy.acceptsTarget(pkg, "跳过 2", null, skip.copy(top = 1600, bottom = 1687), width, 2736))
        assertFalse(CainiaoSplashPolicy.adLabel(pkg, "广告", skip, skip, width, 2736))
        assertFalse(CainiaoSplashPolicy.adLabel(pkg, "广告设置", ad, skip, width, 2736))
    }

    @Test fun anUnlabeledCanvasControlMustHaveCircleSizeAndAnActualAdLabel() {
        assertTrue(CainiaoSplashPolicy.unlabeledBounds("android.view.View", circle, width, height))
        for (label in listOf("AM广告", "AM 广告", "广告"))
            assertTrue(label, CainiaoSplashPolicy.adLabel(pkg, label, ad, circle, width, height))
        for (label in listOf("AM广告设置", "AM广告说明", "广告权益", "会员去广告"))
            assertFalse(label, CainiaoSplashPolicy.adLabel(pkg, label, ad, circle, width, height))
        assertFalse(CainiaoSplashPolicy.adLabel(pkg, "AM广告", ad.copy(top = 100, bottom = 140), circle, width, height))
        assertFalse(CainiaoSplashPolicy.adLabel(pkg, "AM广告", ad.copy(left = 20, right = 130), circle, width, height))
    }

    @Test fun adChoicesImagesShakeButtonsAndOrdinaryPagePositionsAreNotCanvasSkips() {
        assertFalse(CainiaoSplashPolicy.unlabeledBounds("android.widget.ImageView", circle, width, height))
        assertFalse(CainiaoSplashPolicy.unlabeledBounds("android.view.View", SkipTargetPolicy.Bounds(1190, 72, 1204, 86), width, height))
        assertFalse(CainiaoSplashPolicy.unlabeledBounds("android.view.View", circle.copy(top = 40, bottom = 144), width, height))
        assertFalse(CainiaoSplashPolicy.unlabeledBounds("android.view.View", circle.copy(top = 1700, bottom = 1804), width, height))
        assertFalse(CainiaoSplashPolicy.unlabeledBounds("android.view.View", circle.copy(left = 800), width, height))
    }

    @Test fun anEmptySdkBrandingNodeStillNeedsItsOwnClassAndLowerEdgeBounds() {
        assertTrue(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "", ad, circle, width, height))
        for (label in listOf("普通文字", "会员去广告", "AM广告设置", "广告权益"))
            assertFalse(label, CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", label, ad, circle, width, height))
        for (className in listOf("android.view.View", "android.widget.ImageView", "sdk.TextView", null))
            assertFalse(className, CainiaoSplashPolicy.emptySdkMarker(pkg, className, "", ad, circle, width, height))
        assertFalse(CainiaoSplashPolicy.emptySdkMarker("other.app", "android.widget.TextView", "", ad, circle, width, height))
        assertFalse(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "", ad.copy(left = 500, right = 610), circle, width, height))
        assertFalse(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "", ad.copy(top = 70, bottom = 110), circle, width, height))
        assertFalse(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "", ad.copy(right = ad.left), circle, width, height))
    }

    @Test fun originalPublicSnapshotsExposeTheEmptySdkMarkerOnTheLeft() {
        // Unmodified geometry from the two snapshots linked by the upstream Cainiao rule.
        val snapshots = listOf(
            intArrayOf(1080, 2400, 912, 138, 1032, 258, 60, 1950, 156, 1992),
            intArrayOf(1440, 3200, 1244, 176, 1384, 316, 70, 2619, 182, 2668)
        )
        for (s in snapshots) {
            val target = SkipTargetPolicy.Bounds(s[2], s[3], s[4], s[5])
            val marker = SkipTargetPolicy.Bounds(s[6], s[7], s[8], s[9])
            assertTrue(CainiaoSplashPolicy.unlabeledBounds("android.view.View", target, s[0], s[1]))
            assertTrue(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "", marker, target, s[0], s[1]))
            assertFalse(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "广告设置", marker, target, s[0], s[1]))
            assertFalse(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.view.View", "", marker, target, s[0], s[1]))
            assertFalse(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "", marker.copy(top = 0), target, s[0], s[1]))
            assertFalse(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "", marker.copy(left = -1), target, s[0], s[1]))
        }
    }

    @Test fun aCanvasRecreationUsesTheSameWindowWideTouchKey() {
        val tracker = SplashCountdownTracker()
        val key = CainiaoSplashPolicy.gestureKey(width, height)
        assertTrue(tracker.canAttempt(20, key))
        tracker.consume(20, "跳过", key, 1000)
        assertFalse(tracker.canAttempt(20, CainiaoSplashPolicy.gestureKey(width, height)))
        assertTrue(tracker.canAttempt(21, key))
    }

    @Test fun aFullWidthSdkFooterIsNotConfusedWithAnAdImageOrAnOffscreenRow() {
        val footer = ad.copy(left = 0, right = width)
        assertTrue(CainiaoSplashPolicy.adLabel(pkg, "AM广告", footer, circle, width, height))
        assertTrue(CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "", footer, circle, width, height))
        for (bounds in listOf(footer.copy(top = 1400), footer.copy(left = -1), footer.copy(right = width + 1),
                footer.copy(right = width / 2), footer.copy(top = 80, bottom = 120))) {
            assertFalse(bounds.toString(), CainiaoSplashPolicy.adLabel(pkg, "AM广告", bounds, circle, width, height))
            assertFalse(bounds.toString(), CainiaoSplashPolicy.emptySdkMarker(pkg, "android.widget.TextView", "", bounds, circle, width, height))
        }
        assertFalse(CainiaoSplashPolicy.adLabel(pkg, "广告权益", footer, circle, width, height))
    }
}

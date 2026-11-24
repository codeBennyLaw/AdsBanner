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

    @Test fun anUnlabeledCanvasControlMustHaveCircleSizeAndAnActualTrailingAdLabel() {
        assertTrue(CainiaoSplashPolicy.unlabeledBounds("android.view.View", circle, width, height))
        for (label in listOf("AM广告", "AM 广告", "广告"))
            assertTrue(label, CainiaoSplashPolicy.trailingAdLabel(pkg, label, ad, circle, width, height))
        for (label in listOf("AM广告设置", "AM广告说明", "广告权益", "会员去广告"))
            assertFalse(label, CainiaoSplashPolicy.trailingAdLabel(pkg, label, ad, circle, width, height))
        assertFalse(CainiaoSplashPolicy.trailingAdLabel(pkg, "AM广告", ad.copy(top = 100, bottom = 140), circle, width, height))
        assertFalse(CainiaoSplashPolicy.trailingAdLabel(pkg, "AM广告", ad.copy(left = 20, right = 130), circle, width, height))
    }

    @Test fun adChoicesImagesShakeButtonsAndOrdinaryPagePositionsAreNotCanvasSkips() {
        assertFalse(CainiaoSplashPolicy.unlabeledBounds("android.widget.ImageView", circle, width, height))
        assertFalse(CainiaoSplashPolicy.unlabeledBounds("android.view.View", SkipTargetPolicy.Bounds(1190, 72, 1204, 86), width, height))
        assertFalse(CainiaoSplashPolicy.unlabeledBounds("android.view.View", circle.copy(top = 1700, bottom = 1804), width, height))
        assertFalse(CainiaoSplashPolicy.unlabeledBounds("android.view.View", circle.copy(left = 800), width, height))
    }

    @Test fun aCanvasRecreationUsesTheSameWindowWideTouchKey() {
        val tracker = SplashCountdownTracker()
        val key = CainiaoSplashPolicy.gestureKey(width, height)
        assertTrue(tracker.canAttempt(20, key))
        tracker.consume(20, "跳过", key, 1000)
        assertFalse(tracker.canAttempt(20, CainiaoSplashPolicy.gestureKey(width, height)))
        assertTrue(tracker.canAttempt(21, key))
    }
}

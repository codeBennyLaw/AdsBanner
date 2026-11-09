package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SplashClosePolicyTest {
    private val cross = SkipTargetPolicy.Bounds(870, 20, 970, 100)
    private val marker = SkipTargetPolicy.Bounds(20, 90, 100, 130)

    @Test fun independentAdMarkerCanAuthorizeAnUpperCornerCross() {
        assertTrue(SplashClosePolicy.markerNearTarget("广告", marker, cross, 1000, 2000))
        assertTrue(SplashClosePolicy.markerNearTarget("AD 5s", marker, cross, 1000, 2000))
        assertTrue(SplashClosePolicy.markerNearTarget("广告", marker.copy(left = 850, right = 950),
            cross.copy(left = 20, right = 120), 1000, 2000))
    }

    @Test fun backgroundAdsOrSettingsCannotAuthorizeAnUnrelatedClose() {
        for (label in listOf("广告设置", "会员去广告", "10秒", "推荐广告内容"))
            assertFalse(label, SplashClosePolicy.markerNearTarget(label, marker, cross, 1000, 2000))
        assertFalse(SplashClosePolicy.markerNearTarget("广告", marker.copy(top = 800, bottom = 850), cross, 1000, 2000))
        assertFalse(SplashClosePolicy.markerNearTarget("广告", marker, cross.copy(top = 1000, bottom = 1080), 1000, 2000))
    }

    @Test fun theCrossCannotProvideItsOwnAdProofOrBecomeALargeContainer() {
        assertFalse(SplashClosePolicy.markerNearTarget("广告", cross, cross, 1000, 2000))
        assertFalse(SplashClosePolicy.markerNearTarget("广告", marker, cross.copy(left = 500), 1000, 2000))
        assertFalse(SplashClosePolicy.markerNearTarget("广告", marker, cross.copy(bottom = 300), 1000, 2000))
        assertFalse(SplashClosePolicy.markerNearTarget("广告", marker.copy(left = -1), cross, 1000, 2000))
    }
}

package com.adsbanner.clean.core

import kotlin.math.abs

/** A generic cross needs an independent ad label in the same band, not a page-wide keyword. */
internal object SplashClosePolicy {
    fun markerNearTarget(label: String, marker: SkipTargetPolicy.Bounds,
        target: SkipTargetPolicy.Bounds, width: Int, height: Int): Boolean =
        SkipRule.isStandaloneAdMarker(SkipRule.normalizedLabel(label)) &&
            SkipTargetPolicy.smallTarget(marker, width, height) &&
            target.width <= width * 0.24f && target.height <= height * 0.08f &&
            SkipTargetPolicy.score(SkipRule.WEAK, target, width, height, closeIcon = true) > 0 &&
            !intersects(marker, target) &&
            abs((marker.top.toLong() + marker.bottom) - (target.top.toLong() + target.bottom)) <=
                maxOf(height * 0.24f, target.height * 4f)

    private fun intersects(a: SkipTargetPolicy.Bounds, b: SkipTargetPolicy.Bounds): Boolean =
        a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
}

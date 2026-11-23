package com.adsbanner.clean.core

import kotlin.math.abs

/** Matching text alone cannot authorize a tap on a settings row, article or tutorial. */
internal object SplashSkipPolicy {
    fun actionable(hasClickAction: Boolean, editable: Boolean, checkable: Boolean, scrollable: Boolean): Boolean =
        hasClickAction && !editable && !checkable && !scrollable

    fun hasEvidence(label: String, viewId: String?, nearbyAd: Boolean): Boolean =
        SkipRule.match(label) == SkipRule.STRONG &&
            (JumpAdRule.hasSkipCountdown(label) || SkipRule.isAdSkipResourceId(viewId) || nearbyAd)

    /** An independent compact ad label must occupy the same vertical band as the control. */
    fun markerNearTarget(label: String, marker: SkipTargetPolicy.Bounds,
        target: SkipTargetPolicy.Bounds, width: Int, height: Int): Boolean =
        SkipRule.isStandaloneAdMarker(SkipRule.normalizedLabel(label)) &&
            SkipTargetPolicy.smallTarget(marker, width, height) &&
            SkipTargetPolicy.score(SkipRule.STRONG, target, width, height) > 0 &&
            !(marker.left < target.right && target.left < marker.right &&
                marker.top < target.bottom && target.top < marker.bottom) &&
            abs((marker.top.toLong() + marker.bottom) - (target.top.toLong() + target.bottom)) <=
                maxOf(height * 0.24f, target.height * 4f)
}

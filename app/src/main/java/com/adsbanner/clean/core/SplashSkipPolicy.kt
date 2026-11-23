package com.adsbanner.clean.core

import kotlin.math.abs

/** Matching text alone cannot authorize a tap on a settings row, article or tutorial. */
internal object SplashSkipPolicy {
    private val navigationContainers = setOf("ScrollView", "HorizontalScrollView", "NestedScrollView", "RecyclerView", "ListView", "AbsListView", "GridView", "ViewPager", "ViewPager2")

    fun isNavigationContainer(className: String?): Boolean = className?.substringAfterLast('.') in navigationContainers

    fun actionable(hasClickAction: Boolean, editable: Boolean, checkable: Boolean, scrollable: Boolean): Boolean =
        hasClickAction && !editable && !checkable && !scrollable

    fun hasEvidence(label: String, viewId: String?, nearbyAd: Boolean): Boolean =
        SkipRule.match(label) == SkipRule.STRONG &&
            (JumpAdRule.hasSkipCountdown(label) || SkipRule.isAdSkipResourceId(viewId) || nearbyAd)

    /** Touch-only controls need a live countdown, a compact right corner and the original launch session. */
    fun allowsCountdownFallback(label: String, viewId: String?, target: SkipTargetPolicy.Bounds,
        width: Int, height: Int, coldLaunch: Boolean, covered: Boolean, safeHierarchy: Boolean): Boolean =
        coldLaunch && !covered && safeHierarchy && !SkipRule.isOrdinaryResourceId(viewId) &&
            JumpAdRule.skipCountdownSeconds(label)?.let { it in 1..10 } == true &&
            target.width <= width * 0.24f && target.height <= height * 0.06f &&
            SkipTargetPolicy.score(SkipRule.STRONG, target, width, height) in setOf(90, 100)

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

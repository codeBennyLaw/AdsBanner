package com.adsbanner.clean.core

import kotlin.math.abs

/** Xunlei's unnumbered upper-right skip needs its separate membership ad-removal pill. */
internal object XunleiSplashPolicy {
    private const val PACKAGE = "com.xunlei.downloadprovider"
    private val activities = setOf("$PACKAGE.launch.LaunchActivity", "$PACKAGE.frame.MainTabActivity")
    private val membershipLabel = Regex("^会\\s*员\\s*去\\s*广\\s*告\\s*[>›»]?$")

    fun isMemberAdMarker(pkg: String, label: String): Boolean = pkg == PACKAGE &&
        label.length <= 20 && membershipLabel.matches(SkipRule.normalizedLabel(label))

    fun acceptsTarget(pkg: String, activity: String, label: String, viewId: String?,
        target: SkipTargetPolicy.Bounds, width: Int, height: Int): Boolean =
        pkg == PACKAGE && activity in activities && SkipRule.normalizedLabel(label) == "跳过" &&
            !SkipRule.isOrdinaryResourceId(viewId) && !SkipRule.isSearchResourceId(viewId) &&
            SkipTargetPolicy.score(SkipRule.STRONG, target, width, height) == 100 &&
            target.width <= width * 0.32f && target.height <= height * 0.08f &&
            target.left >= width * 0.55f && target.bottom <= height * 0.25f

    /** The caller verifies both nodes belong to the same live application window. */
    fun matches(pkg: String, activity: String, label: String, viewId: String?,
        target: SkipTargetPolicy.Bounds, markerLabel: String, marker: SkipTargetPolicy.Bounds,
        width: Int, height: Int): Boolean = acceptsTarget(pkg, activity, label, viewId, target, width, height) &&
        isMemberAdMarker(pkg, markerLabel) && SkipTargetPolicy.smallTarget(marker, width, height) &&
        marker.width <= width * 0.45f && marker.height <= height * 0.08f &&
        marker.bottom <= height * 0.25f && marker.right <= target.left &&
        abs((marker.top.toLong() + marker.bottom) - (target.top.toLong() + target.bottom)) <=
            maxOf(height * 0.16f, target.height * 4f)
}

package com.adsbanner.clean.core

/** Cainiao's third-party splash container identifies a plain or canvas-drawn skip control. */
internal object CainiaoSplashPolicy {
    const val PACKAGE = "com.cainiao.wireless"
    const val CONTAINER_ID = "$PACKAGE:id/fl_third_splash_container"
    private val brandedMarker = Regex("^AM\\s*广告$", RegexOption.IGNORE_CASE)

    fun isPackage(pkg: String): Boolean = pkg == PACKAGE

    fun isContainer(pkg: String, id: String?, bounds: SkipTargetPolicy.Bounds, width: Int, height: Int): Boolean =
        isPackage(pkg) && id == CONTAINER_ID && width > 0 && height > 0 &&
            bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height &&
            bounds.width >= width * 0.75f && bounds.height >= height * 0.60f

    fun targetBounds(bounds: SkipTargetPolicy.Bounds, width: Int, height: Int): Boolean =
        SkipTargetPolicy.score(SkipRule.STRONG, bounds, width, height) == 100 &&
            bounds.left >= width * 0.60f && bounds.bottom <= height * 0.20f &&
            bounds.width <= width * 0.24f && bounds.height <= height * 0.09f

    fun acceptsTarget(pkg: String, label: String, id: String?, bounds: SkipTargetPolicy.Bounds,
        width: Int, height: Int): Boolean = isPackage(pkg) &&
        SkipRule.normalizedLabel(label).let { it == "跳过" || it.equals("skip", ignoreCase = true) } &&
        !SkipRule.isOrdinaryResourceId(id) && !SkipRule.isSearchResourceId(id) && targetBounds(bounds, width, height)

    /** Do not confuse a tiny ad-choice dot or a large shake/CTA surface with the circular control. */
    fun unlabeledBounds(className: String?, bounds: SkipTargetPolicy.Bounds, width: Int, height: Int): Boolean =
        className?.substringAfterLast('.') == "View" && targetBounds(bounds, width, height) &&
            bounds.width >= width * 0.04f && bounds.height >= height * 0.015f &&
            bounds.width >= bounds.height * 0.70f && bounds.width <= bounds.height * 1.50f

    fun trailingAdLabel(pkg: String, label: String, marker: SkipTargetPolicy.Bounds,
        target: SkipTargetPolicy.Bounds, width: Int, height: Int): Boolean = isPackage(pkg) &&
        (brandedMarker.matches(SkipRule.normalizedLabel(label)) || SkipRule.isStandaloneAdMarker(SkipRule.normalizedLabel(label))) &&
        SkipTargetPolicy.smallTarget(marker, width, height) && marker.width <= width * 0.25f &&
        marker.height <= height * 0.045f && marker.left >= width * 0.60f &&
        marker.top >= height * 0.55f && marker.bottom <= height * 0.96f && marker.top > target.bottom

    /** One accepted touch in the verified container/window, including when its canvas node is recreated. */
    fun gestureKey(width: Int, height: Int): SkipTargetPolicy.Bounds = SkipTargetPolicy.Bounds(0, 0, width, height)
}

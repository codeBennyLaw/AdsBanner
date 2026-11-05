package com.adsbanner.clean.core

internal data class LocalAdRule(
    val id: String,
    val name: String,
    val packageName: String,
    val viewId: String,
    val activityPrefix: String = "",
    val text: String = "",
    val region: Region = Region.TOP,
    val maxAgeMs: Long = 10_000L,
    val enabled: Boolean = true
) {
    enum class Region { TOP, TOP_RIGHT, PAGE }

    fun appliesTo(pkg: String, activity: String, ageMs: Long): Boolean = enabled && packageName == pkg &&
        !SystemAppSafety.isProtected(pkg) && !SkipRule.isEmbeddedPage(pkg, activity) &&
        ageMs in 0..maxAgeMs && (activityPrefix.isEmpty() || activity.startsWith(activityPrefix))

    fun matches(pkg: String, activity: String, ageMs: Long, resourceId: String?, label: String,
        bounds: SkipTargetPolicy.Bounds, width: Int, height: Int): Boolean {
        if (!appliesTo(pkg, activity, ageMs) || resourceId != viewId || (text.isNotEmpty() && label.trim() != text)) return false
        if (!SkipTargetPolicy.smallTarget(bounds, width, height)) return false
        val x = (bounds.left.toLong() + bounds.right) / 2f
        val y = (bounds.top.toLong() + bounds.bottom) / 2f
        return when (region) {
            Region.TOP -> y < height * 0.30f
            Region.TOP_RIGHT -> x > width * 0.55f && y < height * 0.30f
            Region.PAGE -> activityPrefix.isNotEmpty()
        }
    }
}

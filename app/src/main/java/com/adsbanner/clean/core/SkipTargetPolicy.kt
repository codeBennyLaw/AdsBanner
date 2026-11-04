package com.adsbanner.clean.core

/** Geometry is checked both while matching and immediately before acting on a refreshed node. */
internal object SkipTargetPolicy {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val area get() = width.toLong() * height
        fun contains(other: Bounds) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
    }

    fun score(strength: Int, target: Bounds, width: Int, height: Int): Int {
        if (!smallTarget(target, width, height)) return 0
        val x = (target.left.toLong() + target.right) / 2f
        val y = (target.top.toLong() + target.bottom) / 2f
        val topRight = x > width * 0.55f && y < height * 0.30f
        // Blank space below an ad may raise its skip pill into the middle of the screen.
        // Prefer familiar corners, but allow compact strong targets anywhere with live ad evidence.
        val compact = target.width <= width * 0.32f && target.height <= height * 0.08f
        val bottomRight = x > width * 0.70f && y > height * 0.75f && target.right >= width * 0.90f && compact
        return when {
            strength == SkipRule.STRONG && topRight -> 100
            strength == SkipRule.STRONG && bottomRight -> 90
            strength == SkipRule.STRONG && y < height * 0.25f -> 85
            strength == SkipRule.STRONG && compact -> 80
            strength == SkipRule.WEAK && topRight -> 60
            else -> 0
        }
    }

    fun smallTarget(target: Bounds, width: Int, height: Int): Boolean = width > 0 && height > 0 &&
        target.width > 0 && target.height > 0 && target.left >= 0 && target.top >= 0 &&
        target.right <= width && target.bottom <= height &&
        target.width <= width * 0.6f && target.height <= height * 0.12f

    fun preciseParent(target: Bounds, parent: Bounds): Boolean = target.area > 0 && parent.area > 0 &&
        parent.contains(target) && parent.area <= target.area * 2
}

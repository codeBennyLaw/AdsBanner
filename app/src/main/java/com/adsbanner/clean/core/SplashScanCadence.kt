package com.adsbanner.clean.core

/** Events wake cheap queries quickly; idle polling and full-tree work have separate budgets. */
internal class SplashScanCadence {
    private var lastQueryAt: Long? = null
    private var lastTreeAt: Long? = null

    fun reset() { lastQueryAt = null; lastTreeAt = null }
    fun queryDelay(now: Long): Long = lastQueryAt?.let { (32L - (now - it)).coerceAtLeast(0) } ?: 0
    fun queried(now: Long) { lastQueryAt = now }

    fun takeTreePass(now: Long): Boolean {
        if (lastTreeAt?.let { now - it < 400L } == true) return false
        lastTreeAt = now
        return true
    }

    fun pollDelay(ageMs: Long, covered: Boolean): Long = when {
        covered -> 600L
        ageMs < 600L -> 80L
        ageMs < 2_000L -> 120L
        ageMs < 4_000L -> 240L
        else -> 600L
    }
}

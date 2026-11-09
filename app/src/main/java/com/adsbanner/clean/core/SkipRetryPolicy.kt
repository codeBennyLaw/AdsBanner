package com.adsbanner.clean.core

/** A temporarily unresponsive control may recover; an unchanged failure must not be tapped forever. */
internal object SkipRetryPolicy {
    fun allows(failures: Int, failedAt: Long, now: Long): Boolean = when (failures) {
        1 -> now - failedAt >= 350L
        2 -> now - failedAt >= 700L
        else -> false
    }
}

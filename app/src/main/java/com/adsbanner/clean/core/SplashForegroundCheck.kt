package com.adsbanner.clean.core

/** Resolve a missing foreground event briefly, without trusting a background event's package. */
internal class SplashForegroundCheck {
    private var pending = false
    private var expectedPackage: String? = null
    private var until = 0L
    private var nextAt = 0L

    fun request(pkg: String?, now: Long) {
        if (!pending || now > until || (pkg != null && pkg != expectedPackage)) {
            expectedPackage = pkg
            until = now + 600L
        }
        pending = true
    }

    fun take(now: Long): Boolean {
        if (!pending || now > until || now < nextAt) return false
        nextAt = now + 100L
        return true
    }

    fun accepts(pkg: String): Boolean = expectedPackage == null || expectedPackage == pkg

    fun retryDelay(now: Long): Long? = if (pending && now < until) {
        (nextAt - now).coerceAtLeast(0L).coerceAtMost(until - now)
    } else null

    fun complete() { pending = false }

    fun clear() {
        complete()
        expectedPackage = null
        until = 0L
        nextAt = 0L
    }
}

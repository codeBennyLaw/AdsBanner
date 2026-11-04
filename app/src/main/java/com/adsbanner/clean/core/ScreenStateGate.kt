package com.adsbanner.clean.core

/** Screen broadcasts are hints: a missed unlock must not leave automation disabled indefinitely. */
internal class ScreenStateGate {
    var ready = true
        private set
    private var retryAt = 0L

    fun update(ready: Boolean) {
        this.ready = ready
        retryAt = 0L
    }

    fun allows(now: Long, windowChanged: Boolean, readState: () -> Boolean): Boolean {
        if (ready && !windowChanged) return true
        if (!windowChanged && now < retryAt) return false
        ready = readState()
        retryAt = if (ready) 0L else now + 250L
        return ready
    }
}

package com.adsbanner.clean.core

/** Real foreground boundaries may rearm a bounded scan; content changes alone never do. */
internal class SplashResumeTracker {
    enum class Entry { NONE, LAUNCH, WAKE, RETURN, ACTIVITY }

    private var packageName: String? = null
    private var activity: String? = null
    private var windowId: Int? = null
    private var screenHidden = false
    private var wakeUntil: Long? = null
    private var transientWindow = false
    private var nextWindowCheckAt = 0L

    fun clear() {
        packageName = null
        activity = null
        windowId = null
        screenHidden = false
        wakeUntil = null
        transientWindow = false
        nextWindowCheckAt = 0L
    }

    fun onScreenHidden() {
        screenHidden = true
        wakeUntil = null
        nextWindowCheckAt = 0L
    }

    fun onScreenReady(now: Long) {
        if (screenHidden && wakeUntil == null) wakeUntil = now + 15_000L
    }

    /** A chooser or another window of this app can return to the existing activity. */
    fun onTransientWindow(pkg: String? = null, id: Int? = null) {
        if (packageName != null && (pkg == null ||
                (pkg == packageName && id != null && id >= 0 && windowId != null && id != windowId))) {
            transientWindow = true
        }
    }

    fun takeWindowCheck(now: Long): Boolean {
        if ((!wakePending(now) && !transientWindow) || now < nextWindowCheckAt) return false
        nextWindowCheckAt = now + 100L
        return true
    }

    fun canReconcile(pkg: String, id: Int, now: Long): Boolean = wakePending(now) ||
        (transientWindow && pkg == packageName && (windowId == null || windowId == id))

    fun enter(pkg: String, declaredActivity: String?, id: Int?, now: Long): Entry {
        val changed = packageName != pkg
        val validId = id?.takeIf { it >= 0 }
        val result = when {
            changed -> Entry.LAUNCH
            wakePending(now) -> Entry.WAKE
            transientWindow -> Entry.RETURN
            declaredActivity != null && activity != null && declaredActivity != activity -> Entry.ACTIVITY
            validId != null && windowId != null && validId != windowId -> Entry.ACTIVITY
            else -> Entry.NONE
        }
        if (changed) { activity = null; windowId = null }
        packageName = pkg
        if (declaredActivity != null) activity = declaredActivity
        if (validId != null) windowId = validId
        screenHidden = false
        wakeUntil = null
        transientWindow = false
        nextWindowCheckAt = 0L
        return result
    }

    private fun wakePending(now: Long) = screenHidden && wakeUntil?.let { now <= it } == true
}

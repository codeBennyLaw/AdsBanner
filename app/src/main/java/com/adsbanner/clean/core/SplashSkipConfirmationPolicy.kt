package com.adsbanner.clean.core

/** Confirm an already submitted action without confusing its event with a new user tap. */
internal object SplashSkipConfirmationPolicy {
    const val RESULT_SETTLE_MS = 2_000L
    const val WINDOW_SETTLE_MS = 2_000L
    const val ABSENCE_MS = 200L

    /** Only retain result verification; this does not authorize a click or prove success. */
    fun canKeepSubmittedResult(startedUptime: Long, eventUptime: Long,
        targetWindowId: Int, eventWindowId: Int, relatedSource: Boolean, scrollReport: Boolean = false): Boolean =
        relatedSource && startedUptime > 0 && targetWindowId >= 0 && targetWindowId == eventWindowId &&
            eventUptime - startedUptime in 0L..(if (scrollReport) RESULT_SETTLE_MS else 300L)

    fun awaitWindowResult(elapsed: Long, sameWindow: Boolean): Boolean =
        !sameWindow && elapsed in 0L until WINDOW_SETTLE_MS

    /** An old window can remain listed while a verified new activity fully covers it. */
    fun originalWindowMayBeVisible(original: SkipTargetPolicy.Bounds?, current: SkipTargetPolicy.Bounds?,
        currentAbove: Boolean, activityChanged: Boolean): Boolean = original != null &&
        !(activityChanged && currentAbove && original.width > 0 && original.height > 0 &&
            current != null && current.contains(original))

    /** A new instance of the same Activity can replace the splash without changing its class name. */
    fun replacedRemovedWindow(original: SkipTargetPolicy.Bounds?, activeActivity: SkipTargetPolicy.Bounds?,
        originalListed: Boolean, originalNodeReadable: Boolean, screenWidth: Int, screenHeight: Int): Boolean {
        if (originalListed || originalNodeReadable || original == null || activeActivity == null) return false
        // A window can extend past the display during its entry animation. Compare its visible area,
        // using the last display pixel as the outer edge to account for window-bounds rounding.
        val visible = SkipTargetPolicy.Bounds(maxOf(0, original.left), maxOf(0, original.top),
            minOf(screenWidth - 1, original.right), minOf(screenHeight - 1, original.bottom))
        return visible.width > 0 && visible.height > 0 && activeActivity.contains(visible)
    }

    fun ownGestureParentClick(gesture: Boolean, startedUptime: Long, eventUptime: Long,
        target: SkipTargetPolicy.Bounds, source: SkipTargetPolicy.Bounds): Boolean =
        gesture && startedUptime > 0 && eventUptime - startedUptime in 0L..300L &&
            target.area > 0 && source.contains(target)

    /** A cached visible node in an old, hidden activity is not a visible advertisement. */
    fun targetMayRemain(matches: Boolean, nodeVisible: Boolean, sameWindow: Boolean,
        originalWindowVisible: Boolean, covered: Boolean, statusBarTarget: Boolean, activityReplacement: Boolean = false): Boolean =
        if (!sameWindow) originalWindowVisible || !activityReplacement else matches && (nodeVisible || covered || statusBarTarget)
}

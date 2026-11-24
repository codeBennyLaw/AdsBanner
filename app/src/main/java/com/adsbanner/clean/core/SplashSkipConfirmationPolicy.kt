package com.adsbanner.clean.core

/** Confirm an already submitted action without confusing its event with a new user tap. */
internal object SplashSkipConfirmationPolicy {
    const val GESTURE_SETTLE_MS = 1_000L
    const val ABSENCE_MS = 200L

    /** Only retain result verification; this does not authorize a click or prove success. */
    fun canKeepGestureResult(gesture: Boolean, startedUptime: Long, eventUptime: Long,
        targetWindowId: Int, eventWindowId: Int): Boolean = gesture && startedUptime > 0 &&
        targetWindowId >= 0 && targetWindowId == eventWindowId && eventUptime - startedUptime in 0L..300L

    fun ownGestureParentClick(gesture: Boolean, startedUptime: Long, eventUptime: Long,
        target: SkipTargetPolicy.Bounds, source: SkipTargetPolicy.Bounds): Boolean =
        gesture && startedUptime > 0 && eventUptime - startedUptime in 0L..300L &&
            target.area > 0 && source.contains(target)

    /** A cached visible node in an old, hidden activity is not a visible advertisement. */
    fun targetMayRemain(matches: Boolean, nodeVisible: Boolean, sameWindow: Boolean,
        originalWindowVisible: Boolean, covered: Boolean, statusBarTarget: Boolean, activityChanged: Boolean = false): Boolean =
        if (!sameWindow) originalWindowVisible || !activityChanged else matches && (nodeVisible || covered || statusBarTarget)
}

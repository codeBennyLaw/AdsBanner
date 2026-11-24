package com.adsbanner.clean.core

import kotlin.math.abs
import java.util.Locale

/** A static timer label cannot authorize a touch; require a real decrease at the same control. */
internal class SplashCountdownTracker {
    private data class Observation(
        val windowId: Int,
        val signature: String,
        var bounds: SkipTargetPolicy.Bounds,
        var seconds: Int,
        var tickAt: Long,
        var seenAt: Long,
        var confirmedAt: Long? = null,
        var consumed: Boolean = false
    )

    private val observations = ArrayList<Observation>(4)
    private val number = Regex("[0-9]{1,2}")
    private val whitespace = Regex("\\s+")

    fun clear() { observations.clear() }

    fun invalidateProofs() { observations.removeAll { !it.consumed } }

    fun canAttempt(windowId: Int, bounds: SkipTargetPolicy.Bounds): Boolean =
        observations.none { it.consumed && it.windowId == windowId && samePosition(it.bounds, bounds) } &&
            (observations.size < 8 || observations.any { !it.consumed })

    /** A static label gets a short burst, not faster polling for the whole launch window. */
    fun nextCheckDelay(now: Long): Long? = 80L.takeIf {
        observations.any { !it.consumed && it.seconds > 0 && now - it.tickAt in 0L..1_500L }
    }

    fun observe(windowId: Int, label: String, bounds: SkipTargetPolicy.Bounds, now: Long): Boolean {
        val seconds = JumpAdRule.skipCountdownSeconds(label) ?: return false
        if (windowId < 0 || seconds !in 0..10 || bounds.width <= 0 || bounds.height <= 0) return false
        val signature = signature(label)
        observations.removeAll { !it.consumed && now - it.seenAt > 3_000L }
        if (observations.any { it.consumed && it.windowId == windowId && samePosition(it.bounds, bounds) }) return false
        val previous = observations.firstOrNull { it.windowId == windowId && it.signature == signature }
        if (previous == null || !samePosition(previous.bounds, bounds)) {
            if (previous != null) observations.remove(previous)
            if (observations.size >= 8) {
                val disposable = observations.indexOfFirst { !it.consumed }
                if (disposable < 0) return false
                observations.removeAt(disposable)
            }
            observations.add(Observation(windowId, signature, bounds, seconds, now, now))
            return false
        }
        previous.seenAt = now
        previous.bounds = bounds
        if (seconds != previous.seconds) {
            val drop = previous.seconds - seconds
            val interval = now - previous.tickAt
            previous.confirmedAt = now.takeIf { seconds > 0 && drop in 1..3 && interval in 250L..3_000L }
            previous.seconds = seconds
            previous.tickAt = now
        }
        return !previous.consumed && seconds > 0 && previous.confirmedAt?.let { now - it in 0L..1_500L } == true
    }

    /** One accepted coordinate gesture per observed control, even if its timer node is recreated. */
    fun consume(windowId: Int, label: String, bounds: SkipTargetPolicy.Bounds, now: Long = 0L) {
        val signature = signature(label)
        val existing = observations.firstOrNull {
            it.windowId == windowId && it.signature == signature && samePosition(it.bounds, bounds)
        }
        if (existing != null) { existing.consumed = true; return }
        if (!canAttempt(windowId, bounds)) return
        if (observations.size >= 8) observations.removeAt(observations.indexOfFirst { !it.consumed })
        observations.add(Observation(windowId, signature, bounds, JumpAdRule.skipCountdownSeconds(label) ?: 0,
            now, now, consumed = true))
    }

    private fun signature(label: String): String = SkipRule.normalizedLabel(label)
        .replace(number, "#").replace(whitespace, "").lowercase(Locale.ROOT)

    private fun samePosition(a: SkipTargetPolicy.Bounds, b: SkipTargetPolicy.Bounds): Boolean =
        abs((a.left.toLong() + a.right) - (b.left.toLong() + b.right)) <= minOf(a.width, b.width) &&
            abs((a.top.toLong() + a.bottom) - (b.top.toLong() + b.bottom)) <= minOf(a.height, b.height) &&
            a.area <= b.area * 2 && b.area <= a.area * 2
}

package com.adsbanner.clean.core

/** 只对启动期间有广告证据、且没有主动操作的跨应用拉起生成返回请求。 */
internal class JumpGuardSession {
    data class Pending(
        val from: String, val target: String, val sourceEnteredAt: Long,
        val createdAt: Long, internal val generation: Int
    )

    var foregroundPackage: String? = null
        private set
    private var enteredAt = 0L
    private var adSeenAt: Long? = null
    private var systemNavigationAt: Long? = null
    private var generation = 0
    private val interactions = LinkedHashMap<String, Long>()
    private val leftAt = LinkedHashMap<String, Long>()

    fun reset() {
        foregroundPackage = null
        enteredAt = 0L
        adSeenAt = null
        systemNavigationAt = null
        interactions.clear()
        leftAt.clear()
        generation++
    }

    fun canObserveAd(pkg: String, now: Long, launchWindowMs: Long): Boolean =
        foregroundPackage == pkg && now - enteredAt in 0..launchWindowMs.coerceIn(0L, 10_000L) &&
            !interactedSince(pkg, enteredAt)

    fun observeAd(pkg: String, now: Long, launchWindowMs: Long) {
        if (canObserveAd(pkg, now, launchWindowMs)) adSeenAt = now
    }

    fun userInteraction(pkg: String, now: Long) {
        remember(interactions, pkg, now)
        if (foregroundPackage == pkg) adSeenAt = null
    }

    fun systemNavigation(now: Long) {
        systemNavigationAt = now
        adSeenAt = null
    }

    fun enter(
        pkg: String, now: Long, targetProtected: Boolean, sourceTrusted: Boolean,
        launchWindowMs: Long, evidenceWindowMs: Long
    ): Pending? {
        val from = foregroundPackage
        // 同一应用的弹窗、页面变化不重开启动时间窗。
        if (pkg == from) return null
        val sourceEnteredAt = enteredAt
        val recentAd = adSeenAt?.let { now - it in 0..evidenceWindowMs.coerceIn(0L, 3_000L) } == true
        val returning = leftAt[pkg]?.let { now - it in 0..5_000L } == true
        val suspicious = from != null && targetProtected && !sourceTrusted && !returning && recentAd &&
            canObserveAd(from, now, launchWindowMs) && !recentSystemNavigation(now) &&
            !interactedSince(pkg, sourceEnteredAt)
        from?.let { remember(leftAt, it, now) }
        foregroundPackage = pkg
        enteredAt = now
        adSeenAt = null
        // 系统导航保护只对应这次进入，不能顺带放行落地后新的广告拉起。
        systemNavigationAt = null
        generation++
        return if (suspicious) Pending(from!!, pkg, sourceEnteredAt, now, generation) else null
    }

    fun mayReturn(pending: Pending, now: Long): Boolean =
        pending.generation == generation && foregroundPackage == pending.target &&
            now - pending.createdAt in 0..1_200L && !recentSystemNavigation(now) &&
            !interactedSince(pending.from, pending.sourceEnteredAt) &&
            !interactedSince(pending.target, pending.sourceEnteredAt)

    private fun interactedSince(pkg: String, since: Long): Boolean =
        interactions[pkg]?.let { it >= since } == true

    private fun recentSystemNavigation(now: Long): Boolean =
        systemNavigationAt?.let { now - it in 0..3_000L } == true

    private fun remember(map: LinkedHashMap<String, Long>, pkg: String, now: Long) {
        map.remove(pkg)
        map[pkg] = now
        if (map.size > 64) map.remove(map.keys.first())
    }
}

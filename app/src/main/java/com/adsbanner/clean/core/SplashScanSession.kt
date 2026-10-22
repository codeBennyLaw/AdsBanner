package com.adsbanner.clean.core

/** 通知栏遮挡不消耗开屏检测时间，但等待仍有总时限，避免长期后台轮询。 */
internal class SplashScanSession {
    var packageName: String? = null
        private set
    private var startedAt = 0L
    private var coveredAt: Long? = null
    private var coveredMs = 0L
    private var targetSeen = false

    val covered: Boolean get() = coveredAt != null

    fun start(pkg: String, now: Long) {
        clear()
        packageName = pkg
        startedAt = now
    }

    fun clear() {
        packageName = null
        startedAt = 0L
        coveredAt = null
        coveredMs = 0L
        targetSeen = false
    }

    fun updateCoverage(now: Long, isCovered: Boolean) {
        if (packageName == null) return
        if (isCovered) {
            if (coveredAt == null) coveredAt = now
        } else {
            coveredAt?.let { coveredMs += (now - it).coerceAtLeast(0L) }
            coveredAt = null
        }
    }

    fun age(now: Long): Long = (now - startedAt - coveredMs -
        (coveredAt?.let { now - it } ?: 0L)).coerceAtLeast(0L)

    fun pending(now: Long, windowMs: Long): Boolean = packageName != null &&
        age(now) <= windowMs && now - startedAt <= windowMs + 30_000L

    // 通知栏可能盖住广告直到它自行消失，恢复时不能把正常页面的「关闭」当作开屏按钮。
    fun allowWeak(now: Long): Boolean = packageName != null && !covered && now - startedAt <= 3_000L

    fun observeTarget() {
        targetSeen = true
    }

    // 启动事件可能先于活动窗口更新到达，此时旧应用仍在前台，不能立即丢掉新应用的检测。
    fun awaitingTarget(now: Long): Boolean = packageName != null && !targetSeen && now - startedAt <= 15_000L
}

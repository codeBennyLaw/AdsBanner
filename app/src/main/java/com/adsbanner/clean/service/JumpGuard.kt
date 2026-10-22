package com.adsbanner.clean.service

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.JumpAdRule
import com.adsbanner.clean.core.JumpGuardSession
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.SkipRule
import com.adsbanner.clean.core.StatsStore

/** 以主动操作优先的跳转守护；窗口变快本身不能证明是广告拉起。 */
internal class JumpGuard(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val session = JumpGuardSession()
    private var expectedPackage: String? = null
    private var reconcileUntil = 0L
    private var lastInspectAt = 0L
    private var inspectionScheduled = false
    private var lastReturnAt = 0L
    private var pending: JumpGuardSession.Pending? = null
    private val reconcile = Runnable { reconcileForeground() }
    private val inspect = Runnable { inspectAd() }
    private val returnToSource = Runnable { returnIfStillSuspicious() }

    fun onEvent(event: AccessibilityEvent) {
        if (!Prefs.jumpGuard) {
            stop()
            return
        }
        val pkg = event.packageName?.toString()
        val type = event.eventType
        val now = SystemClock.elapsedRealtime()
        if (pkg in SYSTEM_NAVIGATION) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type in USER_EVENTS) {
                session.systemNavigation(now)
                cancelReturn()
            }
            return
        }
        if (pkg != null && type in USER_EVENTS) {
            // 最近任务/桌面有时不会先成为活动窗口，但其点击仍是明确的系统导航。
            if (isTrusted(pkg)) {
                session.systemNavigation(now)
                cancelReturn()
                return
            }
            // 自动跳过也会产生点击事件，明确的跳过按钮不作为购物意图。
            if (type != AccessibilityEvent.TYPE_VIEW_CLICKED || !isSkipClick(event)) {
                session.userInteraction(pkg, now)
                if (pending?.let { it.from == pkg || it.target == pkg } == true) cancelReturn()
                if (pkg == session.foregroundPackage) cancelInspection()
            }
            return
        }
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && pkg != session.foregroundPackage)) {
            requestForeground(pkg, now)
        } else if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            requestForeground(null, now)
        }
        if (pkg != null && session.canObserveAd(pkg, now, Prefs.splashWindowMs)) scheduleInspection()
    }

    /** 开屏引擎已经读取过的按钮直接复用，不在广告关闭前增加一次树遍历。 */
    fun onAdCandidate(pkg: String, label: String) {
        if (Prefs.jumpGuard && JumpAdRule.isAdSkip(label)) {
            session.observeAd(pkg, SystemClock.elapsedRealtime(), Prefs.splashWindowMs)
        }
    }

    fun isReturningFrom(pkg: String): Boolean = pending?.target == pkg

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        pending = null
        expectedPackage = null
        reconcileUntil = 0L
        lastInspectAt = 0L
        inspectionScheduled = false
        session.reset()
    }

    private fun requestForeground(pkg: String?, now: Long) {
        if (pkg != expectedPackage || reconcileUntil < now) reconcileUntil = now + 600L
        expectedPackage = pkg
        handler.removeCallbacks(reconcile)
        handler.post(reconcile)
    }

    private fun reconcileForeground() {
        if (!Prefs.jumpGuard) return stop()
        val now = SystemClock.elapsedRealtime()
        val root = service.rootInActiveWindow
        val pkg = try { root?.packageName?.toString() } finally { root?.let(::recycleNode) }
        if (pkg in SYSTEM_NAVIGATION) {
            session.systemNavigation(now)
            cancelReturn()
            return
        }
        if (pkg != null) {
            val from = session.foregroundPackage
            if (from != pkg) {
                cancelReturn()
                cancelInspection()
                lastInspectAt = 0L
                val ticket = session.enter(
                    pkg, now, pkg in Prefs.jumpTargets,
                    from == null || isTrusted(from), Prefs.splashWindowMs, Prefs.jumpWindowMs
                )
                if (ticket != null) {
                    pending = ticket
                    handler.postDelayed(returnToSource, 250L)
                } else if (!isTrusted(pkg)) scheduleInspection()
            }
        }
        // 启动事件可能早于活动窗口更新；只短暂补查，不把后台事件包名当作前台。
        if ((pkg == null || (expectedPackage != null && pkg != expectedPackage)) && now < reconcileUntil) {
            handler.postDelayed(reconcile, 50L)
        }
    }

    private fun scheduleInspection() {
        val pkg = session.foregroundPackage ?: return
        val now = SystemClock.elapsedRealtime()
        if (!session.canObserveAd(pkg, now, Prefs.splashWindowMs) || isTrusted(pkg)) return
        if (!inspectionScheduled) {
            inspectionScheduled = true
            handler.postDelayed(inspect, maxOf(75L, 150L - (now - lastInspectAt)))
        }
    }

    private fun inspectAd() {
        inspectionScheduled = false
        if (!Prefs.jumpGuard) return
        val pkg = session.foregroundPackage ?: return
        val now = SystemClock.elapsedRealtime()
        if (!session.canObserveAd(pkg, now, Prefs.splashWindowMs)) return
        lastInspectAt = now
        val root = service.rootInActiveWindow ?: return
        try {
            if (root.packageName?.toString() != pkg) return
            var hasAdMarker: Boolean? = null
            for (text in arrayOf("跳过", "skip", "关闭广告")) {
                val nodes = runCatching { root.findAccessibilityNodeInfosByText(text) }.getOrDefault(emptyList())
                try {
                    for (node in nodes.take(8)) {
                        if (node.packageName?.toString() != pkg || !node.isVisibleToUser || !nearTop(node)) continue
                        val label = label(node)
                        if (SkipRule.match(label) != SkipRule.STRONG) continue
                        if (!JumpAdRule.isAdSkip(label) && hasAdMarker == null) {
                            val markers = runCatching { root.findAccessibilityNodeInfosByText("广告") }.getOrDefault(emptyList())
                            try {
                                hasAdMarker = markers.any {
                                    it.packageName?.toString() == pkg && it.isVisibleToUser && nearTop(it) &&
                                        label(it).let { value -> value.length <= 16 && value.contains("广告") }
                                }
                            } finally { markers.forEach(::recycleNode) }
                        }
                        if (JumpAdRule.isAdSkip(label, hasAdMarker == true)) {
                            session.observeAd(pkg, now, Prefs.splashWindowMs)
                            return
                        }
                    }
                } finally { nodes.forEach(::recycleNode) }
            }
        } finally { recycleNode(root) }
    }

    private fun nearTop(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect().also(node::getBoundsInScreen)
        val screen = service.resources.displayMetrics
        return !bounds.isEmpty && bounds.centerY() >= 0 && bounds.width() <= screen.widthPixels * 0.6f &&
            bounds.height() <= screen.heightPixels * 0.25f && bounds.centerY() < screen.heightPixels * 0.3f
    }

    private fun returnIfStillSuspicious() {
        val ticket = pending ?: return
        pending = null
        val now = SystemClock.elapsedRealtime()
        if (!Prefs.jumpGuard || ticket.target !in Prefs.jumpTargets || !session.mayReturn(ticket, now) ||
            now - lastReturnAt < 1_500L || !interactive()) return
        // 延迟期间用户可能已离开购物应用或拉下通知栏，不能向其它窗口发送返回。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) service.clearCache()
        val root = service.rootInActiveWindow ?: return
        val stillTarget = try {
            root.packageName?.toString() == ticket.target && uncovered(root)
        } finally { recycleNode(root) }
        if (!stillTarget || !Prefs.jumpGuard || !session.mayReturn(ticket, SystemClock.elapsedRealtime()) ||
            !interactive() || !service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) return
        lastReturnAt = now
        StatsStore.record(ticket.target, StatsStore.Kind.JUMP)
        Log.i("AdsBanner", "拦截自动广告跳转 -> ${ticket.target}")
        InterceptNotifier.notify(service, AppInfoHelper.label(service, ticket.target), StatsStore.Kind.JUMP)
    }

    private fun uncovered(root: AccessibilityNodeInfo): Boolean {
        val windows = service.windows.orEmpty()
        try {
            val target = windows.firstOrNull { it.id == root.windowId } ?: return false
            val bounds = Rect().also(target::getBoundsInScreen)
            if (bounds.isEmpty) return false
            return windows.none { window ->
                val overlap = Rect().also(window::getBoundsInScreen)
                window.layer > target.layer && overlap.intersect(bounds) &&
                    overlap.width() > bounds.width() / 2 && overlap.height() > bounds.height() / 6
            }
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) windows.forEach { it.recycle() }
        }
    }

    private fun cancelReturn() {
        handler.removeCallbacks(returnToSource)
        pending = null
    }

    private fun cancelInspection() {
        handler.removeCallbacks(inspect)
        inspectionScheduled = false
    }

    private fun isSkipClick(event: AccessibilityEvent): Boolean {
        if (event.text.any { SkipRule.match(it.toString()) == SkipRule.STRONG }) return true
        val source = event.source ?: return false
        return try { SkipRule.match(label(source)) == SkipRule.STRONG } finally { recycleNode(source) }
    }

    private fun isTrusted(pkg: String): Boolean = pkg == service.packageName || AppInfoHelper.isHomeOrSystem(service, pkg)

    private fun interactive(): Boolean = service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun label(node: AccessibilityNodeInfo): String = node.text?.toString()?.trim().orEmpty()
        .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    companion object {
        private val SYSTEM_NAVIGATION = setOf("android", "com.android.systemui")
        private val USER_EVENTS = setOf(AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, AccessibilityEvent.TYPE_VIEW_SCROLLED)
    }
}

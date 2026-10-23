package com.adsbanner.clean.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.adsbanner.clean.core.MiniAppAdRule
import com.adsbanner.clean.core.MiniAppAdSession
import com.adsbanner.clean.core.MiniAppSource
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.StatsStore

/** Event-driven mini-app scans; bounded bursts only while an ad is settling or being closed. */
internal class MiniAppAdGuard(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val session = MiniAppAdSession()
    private val scan = Runnable { tick() }
    private var host: String? = null
    private var activity = ""
    private var identified = false
    private var retainedSource: MiniAppSource? = null
    private var lastPosition: Pair<String, MiniAppAdRule.Bounds>? = null
    private var nextAt = Long.MAX_VALUE
    private var lastAt = 0L
    private var burstUntil = 0L
    private var generation = 0
    private var gestureTimeout: Runnable? = null
    private val imePackages by lazy {
        service.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    }

    fun owns(pkg: String) = host == pkg && (identified || MiniAppAdRule.isContainerActivity(pkg, activity))

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (host != null && Prefs.inAppAdGuard && owns(host!!)) {
                burstUntil = SystemClock.elapsedRealtime() + 2_500L
                schedule(100L)
            }
            return
        }
        if (pkg == null) return
        if (!MiniAppSource.isHost(pkg)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (pkg == "android" || pkg == "com.android.systemui" || pkg in imePackages) {
                    cancelWork()
                    if (host != null && owns(host!!)) {
                        burstUntil = SystemClock.elapsedRealtime() + 2_500L
                        schedule(150L)
                    }
                } else stop()
            }
            return
        }
        if (host != pkg) { stop(); host = pkg }
        val cls = event.className?.toString().orEmpty()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            cls != activity &&
            (cls.startsWith("$pkg.") || cls.startsWith("com.alipay.") || cls.startsWith("com.tencent.qqmini."))) {
            cancelWork()
            activity = cls
            identified = false
            retainedSource = null
        }
        if (!Prefs.inAppAdGuard) { cancelWork(); return }
        if (!owns(pkg) && activity.isNotEmpty()) return
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED || event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            burstUntil = now + 2_500L
        }
        if (!owns(pkg) && now - lastAt < 2_000L) return
        schedule((200L - (now - lastAt)).coerceAtLeast(0L))
    }

    /** Avoid letting the generic splash engine count mini-app ads against their host. */
    fun ownsRoot(pkg: String, root: AccessibilityNodeInfo?): Boolean {
        if (!MiniAppSource.isHost(pkg)) return false
        if (owns(pkg)) return true
        if (root?.packageName?.toString() != pkg) return false
        for (label in arrayOf("关闭小程序", "关闭当前小程序")) {
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(label) }.getOrDefault(emptyList())
            try {
                if (nodes.take(8).any { n ->
                    val b = Rect().also(n::getBoundsInScreen)
                    n.packageName?.toString() == pkg && n.isVisibleToUser && !b.isEmpty &&
                        b.centerY() < screen().height * .2 && b.centerX() > screen().width * .5
                }) {
                    if (host != pkg) { stop(); host = pkg }
                    identified = true
                    if (Prefs.inAppAdGuard) schedule(0L)
                    return true
                }
            } finally { nodes.forEach(::recycle) }
        }
        return false
    }

    fun stop() {
        cancelWork()
        host = null
        activity = ""
        identified = false
        retainedSource = null
        lastAt = 0L
    }

    private fun cancelWork() {
        generation++
        handler.removeCallbacksAndMessages(null)
        gestureTimeout = null
        session.reset()
        lastPosition = null
        nextAt = Long.MAX_VALUE
        burstUntil = 0L
    }

    private fun schedule(delay: Long) {
        if (!Prefs.inAppAdGuard || host == null) return
        val due = SystemClock.elapsedRealtime() + delay
        if (due >= nextAt) return
        handler.removeCallbacks(scan)
        nextAt = due
        handler.postDelayed(scan, delay)
    }

    private fun interactive() = service.getSystemService(PowerManager::class.java).isInteractive &&
        !service.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun tick() {
        nextAt = Long.MAX_VALUE
        val pkg = host ?: return
        if (!Prefs.inAppAdGuard || !interactive()) { cancelWork(); return }
        val now = SystemClock.elapsedRealtime()
        // Window callbacks can arrive before the active root switches back from system chrome.
        if (Build.VERSION.SDK_INT >= 33 && now < burstUntil) service.clearCache()
        val root = service.rootInActiveWindow
        if (root == null || root.packageName?.toString() != pkg) {
            root?.let(::recycle)
            val resumeUntil = burstUntil
            cancelWork()
            if (now < resumeUntil) {
                burstUntil = resumeUntil
                schedule(250L)
            }
            return
        }
        lastAt = now
        val snapshot = capture(root, pkg)
        try {
            if (!snapshot.complete) {
                if (now < burstUntil) schedule(250L)
                return
            }
            var match = MiniAppAdRule.match(pkg, activity, snapshot.nodes, screen(), session.panelKey, retainedSource)
            if (match != null) {
                identified = true
                retainedSource = match.source
            } else {
                identified = false
                retainedSource = null
            }
            val target = match?.target
            var moving = false
            if (target != null) {
                val position = match.source.key + ":" + match.panelKey to target.bounds
                if (lastPosition != position) { match = match.copy(target = null); moving = true }
                lastPosition = position
            } else lastPosition = null
            val step = session.update(match, now)
            step.record?.let {
                StatsStore.recordMiniApp(it)
                InterceptNotifier.notify(service, it.notificationLabel, StatsStore.Kind.PAGE_AD)
            }
            step.action?.let { execute(pkg, it, snapshot) }
            when {
                moving || step.urgent -> schedule(120L)
                match != null && now < burstUntil -> schedule(700L)
            }
        } finally { snapshot.release() }
    }

    private fun execute(pkg: String, action: MiniAppAdSession.Action, snapshot: Snapshot) {
        val icon = snapshot.live[action.target.icon]
        val b = Rect()
        val root = service.rootInActiveWindow
        val active = try { root?.packageName?.toString() == pkg && root.windowId == icon.windowId } finally { root?.let(::recycle) }
        if (!Prefs.inAppAdGuard || !interactive() || !active || !icon.refresh() || !icon.isVisibleToUser || !icon.isEnabled) {
            session.complete(action, false); return
        }
        icon.getBoundsInScreen(b)
        if (b.toBounds() != action.target.bounds) { session.complete(action, false); return }
        if (!action.gesture) {
            val node = action.target.action?.let { snapshot.live[it] }
            val accepted = node != null && node.refresh() && node.packageName?.toString() == pkg &&
                node.windowId == icon.windowId && node.isVisibleToUser && node.isEnabled && node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            session.complete(action, accepted)
            return
        }
        if (!canTouch(pkg, icon.windowId, b.centerX(), b.centerY())) { session.complete(action, false); return }
        val token = generation
        var finished = false
        fun finish(accepted: Boolean) {
            if (finished || token != generation) return
            finished = true
            gestureTimeout?.let(handler::removeCallbacks)
            gestureTimeout = null
            session.complete(action, accepted)
            schedule(120L)
        }
        val timeout = Runnable { finish(false) }
        gestureTimeout = timeout
        val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, 50L)).build()
        val accepted = runCatching { service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) = finish(true)
            override fun onCancelled(gestureDescription: GestureDescription) = finish(false)
        }, handler) }.getOrDefault(false)
        if (accepted) handler.postDelayed(timeout, 1_000L) else finish(false)
    }

    private fun canTouch(pkg: String, windowId: Int, x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT >= 33) service.clearCache()
        val root = service.rootInActiveWindow ?: return false
        val active = try { root.packageName?.toString() == pkg && root.windowId == windowId } finally { recycle(root) }
        if (!active || !interactive()) return false
        val windows = service.windows.orEmpty()
        try {
            val target = windows.firstOrNull { it.id == windowId } ?: return false
            return windows.none { it.layer > target.layer && Rect().also(it::getBoundsInScreen).contains(x, y) }
        } finally { recycleWindows(windows) }
    }

    private class Snapshot(val nodes: List<MiniAppAdRule.Node>, val live: List<AccessibilityNodeInfo>, val complete: Boolean) {
        fun release() { live.forEach(::recycle) }
    }

    private fun capture(active: AccessibilityNodeInfo, pkg: String): Snapshot {
        val roots = linkedMapOf(active.windowId to active)
        val windows = service.windows.orEmpty()
        try {
            windows.take(12).forEach { window -> if (window.id !in roots) window.root?.let {
                if (it.packageName?.toString() == pkg) roots[it.windowId] = it else recycle(it)
            } }
        } finally { recycleWindows(windows) }
        val nodes = ArrayList<MiniAppAdRule.Node>()
        val live = ArrayList<AccessibilityNodeInfo>()
        var complete = true
        fun walk(node: AccessibilityNodeInfo, parent: Int, path: String, depth: Int) {
            if (node.packageName?.toString() != pkg) { recycle(node); return }
            if (nodes.size >= 1_800 || depth > 40) { complete = false; recycle(node); return }
            val index = nodes.size
            val bounds = Rect().also(node::getBoundsInScreen)
            nodes.add(MiniAppAdRule.Node(parent, "${node.windowId}:$path:${node.viewIdResourceName.orEmpty()}",
                node.viewIdResourceName.orEmpty(), node.text?.toString()?.trim().orEmpty(), node.contentDescription?.toString()?.trim().orEmpty(),
                node.className?.toString().orEmpty(), node.isVisibleToUser, node.isEnabled, node.isClickable, bounds.toBounds()))
            live.add(node)
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child == null) complete = false else walk(child, index, "$path/$i", depth + 1)
            }
        }
        roots.values.forEach { walk(it, -1, "root", 0) }
        return Snapshot(nodes, live, complete)
    }

    private fun screen(): MiniAppAdRule.Bounds {
        val b = if (Build.VERSION.SDK_INT >= 30) service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds else {
            val dm = service.resources.displayMetrics; Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return b.toBounds()
    }

    companion object {
        private fun Rect.toBounds() = MiniAppAdRule.Bounds(left, top, right, bottom)
        @Suppress("DEPRECATION") private fun recycle(node: AccessibilityNodeInfo) { if (Build.VERSION.SDK_INT < 33) node.recycle() }
        @Suppress("DEPRECATION") private fun recycleWindows(windows: List<AccessibilityWindowInfo>) { if (Build.VERSION.SDK_INT < 33) windows.forEach { it.recycle() } }
    }
}

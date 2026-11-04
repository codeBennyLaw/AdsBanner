package com.adsbanner.clean.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import com.adsbanner.clean.core.BiliPauseAdRule
import com.adsbanner.clean.core.BiliPauseAdSession
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.ProtectionStore
import com.adsbanner.clean.core.StatsStore

/** 事件立即唤醒；半屏播放时补充低频查询，提示出现后在有限窗口内快速复查。 */
internal class BiliPauseAdGuard(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val session = BiliPauseAdSession()
    private var running = false
    private var watchingWindows = false
    private var generation = 0
    private var nextScanAt = Long.MAX_VALUE
    private var lastScanAt = 0L
    private var emptyPasses = 0
    private var burstUntil = 0L
    private var lastClose: Pair<String, BiliPauseAdRule.Bounds>? = null
    private var gestureTimeout: Runnable? = null
    private val scan = Runnable { tick() }

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString()
        if (pkg != BiliPauseAdRule.PACKAGE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                if (pkg == "android" || pkg == "com.android.systemui") suspendForWindow() else stop()
            }
            return
        }
        if (!Prefs.inAppAdGuard || !ProtectionStore.allows(BiliPauseAdRule.PACKAGE)) {
            stop()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) burstUntil = now + 4_500L
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) emptyPasses = 0
        running = true
        watchingWindows = true
        val gap = if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) 32L else 120L
        schedule((gap - (now - lastScanAt)).coerceAtLeast(0L))
    }

    fun stop() {
        running = false
        watchingWindows = false
        generation++
        handler.removeCallbacks(scan)
        gestureTimeout?.let(handler::removeCallbacks)
        gestureTimeout = null
        nextScanAt = Long.MAX_VALUE
        lastScanAt = 0L
        emptyPasses = 0
        burstUntil = 0L
        lastClose = null
        session.reset()
    }

    /** Only an already-observed Bilibili page may resume after a system window goes away. */
    fun onWindowsChanged() {
        if (!watchingWindows) return
        if (!Prefs.inAppAdGuard || !ProtectionStore.allows(BiliPauseAdRule.PACKAGE)) {
            stop()
            return
        }
        val root = activeRoot()
        if (root == null) {
            suspendForWindow()
            return
        }
        recycleNode(root)
        running = true
        schedule(0L)
    }

    private fun suspendForWindow() {
        val watch = watchingWindows
        stop()
        watchingWindows = watch
    }

    private fun schedule(delay: Long) {
        if (!running) return
        val due = SystemClock.elapsedRealtime() + delay
        if (due >= nextScanAt) return
        handler.removeCallbacks(scan)
        nextScanAt = due
        handler.postDelayed(scan, delay)
    }

    private fun activeRoot(): AccessibilityNodeInfo? {
        if (!Prefs.inAppAdGuard || !ProtectionStore.allows(BiliPauseAdRule.PACKAGE) || !service.getSystemService(PowerManager::class.java).isInteractive ||
            service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val root = service.rootInActiveWindow ?: return null
        if (root.packageName?.toString() == BiliPauseAdRule.PACKAGE) return root
        recycleNode(root)
        return null
    }

    private fun canInspect(): Boolean {
        val root = activeRoot() ?: return false
        recycleNode(root)
        return true
    }

    private fun tick() {
        nextScanAt = Long.MAX_VALUE
        val root = activeRoot()
        if (root == null) {
            suspendForWindow()
            return
        }
        val now = SystemClock.elapsedRealtime()
        lastScanAt = now
        val snapshot = capture(root)
        try {
            // 页面更新过程中节点暂缺时，仅重查；不把不完整的树当作广告已经消失。
            if (!snapshot.complete) {
                schedule(500L)
                return
            }
            val match = BiliPauseAdRule.match(snapshot.nodes, screenBounds(), session.panelKey)
            val close = match?.close
            var stabilizing = false
            if (close != null) {
                val position = close.key to snapshot.nodes[close.icon].bounds
                // Native node actions follow the control while it moves; only coordinate
                // gestures need a second snapshot with the same position.
                if (lastClose != position) {
                    stabilizing = true
                }
                lastClose = position
            } else {
                lastClose = null
            }
            val step = session.update(match, now, closeStable = !stabilizing)
            emptyPasses = if (match == null) (emptyPasses + 1).coerceAtMost(3) else 0
            if (step.record && canInspect()) StatsStore.record(BiliPauseAdRule.PACKAGE, StatsStore.Kind.PAGE_AD)
            step.action?.let { execute(it, snapshot) }
            schedule(when {
                stabilizing || step.urgent || now < burstUntil -> 120L
                match?.hintKey != null || match?.panelKey != null -> 500L
                match != null -> 900L
                emptyPasses == 1 -> 900L
                emptyPasses == 2 -> 1_800L
                else -> 3_000L
            })
        } finally {
            snapshot.release()
        }
    }

    private fun execute(action: BiliPauseAdSession.Action, snapshot: Snapshot) {
        if (!canInspect()) {
            stop()
            return
        }
        val icon = snapshot.live[action.target.icon]
        val model = snapshot.nodes[action.target.icon]
        val expected = model.bounds
        val windowId = icon.windowId
        val rect = Rect()
        if (!icon.refresh() || !icon.isVisibleToUser || !icon.isEnabled || icon.packageName?.toString() != BiliPauseAdRule.PACKAGE ||
            icon.windowId != windowId || icon.text?.toString()?.trim().orEmpty() != model.text ||
            icon.contentDescription?.toString()?.trim().orEmpty() != model.desc) {
            session.complete(action, false)
            return
        }
        icon.getBoundsInScreen(rect)
        if (rect.width() != expected.width || rect.height() != expected.height || !screenBounds().contains(rect.toBounds()) ||
            (action.gesture && rect.toBounds() != expected)) {
            session.complete(action, false)
            return
        }
        if (!action.gesture) {
            val node = action.target.action?.let { snapshot.live[it] }
            val actionModel = action.target.action?.let { snapshot.nodes[it] }
            val sameTarget = action.target.action == action.target.icon
            // Reuse the icon just refreshed above. Refreshing it a second time while a native
            // animation is translating it would manufacture a mismatch between two frames.
            val accepted = node != null && (sameTarget || node.refresh()) && node.isEnabled && node.isVisibleToUser &&
                node.packageName?.toString() == BiliPauseAdRule.PACKAGE && node.windowId == windowId &&
                node.text?.toString()?.trim().orEmpty() == actionModel?.text &&
                node.contentDescription?.toString()?.trim().orEmpty() == actionModel?.desc &&
                (if (sameTarget) rect else Rect().also(node::getBoundsInScreen)).let { fresh ->
                    actionModel != null && fresh.width() == actionModel.bounds.width && fresh.height() == actionModel.bounds.height &&
                        fresh.contains(rect) && fresh.left - rect.left == actionModel.bounds.left - expected.left &&
                        fresh.top - rect.top == actionModel.bounds.top - expected.top
                } &&
                node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            session.complete(action, accepted)
            return
        }
        if (service.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) {
            session.complete(action, false)
            return
        }
        if (!canGestureAt(windowId, rect.centerX(), rect.centerY())) {
            session.complete(action, false)
            return
        }
        val token = generation
        var finished = false
        val timeout = Runnable {
            if (!finished && token == generation) {
                finished = true
                gestureTimeout = null
                session.complete(action, false)
                schedule(120L)
            }
        }
        fun finish(accepted: Boolean) {
            if (finished || token != generation) return
            finished = true
            handler.removeCallbacks(timeout)
            gestureTimeout = null
            session.complete(action, accepted)
            schedule(120L)
        }
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L)).build()
        val dispatched = runCatching {
            service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = finish(true)
                override fun onCancelled(gestureDescription: GestureDescription) = finish(false)
            }, handler)
        }.getOrDefault(false)
        if (dispatched) {
            gestureTimeout = timeout
            handler.postDelayed(timeout, 1_000L)
        } else {
            finish(false)
        }
    }

    /** Coordinate gestures must never land on a notification shade, keyboard or another window. */
    private fun canGestureAt(windowId: Int, x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) service.clearCache()
        val active = activeRoot() ?: return false
        try { if (active.windowId != windowId) return false } finally { recycleNode(active) }
        val windows = service.windows.orEmpty()
        try {
            val target = windows.firstOrNull { it.id == windowId } ?: return false
            if (!Rect().also(target::getBoundsInScreen).contains(x, y)) return false
            return windows.none { it.layer > target.layer && Rect().also(it::getBoundsInScreen).contains(x, y) }
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) windows.forEach { it.recycle() }
        }
    }

    private class Snapshot(
        val nodes: List<BiliPauseAdRule.Node>,
        val live: List<AccessibilityNodeInfo>,
        val complete: Boolean
    ) {
        @Suppress("DEPRECATION")
        fun release() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) live.forEach { it.recycle() }
        }
    }

    private fun capture(active: AccessibilityNodeInfo): Snapshot {
        val roots = ArrayList<AccessibilityNodeInfo>()
        roots.add(active)
        val windows = service.windows.orEmpty()
        try {
            windows.forEach { window ->
                window.root?.let { root ->
                    if (root.packageName?.toString() == BiliPauseAdRule.PACKAGE && roots.none { it == root }) {
                        roots.add(root)
                    } else recycleNode(root)
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) windows.forEach { it.recycle() }
        }
        val nodes = ArrayList<BiliPauseAdRule.Node>()
        val live = ArrayList<AccessibilityNodeInfo>()
        var complete = roots.isNotEmpty()
        fun walk(node: AccessibilityNodeInfo, parent: Int, path: String, depth: Int) {
            val index = nodes.size
            val bounds = Rect().also(node::getBoundsInScreen)
            nodes.add(BiliPauseAdRule.Node(
                parent, "${node.windowId}:$path:${node.viewIdResourceName.orEmpty()}",
                node.viewIdResourceName.orEmpty(), node.text?.toString()?.trim().orEmpty(),
                node.contentDescription?.toString()?.trim().orEmpty(), node.isVisibleToUser,
                node.isEnabled, node.isClickable, bounds.toBounds(), node.className?.toString().orEmpty()
            ))
            live.add(node)
            // 不按不可见父节点剪枝：Compose 的包装节点可能不可见，但子节点仍可操作。
            for (i in 0 until node.childCount) {
                if (nodes.size >= 2_500 || depth >= 64) {
                    complete = false
                    break
                }
                val child = node.getChild(i)
                if (child == null) complete = false else walk(child, index, "$path/$i", depth + 1)
            }
        }
        roots.forEach { root -> walk(root, -1, "root", 0) }
        return Snapshot(nodes, live, complete)
    }

    private fun screenBounds(): BiliPauseAdRule.Bounds {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        } else {
            val dm = service.resources.displayMetrics
            Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
        return bounds.toBounds()
    }

    private fun Rect.toBounds() = BiliPauseAdRule.Bounds(left, top, right, bottom)

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }
}

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
import com.adsbanner.clean.core.StatsStore

/** 事件立即唤醒；半屏播放时补充低频查询，提示出现后在有限窗口内快速复查。 */
internal class BiliPauseAdGuard(private val service: AccessibilityService) {
    private val handler = Handler(Looper.getMainLooper())
    private val session = BiliPauseAdSession()
    private var running = false
    private var generation = 0
    private var nextScanAt = Long.MAX_VALUE
    private var lastScanAt = 0L
    private var burstUntil = 0L
    private var lastClose: Pair<String, BiliPauseAdRule.Bounds>? = null
    private var gestureTimeout: Runnable? = null
    private val scan = Runnable { tick() }

    fun onEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != BiliPauseAdRule.PACKAGE) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) stop()
            return
        }
        if (!Prefs.inAppAdGuard) {
            stop()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) burstUntil = now + 4_500L
        running = true
        schedule((120L - (now - lastScanAt)).coerceAtLeast(0L))
    }

    fun stop() {
        running = false
        generation++
        handler.removeCallbacks(scan)
        gestureTimeout?.let(handler::removeCallbacks)
        gestureTimeout = null
        nextScanAt = Long.MAX_VALUE
        lastScanAt = 0L
        burstUntil = 0L
        lastClose = null
        session.reset()
    }

    private fun schedule(delay: Long) {
        if (!running) return
        val due = SystemClock.elapsedRealtime() + delay
        if (due >= nextScanAt) return
        handler.removeCallbacks(scan)
        nextScanAt = due
        handler.postDelayed(scan, delay)
    }

    private fun canInspect(): Boolean {
        if (!Prefs.inAppAdGuard || !service.getSystemService(PowerManager::class.java).isInteractive ||
            service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        val root = service.rootInActiveWindow ?: return false
        return try {
            root.packageName?.toString() == BiliPauseAdRule.PACKAGE
        } finally {
            recycleNode(root)
        }
    }

    private fun tick() {
        nextScanAt = Long.MAX_VALUE
        if (!canInspect()) {
            stop()
            return
        }
        val now = SystemClock.elapsedRealtime()
        lastScanAt = now
        val snapshot = capture()
        try {
            // 页面更新过程中节点暂缺时，仅重查；不把不完整的树当作广告已经消失。
            if (!snapshot.complete) {
                schedule(500L)
                return
            }
            var match = BiliPauseAdRule.match(snapshot.nodes, screenBounds(), session.panelKey)
            val close = match?.close
            var stabilizing = false
            if (close != null) {
                val position = close.key to snapshot.nodes[close.icon].bounds
                // 面板滑入时位置在移动，至少跨一次扫描确认关闭按钮位置稳定。
                if (lastClose != position) {
                    match = match.copy(close = null)
                    stabilizing = true
                }
                lastClose = position
            } else {
                lastClose = null
            }
            val step = session.update(match, now)
            if (step.record && canInspect()) StatsStore.record(BiliPauseAdRule.PACKAGE, StatsStore.Kind.PAGE_AD)
            step.action?.let { execute(it, snapshot) }
            schedule(when {
                stabilizing || step.urgent || now < burstUntil -> 120L
                match != null -> 500L
                else -> 900L
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
        val expected = snapshot.nodes[action.target.icon].bounds
        val rect = Rect()
        if (!icon.refresh() || !icon.isVisibleToUser || !icon.isEnabled) {
            session.complete(action, false)
            return
        }
        icon.getBoundsInScreen(rect)
        if (rect.toBounds() != expected) {
            session.complete(action, false)
            return
        }
        if (!action.gesture) {
            val node = action.target.action?.let { snapshot.live[it] }
            val accepted = node != null && node.refresh() && node.isEnabled && node.isVisibleToUser &&
                node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            session.complete(action, accepted)
            return
        }
        if (service.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES == 0) {
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

    private fun capture(): Snapshot {
        val roots = ArrayList<AccessibilityNodeInfo>()
        val active = service.rootInActiveWindow ?: return Snapshot(emptyList(), emptyList(), false)
        if (active.packageName?.toString() != BiliPauseAdRule.PACKAGE) {
            recycleNode(active)
            return Snapshot(emptyList(), emptyList(), false)
        }
        roots.add(active)
        service.windows?.forEach { window ->
            window.root?.let { root ->
                if (root.packageName?.toString() == BiliPauseAdRule.PACKAGE && roots.none { it == root }) {
                    roots.add(root)
                } else recycleNode(root)
            }
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
                node.isEnabled, node.isClickable, bounds.toBounds()
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

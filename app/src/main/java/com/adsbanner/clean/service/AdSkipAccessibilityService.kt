package com.adsbanner.clean.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.BiliPauseAdRule
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.SkipRule
import com.adsbanner.clean.core.SplashScanSession
import com.adsbanner.clean.core.StatsStore

/**
 * AdsBanner 的核心引擎，运行在系统无障碍通道里。
 *
 * 两条独立的能力：
 *
 * 1) 开屏广告跳过
 *    进入某应用后 [Prefs.splashWindowMs] 时间窗内，遍历该应用的可访问性节点树，
 *    找出文案命中 [SkipRule] 且位置合理的"跳过"控件并点击。
 *    位置约束是防误点的关键：广告跳过按钮几乎都在右上角，而正文里的"关闭""跳过"
 *    分布在其它区域，靠位置过滤掉。
 *
 * 2) 误触跳转拦截
 *    广告跳转的唯一可靠特征是"用户还在 A 应用里，A 应用突然把 B 应用切换到前台"。
 *    因此记录"上一次前后台窗口事件的时间"，如果切到名单内的 B 应用且间隔极短、
 *    且来源不是桌面/系统界面，就判定为被强行拉起，立刻发送返回。
 *
 * 说明：无障碍服务只能"观察 + 模拟点击/返回"，没有任何 API 可以在点击落地前
 * 拦截 Intent 跳转。所以跳转拦截一定是"已经跳过去，再立即退回来"，会有极短的闪现。
 */
class AdSkipAccessibilityService : AccessibilityService() {

    private data class Candidate(
        val node: AccessibilityNodeInfo,
        val label: String,
        val score: Int,
        /** 弱匹配：文案是「关闭 / ×」这类到处都是的按钮，点击前要额外确认 */
        val weak: Boolean
    )

    private val handler = Handler(Looper.getMainLooper())

    /** 上一次检测到的前台包名 */
    private var foregroundPkg: String? = null

    /** 上一次窗口切换事件的时间（elapsedRealtime），用于判断"是不是被突然拉起" */
    private var lastWindowEventAt = 0L

    private val splashSession = SplashScanSession()
    private var nextSplashScanAt = Long.MAX_VALUE
    private val splashScan = Runnable { scanSplashWindow() }
    /** 只保留本次启动的应用事件节点，窗口列表短暂缺失时仍可向系统查询该窗口。 */
    private var splashEventRoot: AccessibilityNodeInfo? = null

    /**
     * 应用 -> 最后一次离开前台的时刻。
     * 分享面板、输入法、系统弹层都会让前台包名短暂跳走再跳回来，
     * 靠这个时间戳把「刚离开又回来」的抖动排除掉。
     */
    private val leftAt = HashMap<String, Long>()

    /** 输入法弹起不是用户切换到别的应用，包名在运行时读取，不写死厂商 */
    private val imePackages: Set<String> by lazy {
        runCatching {
            getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.mapNotNull { it.packageName }
                ?.toSet()
                .orEmpty()
        }.getOrDefault(emptySet())
    }

    private var lastSkipTryAt = 0L
    private var skipTryCount = 0

    private var lastJumpBlockAt = 0L

    private val pauseAdGuard by lazy { BiliPauseAdGuard(this) }

    @Volatile
    private var screenCache: Point? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        StatsStore.init(this)
        foregroundPkg = null
        stopSplashScan()
        lastWindowEventAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "无障碍服务已连接")
        // 服务在线时顺带拉起常驻通知，提高进程优先级
        if (Prefs.keepAlive) KeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        val pkg = event.packageName?.toString()
        // B 站页面守护只需要自己的交互事件；窗口切换仍要转发一次，及时结束旧页面扫描。
        if (pkg == BiliPauseAdRule.PACKAGE || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            pauseAdGuard.onEvent(event)
        }
        // 两个能力都关闭时，不再读取节点树或维护前台状态。
        if (!Prefs.skipSplash) stopSplashScan()
        if (!Prefs.skipSplash && !Prefs.jumpGuard) return
        // 窗口集合变化可能没有包名。通知栏收起时即使应用内容不变，也需要补查。
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            refreshSplashCache()
            scheduleSplashScan(0L)
            return
        }
        if (pkg == null) return
        if (pkg == packageName) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !splashSession.awaitingTarget(SystemClock.elapsedRealtime())) {
                stopSplashScan()
                foregroundPkg = null
            } else scheduleSplashScan(0L)
            return
        }
        // 通知栏和输入法不改变正在等待的应用，但展开、收起都要唤醒检测。
        if (pkg in IGNORED_PACKAGES || pkg in imePackages) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) refreshSplashCache()
            scheduleSplashScan(0L)
            return
        }
        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(pkg)
            else -> Unit
        }
        if (Prefs.skipSplash && splashSession.packageName == pkg) rememberSplashSource(event, pkg)
    }

    override fun onInterrupt() {
        stopSplashScan()
        pauseAdGuard.stop()
    }

    override fun onDestroy() {
        stopSplashScan()
        pauseAdGuard.stop()
        handler.removeCallbacksAndMessages(null)
        InterceptNotifier.cancel(this)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        screenCache = null
        clearSplashSource()
        pauseAdGuard.stop()
    }

    // ------------------------------------------------------------------ 窗口切换

    private fun onWindowChanged(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        val prev = foregroundPkg
        val gap = now - lastWindowEventAt
        lastWindowEventAt = now

        if (pkg != prev) {
            prev?.let { leftAt[it] = now }
            foregroundPkg = pkg
            stopSplashScan()

            if (prev != null && gap in 0..Prefs.jumpWindowMs) {
                if (shouldBlockJump(pkg, prev, now)) {
                    blockJump(pkg)
                    return
                }
            }
            if (Prefs.skipSplash && !AppInfoHelper.isHomeOrSystem(this, pkg)) {
                splashSession.start(pkg, now)
                lastSkipTryAt = 0L
                skipTryCount = 0
            }
        }

        scheduleSplashScan(0L)
    }

    private fun onContentChanged(pkg: String) {
        // 服务晚于应用启动时，只为当前活动应用补建一次检测窗口，后台内容事件不启动扫描。
        if (Prefs.skipSplash && foregroundPkg == null && rootInActiveWindow?.packageName?.toString() == pkg) {
            onWindowChanged(pkg)
        } else if (splashSession.packageName == pkg) {
            scheduleSplashScan(0L)
        }
    }

    private fun isScreenInteractive(): Boolean =
        getSystemService(android.os.PowerManager::class.java)?.isInteractive != false &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    private fun stopSplashScan() {
        handler.removeCallbacks(splashScan)
        nextSplashScanAt = Long.MAX_VALUE
        splashSession.clear()
        clearSplashSource()
    }

    private fun clearSplashSource() {
        splashEventRoot?.let(::recycleNode)
        splashEventRoot = null
    }

    private fun rememberSplashSource(event: AccessibilityEvent, pkg: String) {
        var root = event.source ?: return
        if (root.packageName?.toString() != pkg) {
            recycleNode(root)
            return
        }
        // 有些应用只在内容事件中提供节点；尽量向上取整棵树，失败时保留事件子树。
        var depth = 0
        while (depth++ < 30) {
            val parent = root.parent ?: break
            if (parent.packageName?.toString() != pkg) {
                recycleNode(parent)
                break
            }
            recycleNode(root)
            root = parent
        }
        clearSplashSource()
        splashEventRoot = root
    }

    private fun refreshSplashCache() {
        if (splashSession.packageName != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clearCache()
        }
    }

    private fun scheduleSplashScan(delay: Long) {
        if (!Prefs.skipSplash || splashSession.packageName == null) return
        val due = SystemClock.elapsedRealtime() + delay
        if (due >= nextSplashScanAt) return
        handler.removeCallbacks(splashScan)
        nextSplashScanAt = due
        handler.postDelayed(splashScan, delay)
    }

    private fun scanSplashWindow() {
        nextSplashScanAt = Long.MAX_VALUE
        val pkg = splashSession.packageName ?: return
        if (!Prefs.skipSplash || !isScreenInteractive()) {
            stopSplashScan()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val active = rootInActiveWindow
        val activePkg = active?.packageName?.toString()
        // 部分系统在通知栏展开时仍把应用标为活动窗口，不能仅凭活动包名判断遮挡。
        val covered = activePkg != pkg || hasSystemCover(active.windowId)
        active?.let(::recycleNode)
        if (activePkg != null && activePkg != pkg && activePkg !in IGNORED_PACKAGES && activePkg !in imePackages) {
            if (splashSession.awaitingTarget(now)) {
                splashSession.updateCoverage(now, true)
                scheduleSplashScan(150L)
            } else stopSplashScan()
            return
        }
        if (activePkg == pkg) splashSession.observeTarget()
        splashSession.updateCoverage(now, covered)
        if (!splashSession.pending(now, Prefs.splashWindowMs) || skipTryCount >= 120) {
            stopSplashScan()
            return
        }
        // 活动根节点暂缺时也检查应用窗口/事件子树。遮挡期间只提交节点动作。
        attemptSkip(pkg, now, covered)
        scheduleSplashScan(if (covered) 300L else 150L)
    }

    private fun hasSystemCover(windowId: Int): Boolean {
        val screenWindows = windows.orEmpty()
        val target = screenWindows.firstOrNull { it.id == windowId } ?: return true
        val targetBounds = Rect().also(target::getBoundsInScreen)
        return screenWindows.any { window ->
            if (window.layer <= target.layer || window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                false
            } else {
                val overlap = Rect().also(window::getBoundsInScreen)
                // 排除常驻状态栏/导航栏，通知栏和较大的系统弹层按遮挡处理。
                overlap.intersect(targetBounds) && overlap.width() > targetBounds.width() / 2 &&
                    overlap.height() > targetBounds.height() / 6
            }
        }
    }

    // ------------------------------------------------------------------ 跳转拦截

    private fun shouldBlockJump(target: String, from: String, now: Long): Boolean {
        if (!Prefs.jumpGuard) return false
        // 从桌面/系统界面启动属于用户主动行为，不拦
        if (AppInfoHelper.isHomeOrSystem(this, from)) return false
        // 目标应用刚离开又回来，说明是窗口抖动（分享面板、输入法、系统弹层），不是被广告拉起
        val left = leftAt[target]
        if (left != null && now - left < RETURN_GRACE_MS) return false
        return target in Prefs.jumpTargets
    }

    private fun blockJump(target: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastJumpBlockAt < 1500) return
        lastJumpBlockAt = now

        StatsStore.record(target, StatsStore.Kind.JUMP)
        Log.i(TAG, "拦截误触跳转 -> $target")
        InterceptNotifier.notify(this, AppInfoHelper.label(this, target), StatsStore.Kind.JUMP)

        // 稍等目标应用完成启动再返回，否则返回事件可能被丢弃
        handler.postDelayed({
            performGlobalAction(GLOBAL_ACTION_BACK)
            // 部分应用会连续拉起两层页面，第二下前先确认仍停在目标应用
            handler.postDelayed({
                if (foregroundPkg == target) {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
            }, 500L)
        }, 250L)
    }

    // ------------------------------------------------------------------ 开屏跳过

    private fun attemptSkip(pkg: String, now: Long, covered: Boolean) {
        // 同一应用内的尝试间隔。系统事件本身已经按 notificationTimeout 节流，
        // 这里只是防止节点树遍历过于频繁，取一个比系统节流略大的值。
        if (now - lastSkipTryAt < 70L) return
        val age = splashSession.age(now)

        lastSkipTryAt = now
        if (!covered) skipTryCount++

        // 刚进入应用的几秒内放宽位置限制，覆盖少数把跳过按钮放在底部的设计
        val allowAnywhere = age <= 6000L
        // 「关闭 / ×」只在刚启动那一瞬间才可能是开屏按钮，过了这段时间基本都是页面自己的按钮
        val allowWeak = splashSession.allowWeak(now)
        val candidates = findCandidates(pkg, allowAnywhere, allowWeak, covered)
        try {
            // 先尝试多个候选的节点动作，避免不可点击的同名文字挡住真正的跳过按钮。
            val clicked = candidates.take(8).firstOrNull { clickCandidate(it, pkg, covered, allowGesture = false) }
                ?: if (covered) null else candidates.firstOrNull()?.takeIf {
                    clickCandidate(it, pkg, covered = false, allowGesture = true)
                }
            if (clicked == null) return
            stopSplashScan()
            StatsStore.record(pkg, StatsStore.Kind.SPLASH)
            Log.i(TAG, "跳过开屏：\"${clicked.label}\" @ $pkg")
            InterceptNotifier.notify(this, AppInfoHelper.label(this, pkg), StatsStore.Kind.SPLASH)
        } finally {
            candidates.forEach { recycleNode(it.node) }
        }
    }

    private fun findCandidates(pkg: String, allowAnywhere: Boolean, allowWeak: Boolean, covered: Boolean): List<Candidate> {
        val screen = screenSize()
        val roots = LinkedHashMap<Int, AccessibilityNodeInfo>()

        fun addRoot(root: AccessibilityNodeInfo?) {
            if (root == null) return
            if (root.packageName?.toString() == pkg && root.windowId !in roots) roots[root.windowId] = root
            else recycleNode(root)
        }

        runCatching {
            windows?.forEach { addRoot(it.root) }
        }
        addRoot(rootInActiveWindow)
        splashEventRoot?.let { saved ->
            // refresh 仍须得到系统许可；缓存节点不能绕过隐藏窗口的访问限制。
            if (saved.refresh() && saved.packageName?.toString() == pkg) addRoot(copyNode(saved))
            else clearSplashSource()
        }

        // 这些应用里「关闭 / ×」是常规导航控件，弱匹配一律不点，只认明确的"跳过"
        val weakAllowed = allowWeak && pkg !in WEAK_MATCH_BLOCKED
        return try {
            roots.values.flatMap { findInTree(it, screen, allowAnywhere, weakAllowed, covered) }
                .sortedWith(compareBy<Candidate> { it.weak }.thenByDescending { it.score })
        } finally {
            roots.values.forEach(::recycleNode)
        }
    }

    /**
     * 遍历节点树挑出候选。
     *
     * 弱匹配（关闭 / ×）会被额外限制：必须同时满足"页面结构像开屏页"和"页面上有广告特征"，
     * 否则支付宝、微信这类容器应用里的关闭按钮、小程序的关闭图标都会被当成广告按钮点掉。
     */
    private fun findInTree(
        root: AccessibilityNodeInfo,
        screen: Point,
        allowAnywhere: Boolean,
        weakAllowed: Boolean,
        covered: Boolean
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>(4)
        var visited = 0
        var adHints = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || visited > 700) return
            visited++

            // 遮挡时只保留明确的跳过匹配供节点动作使用，不允许弱匹配或坐标点击。
            if (!covered && depth > 0 && !node.isVisibleToUser) return

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val label = if (text.isNotEmpty()) text else desc

            if (label.isNotEmpty()) {
                if (AD_HINT.containsMatchIn(label)) adHints++

                val strength = SkipRule.match(label)
                if (strength != SkipRule.NONE) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val score = scoreOf(strength, rect, screen, allowAnywhere)
                        if (score > 0) {
                            candidates.add(
                                Candidate(copyNode(node), label, score, strength == SkipRule.WEAK)
                            )
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try {
                    walk(child, depth + 1)
                } finally {
                    recycleNode(child)
                }
            }
        }

        walk(root, 0)

        return candidates.filter { candidate ->
            val allowed = !candidate.weak || (weakAllowed && adHints > 0 && visited <= SPLASH_MAX_NODES)
            if (!allowed) recycleNode(candidate.node)
            allowed
        }
    }

    /**
     * 位置打分。返回 0 表示"文案像但位置不像"，直接放弃，避免误点。
     */
    private fun scoreOf(strength: Int, rect: Rect, screen: Point, allowAnywhere: Boolean): Int {
        val w = screen.x.toFloat()
        val h = screen.y.toFloat()
        if (w <= 0f || h <= 0f) return 0
        // 跳过按钮一定是小控件，过大的多半是整块广告卡片
        if (rect.width() > w * 0.6f || rect.height() > h * 0.25f) return 0

        val cx = rect.centerX()
        val cy = rect.centerY()
        val inTopRight = cx > w * 0.55f && cy < h * 0.30f

        return when {
            strength == SkipRule.STRONG && inTopRight -> 100
            strength == SkipRule.STRONG && cy < h * 0.25f -> 85
            strength == SkipRule.WEAK && inTopRight -> 60
            strength == SkipRule.STRONG && allowAnywhere -> 45
            else -> 0
        }
    }

    private fun clickCandidate(candidate: Candidate, pkg: String, covered: Boolean, allowGesture: Boolean): Boolean {
        if (!candidate.node.refresh() || candidate.node.packageName?.toString() != pkg ||
            !candidate.node.isEnabled) return false
        // 页面可能已更新；旧节点不能仅凭之前读到的文案继续点击。
        val label = candidate.node.text?.toString()?.trim().orEmpty().ifEmpty {
            candidate.node.contentDescription?.toString()?.trim().orEmpty()
        }
        val strength = SkipRule.match(label)
        if (strength == SkipRule.NONE || (!candidate.weak && strength != SkipRule.STRONG) ||
            (covered && strength != SkipRule.STRONG)) return false
        val self = Rect()
        candidate.node.getBoundsInScreen(self)
        val selfArea = self.width().toLong() * self.height()

        // 优先点节点自身。向上找可点击父节点时，必须确认父节点没有明显放大成容器，
        // 否则会点到容器中心 —— 广告层里「×」和「⋮」常挂在同一个可点击容器下，就会点偏。
        var node: AccessibilityNodeInfo? = copyNode(candidate.node)
        var depth = 0
        try {
            while (node != null && depth < 4) {
                val hasClickAction = node.isClickable || node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                }
                if (node.packageName?.toString() == pkg && node.windowId == candidate.node.windowId &&
                    node.isEnabled && hasClickAction && (node.isVisibleToUser || covered)) {
                    val parentRect = Rect()
                    node.getBoundsInScreen(parentRect)
                    val parentArea = parentRect.width().toLong() * parentRect.height()
                    val precise = depth == 0 || (selfArea > 0 && parentArea <= selfArea * 2)
                    if (precise && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                }
                val parent = node.parent
                recycleNode(node)
                node = parent
                depth++
            }
        } finally {
            node?.let(::recycleNode)
        }

        // 自定义绘制的控件不暴露 clickable，退化为点击自身中心的坐标
        if (!allowGesture || self.width() <= 0 || self.height() <= 0 ||
            !canTapApplication(pkg, candidate.node.windowId, self.centerX(), self.centerY())) return false
        return tapAt(self.centerX().toFloat(), self.centerY().toFloat())
    }

    /** 触摸会落到最上层窗口，提交手势前再次确认目标窗口及点击点没有被系统界面遮住。 */
    private fun canTapApplication(pkg: String, windowId: Int, x: Int, y: Int): Boolean {
        // 节点遍历期间系统窗口可能已变化，而事件仍在主线程队列里，不能信任此前的窗口缓存。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clearCache()
        } else if (SystemClock.elapsedRealtime() - lastSkipTryAt > 250L) {
            return false
        }
        val active = rootInActiveWindow ?: return false
        if (active.packageName?.toString() != pkg || active.windowId != windowId) return false
        val screenWindows = windows.orEmpty()
        val target = screenWindows.firstOrNull { it.id == windowId } ?: return false
        return screenWindows.none { window ->
            val bounds = Rect()
            window.getBoundsInScreen(bounds)
            window.layer > target.layer && bounds.contains(x, y)
        }
    }

    private fun tapAt(x: Float, y: Float): Boolean = runCatching {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun copyNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) AccessibilityNodeInfo(node)
        else AccessibilityNodeInfo.obtain(node)

    @Suppress("DEPRECATION")
    private fun recycleNode(node: AccessibilityNodeInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) node.recycle()
    }

    private fun screenSize(): Point {
        screenCache?.let { return it }
        val point = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            Point(dm.widthPixels, dm.heightPixels)
        }
        screenCache = point
        return point
    }

    companion object {
        private const val TAG = "AdsBanner"

        /** 开屏页结构简单，节点数通常很少；小程序、列表页动辄上百个 */
        private const val SPLASH_MAX_NODES = 60

        /** 目标应用离开前台不超过这个时间又回来，视为窗口抖动，不拦截 */
        private const val RETURN_GRACE_MS = 5_000L

        /** 出现这些文字才说明当前页确实是开屏广告 */
        private val AD_HINT = Regex("广告|跳过|\\d+\\s*[sS秒]")

        /** 这些应用里「关闭 / ×」是常规导航控件，弱匹配一律不点 */
        private val WEAK_MATCH_BLOCKED = setOf(
            "com.eg.android.AlipayGphone",
            "com.tencent.mm"
        )

        /** 这些包名的事件会污染"前台应用"的判断，直接忽略 */
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui"
        )
    }
}

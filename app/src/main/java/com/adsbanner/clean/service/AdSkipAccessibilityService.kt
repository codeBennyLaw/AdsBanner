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
 *    只在启动期间有近期广告证据、且没有主动操作时，处理名单内应用的自动拉起。
 *    点击、滚动和系统导航优先放行；返回前再次确认目标窗口，减少对正常购物的干扰。
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

    private val splashSession = SplashScanSession()
    private var nextSplashScanAt = Long.MAX_VALUE
    private val splashScan = Runnable { scanSplashWindow() }
    /** 只保留本次启动的应用事件节点，窗口列表短暂缺失时仍可向系统查询该窗口。 */
    private var splashEventRoot: AccessibilityNodeInfo? = null

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

    private val pauseAdGuard by lazy { BiliPauseAdGuard(this) }
    private val jumpGuard by lazy { JumpGuard(this) }

    @Volatile
    private var screenCache: Point? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        StatsStore.init(this)
        foregroundPkg = null
        stopSplashScan()
        jumpGuard.stop()
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
        jumpGuard.onEvent(event)
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
        jumpGuard.stop()
    }

    override fun onDestroy() {
        stopSplashScan()
        pauseAdGuard.stop()
        jumpGuard.stop()
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
        val prev = foregroundPkg

        if (pkg != prev) {
            foregroundPkg = pkg
            stopSplashScan()

            if (Prefs.skipSplash && !AppInfoHelper.isHomeOrSystem(this, pkg)) {
                splashSession.start(pkg, SystemClock.elapsedRealtime())
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
        val source = event.source ?: return
        if (source.packageName?.toString() != pkg) {
            recycleNode(source)
            return
        }
        // 保留变化的子树，先查新出现的按钮；不要在事件回调里一路查询父节点。
        clearSplashSource()
        splashEventRoot = source
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
        if (!Prefs.skipSplash || !isScreenInteractive() || AppInfoHelper.isProtectedFromAutomation(this, pkg)) {
            stopSplashScan()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (jumpGuard.isReturningFrom(pkg)) {
            scheduleSplashScan(250L)
            return
        }
        val remaining = 70L - (now - lastSkipTryAt)
        if (remaining > 0L) {
            // 事件不能在节流期内被丢掉，否则只能等下一轮 150ms 轮询。
            scheduleSplashScan(remaining)
            return
        }
        val active = rootInActiveWindow
        val screenWindows = windows.orEmpty()
        try {
            val activePkg = active?.packageName?.toString()
            // 部分系统在通知栏展开时仍把应用标为活动窗口，不能仅凭活动包名判断遮挡。
            val covered = activePkg != pkg || hasSystemCover(active.windowId, screenWindows)
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
            attemptSkip(pkg, now, covered, active, screenWindows)
            scheduleSplashScan(if (covered) 300L else 150L)
        } finally {
            active?.let(::recycleNode)
            recycleWindows(screenWindows)
        }
    }

    private fun hasSystemCover(windowId: Int, screenWindows: List<AccessibilityWindowInfo>): Boolean {
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

    // ------------------------------------------------------------------ 开屏跳过

    private fun attemptSkip(
        pkg: String, now: Long, covered: Boolean,
        active: AccessibilityNodeInfo?, screenWindows: List<AccessibilityWindowInfo>
    ) {
        val age = splashSession.age(now)

        lastSkipTryAt = now
        if (!covered) skipTryCount++

        val quick = tryQuickSkip(pkg, covered, active)
        if (quick != null) {
            try {
                recordSplashSkip(pkg, quick.label)
            } finally {
                recycleNode(quick.node)
            }
            return
        }

        // 刚进入应用的几秒内放宽位置限制，覆盖少数把跳过按钮放在底部的设计
        val allowAnywhere = age <= 6000L
        // 「关闭 / ×」只在刚启动那一瞬间才可能是开屏按钮，过了这段时间基本都是页面自己的按钮
        val allowWeak = splashSession.allowWeak(now)
        val candidates = findCandidates(pkg, allowAnywhere, allowWeak, covered, active, screenWindows)
        try {
            // 先尝试多个候选的节点动作，避免不可点击的同名文字挡住真正的跳过按钮。
            val clicked = candidates.take(8).firstOrNull { clickCandidate(it, pkg, covered, allowGesture = false) }
                ?: if (covered) null else candidates.firstOrNull()?.takeIf {
                    clickCandidate(it, pkg, covered = false, allowGesture = true)
                }
            if (clicked == null) return
            recordSplashSkip(pkg, clicked.label)
        } finally {
            candidates.forEach { recycleNode(it.node) }
        }
    }

    private fun recordSplashSkip(pkg: String, label: String) {
        stopSplashScan()
        StatsStore.record(pkg, StatsStore.Kind.SPLASH)
        Log.i(TAG, "跳过开屏：\"$label\" @ $pkg")
        InterceptNotifier.notify(this, AppInfoHelper.label(this, pkg), StatsStore.Kind.SPLASH)
    }

    /** 仅对右上角强匹配执行节点动作；弱匹配和坐标回退仍走完整检查。 */
    private fun tryQuickSkip(pkg: String, covered: Boolean, active: AccessibilityNodeInfo?): Candidate? {
        val screen = screenSize()
        fun candidateOf(node: AccessibilityNodeInfo): Candidate? {
            if (node.packageName?.toString() != pkg || !node.isEnabled) return null
            val label = nodeLabel(node)
            if (SkipRule.match(label) != SkipRule.STRONG) return null
            val rect = Rect().also(node::getBoundsInScreen)
            if (rect.isEmpty || scoreOf(SkipRule.STRONG, rect, screen, false) != 100) return null
            if (node.isVisibleToUser) jumpGuard.onAdCandidate(pkg, label)
            return Candidate(copyNode(node), label, 100, false)
        }
        fun click(node: AccessibilityNodeInfo): Candidate? {
            val candidate = candidateOf(node) ?: return null
            if (clickCandidate(candidate, pkg, covered, allowGesture = false)) return candidate
            recycleNode(candidate.node)
            return null
        }
        fun query(root: AccessibilityNodeInfo): Candidate? {
            // 查询在目标应用内执行，避免为了一个按钮逐个跨进程读取数百个无关节点。
            for (text in arrayOf("跳过", "skip")) {
                val nodes = runCatching { root.findAccessibilityNodeInfosByText(text) }.getOrDefault(emptyList())
                try {
                    for (node in nodes.take(8)) click(node)?.let { return it }
                } finally {
                    nodes.forEach(::recycleNode)
                }
            }
            return null
        }
        val source = splashEventRoot
        if (source != null) {
            if (source.refresh() && source.packageName?.toString() == pkg) {
                click(source)?.let { return it }
                if (source != active) query(source)?.let { return it }
            } else clearSplashSource()
        }
        if (active?.packageName?.toString() == pkg) query(active)?.let { return it }
        return null
    }

    private fun findCandidates(
        pkg: String, allowAnywhere: Boolean, allowWeak: Boolean, covered: Boolean,
        active: AccessibilityNodeInfo?, screenWindows: List<AccessibilityWindowInfo>
    ): List<Candidate> {
        val screen = screenSize()
        val roots = LinkedHashMap<Int, AccessibilityNodeInfo>()

        fun addRoot(root: AccessibilityNodeInfo?) {
            if (root == null) return
            if (root.packageName?.toString() == pkg && root.windowId !in roots) roots[root.windowId] = root
            else recycleNode(root)
        }

        active?.let { addRoot(copyNode(it)) }
        runCatching { screenWindows.forEach { if (it.id !in roots) addRoot(it.root) } }
        splashEventRoot?.let { saved ->
            if (saved.windowId !in roots) {
                // 仅在窗口根节点缺失时向上补查；正常扫描不重复读取这条父链。
                // refresh 仍须得到系统许可，缓存节点不能绕过隐藏窗口的访问限制。
                if (saved.refresh() && saved.packageName?.toString() == pkg) {
                    var root = copyNode(saved)
                    var depth = 0
                    while (depth++ < 30) {
                        val parent = root.parent ?: break
                        if (parent.packageName?.toString() != pkg || parent.windowId != saved.windowId) {
                            recycleNode(parent)
                            break
                        }
                        recycleNode(root)
                        root = parent
                    }
                    addRoot(root)
                } else clearSplashSource()
            }
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

            val label = nodeLabel(node)

            if (label.isNotEmpty()) {
                if (SkipRule.hasAdEvidence(label)) adHints++

                val strength = SkipRule.match(label)
                if (strength != SkipRule.NONE) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val score = scoreOf(strength, rect, screen, allowAnywhere)
                        if (score > 0) {
                            if (strength == SkipRule.STRONG && score >= 85 && node.isVisibleToUser) {
                                node.packageName?.toString()?.let { jumpGuard.onAdCandidate(it, label) }
                            }
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
        if (AppInfoHelper.isProtectedFromAutomation(this, pkg)) return false
        if (!candidate.node.refresh() || candidate.node.packageName?.toString() != pkg ||
            !candidate.node.isEnabled) return false
        // 页面可能已更新；旧节点不能仅凭之前读到的文案继续点击。
        val label = nodeLabel(candidate.node)
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

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        node.text?.toString()?.trim().orEmpty().ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }

    @Suppress("DEPRECATION")
    private fun recycleWindows(screenWindows: List<AccessibilityWindowInfo>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) screenWindows.forEach { it.recycle() }
    }

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

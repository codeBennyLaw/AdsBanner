package com.adsbanner.clean.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
import android.view.inputmethod.InputMethodManager
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.SkipRule
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

    /** 应用 -> 首次进入时间 */
    private val enterAt = HashMap<String, Long>()

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

    private var splashDonePkg: String? = null
    private var splashDoneAt = 0L
    private var lastSkipTryAt = 0L
    private var skipTryCount = 0

    private var lastJumpBlockAt = 0L

    /** 暂停广告提示：上次检查时间 */
    private var lastPauseAdCheckAt = 0L

    /** 页面内广告：上次检查时间、上次点击时间与已点过的位置签名 */
    private var lastInAppAdCheckAt = 0L
    private var lastInAppAdClickAt = 0L
    private var lastInAppAdSig = ""

    @Volatile
    private var screenCache: Point? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        StatsStore.init(this)
        foregroundPkg = null
        lastWindowEventAt = SystemClock.elapsedRealtime()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
        // 服务在线时顺带拉起常驻通知，提高进程优先级
        if (Prefs.keepAlive) KeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (pkg in IGNORED_PACKAGES) return
        // 输入法弹起会让前台包名短暂变成输入法，不能当成"切到别的应用"
        if (pkg in imePackages) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(pkg)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        InterceptNotifier.cancel(this)
        super.onDestroy()
    }

    /**
     * 调试用：把当前前台应用的节点树写到 logcat。
     *
     * 页面内广告的识别条件依赖真实节点，闭着眼睛猜文案和位置效率太低，
     * 导出一次就能看清提示条到底是原生控件还是自绘内容。
     */
    fun dumpForegroundTree(): String {
        val pkg = foregroundPkg ?: return ""
        val roots = collectRoots(pkg)
        if (roots.isEmpty()) return ""

        val sb = StringBuilder("节点树 dump @ $pkg\n")
        var count = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (count > 900 || depth > 40) return
            count++

            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()

            if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                sb.append("  ".repeat(depth.coerceAtMost(18)))
                sb.append(cls)
                if (text.isNotEmpty()) sb.append(" text=\"").append(text.take(40)).append('"')
                if (desc.isNotEmpty()) sb.append(" desc=\"").append(desc.take(40)).append('"')
                if (node.isClickable) sb.append(" click")
                sb.append(" [").append(rect.left).append(',')
                    .append(rect.top).append('-')
                    .append(rect.right).append(',')
                    .append(rect.bottom).append("]\n")
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        roots.forEach { walk(it, 0) }
        return sb.toString()
    }

    /** 把节点树分段打到 logcat，单条日志有长度上限 */
    fun dumpToLog(): Boolean {
        val text = dumpForegroundTree()
        if (text.isBlank()) return false

        val chunk = 3_500
        var index = 0
        while (index < text.length) {
            val end = minOf(index + chunk, text.length)
            Log.i(TAG, "DUMP|" + text.substring(index, end))
            index = end
        }
        return true
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        screenCache = null
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
            enterAt[pkg] = now
            splashDonePkg = null
            skipTryCount = 0
            // 刚切到新应用，不继承上一个应用的尝试节流，保证第一时间就能尝试
            lastSkipTryAt = 0L

            if (prev != null && gap in 0..Prefs.jumpWindowMs) {
                if (shouldBlockJump(pkg, prev, now)) {
                    blockJump(pkg)
                    return
                }
            }
        }

        if (Prefs.skipSplash) attemptSkip(pkg, now)
    }

    private fun onContentChanged(pkg: String) {
        val now = SystemClock.elapsedRealtime()
        // 无障碍服务可能晚于应用启动，这里补一次进入时间，避免漏掉开屏
        if (!enterAt.containsKey(pkg)) enterAt[pkg] = now
        if (Prefs.skipSplash) attemptSkip(pkg, now)
        // 先尝试点掉「即将展示广告」的取消，赶在广告弹出之前
        attemptCancelPauseAd(pkg, now)
        attemptCloseInAppAd(pkg, now)
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

    private fun attemptSkip(pkg: String, now: Long) {
        // 同一应用内的尝试间隔。系统事件本身已经按 notificationTimeout 节流，
        // 这里只是防止节点树遍历过于频繁，取一个比系统节流略大的值。
        if (now - lastSkipTryAt < 70L) return
        if (skipTryCount >= 120) return
        if (splashDonePkg == pkg && now - splashDoneAt < 3000L) return

        val entered = enterAt[pkg] ?: return
        val age = now - entered
        if (age > Prefs.splashWindowMs) return

        lastSkipTryAt = now
        skipTryCount++

        // 刚进入应用的几秒内放宽位置限制，覆盖少数把跳过按钮放在底部的设计
        val allowAnywhere = age <= 6000L
        // 「关闭 / ×」只在刚启动那一瞬间才可能是开屏按钮，过了这段时间基本都是页面自己的按钮
        val allowWeak = age <= 3000L
        val candidate = findCandidate(pkg, allowAnywhere, allowWeak) ?: return

        if (clickCandidate(candidate)) {
            splashDonePkg = pkg
            splashDoneAt = SystemClock.elapsedRealtime()
            StatsStore.record(pkg, StatsStore.Kind.SPLASH)
            Log.i(TAG, "跳过开屏：\"${candidate.label}\" @ $pkg")
            InterceptNotifier.notify(this, AppInfoHelper.label(this, pkg), StatsStore.Kind.SPLASH)
        }
    }

    // ------------------------------------------------------------------ 暂停广告提示

    /**
     * 点掉「N 秒后将展示广告 [取消]」这类提示。
     *
     * 提示只停留一秒左右，所以节流压到 120ms；判断依据是文案而不是播放状态——
     * 无障碍服务拿不到播放器是否暂停，但这条提示本身只会在暂停时出现。
     * 命中文案后必须同时找到一个「取消」按钮才动手，避免把别处的取消按钮点掉。
     */
    private fun attemptCancelPauseAd(pkg: String, now: Long) {
        if (!Prefs.inAppAdGuard) return
        if (pkg !in IN_APP_AD_PACKAGES) return
        if (now - lastPauseAdCheckAt < 120L) return

        val roots = collectRoots(pkg)
        if (roots.isEmpty()) return
        lastPauseAdCheckAt = now

        var hasHint = false
        var cancelNode: AccessibilityNodeInfo? = null
        var visited = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 28 || visited > 600) return
            if (hasHint && cancelNode != null) return
            if (depth > 0 && !node.isVisibleToUser) return
            visited++

            val label = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }

            if (label.isNotEmpty()) {
                if (!hasHint && label.length <= 30 && PAUSE_AD_HINT.containsMatchIn(label)) {
                    hasHint = true
                }
                if (cancelNode == null && label == "取消") cancelNode = node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        for (root in roots) {
            walk(root, 0)
            if (hasHint && cancelNode != null) break
        }

        if (!hasHint) return
        val target = cancelNode ?: run {
            // 文案读到了却找不到按钮，通常是提示条用了自绘控件，留个痕迹便于排查
            Log.i(TAG, "发现暂停广告提示但没找到取消按钮 @ $pkg")
            return
        }

        if (clickCandidate(Candidate(target, "取消", 0, false))) {
            StatsStore.record(pkg, StatsStore.Kind.PAGE_AD)
            Log.i(TAG, "取消暂停广告提示 @ $pkg")
        }
    }

    // ------------------------------------------------------------------ 页面内广告

    /**
     * 关闭页面内覆盖式广告，例如播放页暂停后盖在画面上的广告层。
     *
     * 与开屏跳过的区别是不限定进入应用后的时间窗，因此必须更保守：
     * 播放页右上角本来就有播放器自己的控件，误点会把视频关掉。三个条件同时满足才动手——
     * 只在已知会插入这类广告的应用内、页面里出现「广告」标识、右上角有很小的「关闭 / ×」控件。
     */
    private fun attemptCloseInAppAd(pkg: String, now: Long) {
        if (!Prefs.inAppAdGuard) return
        if (pkg !in IN_APP_AD_PACKAGES) return
        if (now - lastInAppAdCheckAt < 1_000L) return

        val roots = collectRoots(pkg)
        if (roots.isEmpty()) return
        lastInAppAdCheckAt = now

        val screen = screenSize()
        val w = screen.x.toFloat()
        val h = screen.y.toFloat()
        if (w <= 0f || h <= 0f) return

        var hasAdLabel = false
        var closeNode: AccessibilityNodeInfo? = null
        var closeLabel = ""

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return
            if (depth > 0 && !node.isVisibleToUser) return

            val label = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }

            if (label.isNotEmpty()) {
                if (!hasAdLabel && label in AD_LABELS) hasAdLabel = true
                if (closeNode == null && label in CLOSE_LABELS) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val small = rect.width() in 1..(w * 0.18f).toInt() &&
                        rect.height() in 1..(h * 0.12f).toInt()
                    if (small && rect.centerX() > w * 0.78f && rect.centerY() < h * 0.45f) {
                        closeNode = node
                        closeLabel = label
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        for (root in roots) {
            walk(root, 0)
            if (hasAdLabel && closeNode != null) break
        }

        val target = closeNode ?: return
        if (!hasAdLabel) return

        val rect = Rect()
        target.getBoundsInScreen(rect)
        // 同一位置的关闭按钮短时间内只点一次，防止广告反复弹出时连续触发
        val signature = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
        if (signature == lastInAppAdSig && now - lastInAppAdClickAt < 10_000L) return

        if (clickCandidate(Candidate(target, closeLabel, 0, false))) {
            lastInAppAdSig = signature
            lastInAppAdClickAt = now
            StatsStore.record(pkg, StatsStore.Kind.PAGE_AD)
            Log.i(TAG, "关闭页面内广告：\"$closeLabel\" @ $pkg")
        }
    }

    private fun findCandidate(pkg: String, allowAnywhere: Boolean, allowWeak: Boolean): Candidate? {
        val screen = screenSize()
        val roots = ArrayList<AccessibilityNodeInfo>()

        runCatching {
            windows?.forEach { window ->
                val root = window?.root ?: return@forEach
                if (root.packageName == pkg) roots.add(root)
            }
        }

        if (roots.isEmpty()) {
            val active = rootInActiveWindow ?: return null
            if (active.packageName != pkg) return null
            roots.add(active)
        }

        // 这些应用里「关闭 / ×」是常规导航控件，弱匹配一律不点，只认明确的"跳过"
        val weakAllowed = allowWeak && pkg !in WEAK_MATCH_BLOCKED

        // 快路径：先查当前活动窗口，开屏广告基本都在这一层，命中就不用再遍历其它窗口
        val active = rootInActiveWindow
        if (active != null && active.packageName == pkg) {
            findInTree(active, screen, allowAnywhere, weakAllowed)?.let { return it }
            if (roots.isEmpty()) roots.add(active)
        }

        if (roots.isEmpty()) return null

        var best: Candidate? = null
        for (root in roots) {
            val found = findInTree(root, screen, allowAnywhere, weakAllowed) ?: continue
            if (best == null || found.score > best.score) best = found
        }
        return best
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
        weakAllowed: Boolean
    ): Candidate? {
        val candidates = ArrayList<Candidate>(4)
        var visited = 0
        var adHints = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || visited > 700) return
            visited++

            // 不可见节点的整棵子树都不可见，直接剪掉，避免在无效分支上耗时间；
            // 根节点本身可能被判为不可见，所以 depth 0 不剪
            if (depth > 0 && !node.isVisibleToUser) return

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
                                Candidate(node, label, score, strength == SkipRule.WEAK)
                            )
                        }
                    }
                }
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                walk(child, depth + 1)
            }
        }

        walk(root, 0)

        candidates.sortByDescending { it.score }
        for (candidate in candidates) {
            if (!candidate.weak) return candidate
            if (weakAllowed && adHints > 0 && visited <= SPLASH_MAX_NODES) return candidate
        }
        return null
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

    private fun clickCandidate(candidate: Candidate): Boolean {
        val self = Rect()
        candidate.node.getBoundsInScreen(self)
        val selfArea = self.width().toLong() * self.height()

        // 优先点节点自身。向上找可点击父节点时，必须确认父节点没有明显放大成容器，
        // 否则会点到容器中心 —— 广告层里「×」和「⋮」常挂在同一个可点击容器下，就会点偏。
        var node: AccessibilityNodeInfo? = candidate.node
        var depth = 0
        while (node != null && depth < 4) {
            if (node.isEnabled && node.isClickable && node.isVisibleToUser) {
                val parentRect = Rect()
                node.getBoundsInScreen(parentRect)
                val parentArea = parentRect.width().toLong() * parentRect.height()
                val precise = depth == 0 || (selfArea > 0 && parentArea <= selfArea * 2)
                if (precise && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            node = node.parent
            depth++
        }

        // 自定义绘制的控件不暴露 clickable，退化为点击自身中心的坐标
        if (self.width() <= 0 || self.height() <= 0) return false
        return tapAt(self.centerX().toFloat(), self.centerY().toFloat())
    }

    /** 收集当前属于该应用的所有窗口根节点，悬浮层、弹层常常不在活动窗口里 */
    private fun collectRoots(pkg: String): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>(2)
        runCatching {
            windows?.forEach { window ->
                val root = window?.root ?: return@forEach
                if (root.packageName == pkg) roots.add(root)
            }
        }
        if (roots.isEmpty()) {
            rootInActiveWindow?.let { if (it.packageName == pkg) roots.add(it) }
        }
        return roots
    }

    private fun tapAt(x: Float, y: Float): Boolean = runCatching {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 60L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
    }.getOrDefault(false)

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

        /** 供设置页在调试时导出当前页面节点树，未连接时为 null */
        @Volatile
        var instance: AdSkipAccessibilityService? = null
            private set

        /** 开屏页结构简单，节点数通常很少；小程序、列表页动辄上百个 */
        private const val SPLASH_MAX_NODES = 60

        /** 目标应用离开前台不超过这个时间又回来，视为窗口抖动，不拦截 */
        private const val RETURN_GRACE_MS = 5_000L

        /** 页面内广告清理生效的应用，暂只针对哔哩哔哩的播放页暂停广告 */
        private val IN_APP_AD_PACKAGES = setOf(
            "tv.danmaku.bili"
        )

        /** 「N 秒后将展示广告」这类倒计时提示，命中后配合「取消」按钮一起判断 */
        private val PAUSE_AD_HINT = Regex("秒后将展示广告|即将展示广告|秒后将播放广告|即将播放广告")

        /** 广告标识，要求整段文案完全相等，避免正文里出现「广告」二字时误判 */
        private val AD_LABELS = setOf("广告", "廣告", "赞助", "推广")

        /** 关闭控件的文案，包含常见的 × 变体 */
        private val CLOSE_LABELS = setOf(
            "关闭", "关闭广告", "不感兴趣", "×", "✕", "╳", "X", "x"
        )

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

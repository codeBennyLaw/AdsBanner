package com.adsbanner.clean.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import androidx.core.content.ContextCompat
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.BiliPauseAdRule
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.ProtectionStore
import com.adsbanner.clean.core.LocalAdRule
import com.adsbanner.clean.core.JumpAdRule
import com.adsbanner.clean.core.SkipRule
import com.adsbanner.clean.core.SkipTargetPolicy
import com.adsbanner.clean.core.SplashScanSession
import com.adsbanner.clean.core.SplashScanCadence
import com.adsbanner.clean.core.SplashResumeTracker
import com.adsbanner.clean.core.ScreenStateGate
import com.adsbanner.clean.core.StatsStore

/**
 * AdsBanner 的核心引擎，运行在系统无障碍通道里。
 *
 * 两条独立的能力：
 *
 * 1) 开屏广告跳过
 *    进入某应用后 [Prefs.splashWindowMs] 时间窗内，遍历该应用的可访问性节点树，
 *    找出文案命中 [SkipRule] 且位置合理的"跳过"控件并点击。
 *    结合广告证据、尺寸与位置防止误点；普通关闭限于右上角，恢复检测只允许明确跳过。
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
        val weak: Boolean,
        val rule: LocalAdRule? = null,
        var gestureAllowed: Boolean = true,
        val treeEvidence: Boolean = false
    )

    private data class PendingSkip(val candidate: Candidate, val pkg: String, val bounds: Rect,
        val gesture: Boolean, val startedAt: Long, val absentAt: Long? = null)
    private data class FailedSkip(val node: AccessibilityNodeInfo, val label: String)
    private var pendingSkip: PendingSkip? = null
    private val failedSkips = ArrayList<FailedSkip>(4)
    private val verifySkip = Runnable { verifyPendingSkip() }

    private val handler = Handler(Looper.getMainLooper())
    private val screenState = ScreenStateGate()
    private val splashResume = SplashResumeTracker()
    private var receiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenState.update(intent?.action != Intent.ACTION_SCREEN_OFF && isScreenInteractive())
            if (!screenState.ready) {
                splashResume.onScreenHidden()
                suspendScans()
            } else {
                splashResume.onScreenReady(SystemClock.elapsedRealtime())
                reconcileSplashResume()
            }
            InterceptNotifier.onScreenChanged(this@AdSkipAccessibilityService, screenState.ready)
        }
    }
    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        if (!Prefs.skipSplash) {
            if (splashSession.packageName != null) stopSplashScan()
            splashResume.clear()
        }
        if (!Prefs.jumpGuard) jumpGuard.stop()
        if (!Prefs.inAppAdGuard) pauseAdGuard.stop()
        if (!Prefs.interceptNotify) InterceptNotifier.cancel(this)
        if (!Prefs.skipSplash && !Prefs.jumpGuard && !Prefs.inAppAdGuard) {
            foregroundPkg = null
            foregroundActivity = ""
            activeRules = emptyList()
            embeddedPagePackage = null
        }
    }

    /** 上一次检测到的前台包名 */
    private var foregroundPkg: String? = null
    private var foregroundActivity = ""
    private var activeRules: List<LocalAdRule> = emptyList()
    private var embeddedPagePackage: String? = null

    private val splashSession = SplashScanSession()
    private val splashCadence = SplashScanCadence()
    private var nextSplashScanAt = Long.MAX_VALUE
    private var splashCacheDirty = false
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

    private var skipTryCount = 0
    private var gestureInFlight = false
    private var actionGeneration = 0
    private var gestureTimeout: Runnable? = null

    private val pauseAdGuard by lazy { BiliPauseAdGuard(this) }
    private val jumpGuard by lazy { JumpGuard(this) }
    private val policyListener: () -> Unit = {
        stopSplashScan()
        pauseAdGuard.stop()
        jumpGuard.stop()
        foregroundPkg = null
        foregroundActivity = ""
        activeRules = emptyList()
        embeddedPagePackage = null
        splashResume.clear()
    }

    @Volatile
    private var screenCache: Point? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Prefs.init(this)
        StatsStore.init(this)
        ProtectionStore.init(this)
        ProtectionStore.addPolicyListener(policyListener)
        Prefs.addChangeListener(settingsListener)
        screenState.update(isScreenInteractive())
        splashResume.clear()
        if (!screenState.ready) splashResume.onScreenHidden()
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        foregroundPkg = null
        embeddedPagePackage = null
        stopSplashScan()
        jumpGuard.stop()
        Log.i(TAG, "无障碍服务已连接")
        // 服务在线时顺带拉起常驻通知，提高进程优先级
        if (Prefs.keepAlive) KeepAliveService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (ProtectionStore.paused) return
        if (!Prefs.skipSplash && !Prefs.jumpGuard && !Prefs.inAppAdGuard) return
        val type = event.eventType
        val wasReady = screenState.ready
        if (!screenState.allows(SystemClock.elapsedRealtime(), type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                ::isScreenInteractive)) {
            if (wasReady) {
                splashResume.onScreenHidden()
                suspendScans()
            }
            return
        }
        splashResume.onScreenReady(SystemClock.elapsedRealtime())
        if (!wasReady) InterceptNotifier.onScreenChanged(this, true)
        val pkg = event.packageName?.toString()
        if (pendingSkip != null && !gestureInFlight) verifyPendingSkip(allowFallback = false)
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || pkg == foregroundPkg) reconcileSplashResume()
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) pauseAdGuard.onWindowsChanged()
        // B 站页面守护只需要自己的交互事件；窗口切换仍要转发一次，及时结束旧页面扫描。
        if (pkg == BiliPauseAdRule.PACKAGE || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            pauseAdGuard.onEvent(event)
        }
        jumpGuard.onEvent(event)
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.className?.toString()?.let { it.endsWith(".ChooserActivity") || it.endsWith(".ResolverActivity") } == true) {
            splashResume.onTransientWindow()
            stopSplashScan()
            return
        }
        if (pkg != null && !ProtectionStore.allows(pkg)) {
            if (splashSession.packageName == pkg) stopSplashScan()
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) foregroundPkg = pkg
            return
        }
        // Embedded pages are excluded from application-launch scans; they have no ad-cleaning engine.
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null &&
            pkg !in IGNORED_PACKAGES && pkg !in imePackages) {
            val cls = event.className?.toString().orEmpty()
            if (SkipRule.isEmbeddedPage(pkg, cls)) {
                embeddedPagePackage = pkg
                foregroundPkg = pkg
                stopSplashScan()
            } else if (pkg != embeddedPagePackage || cls.startsWith("$pkg.")) embeddedPagePackage = null
        }
        if (pkg != null && pkg == embeddedPagePackage) return
        // 两个能力都关闭时，不再读取节点树或维护前台状态。
        if (!Prefs.skipSplash && splashSession.packageName != null) stopSplashScan()
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
                splashResume.clear()
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
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged(pkg, event.className?.toString().orEmpty(), event.windowId)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> onContentChanged(pkg)
            else -> Unit
        }
        if (Prefs.skipSplash && splashSession.packageName == pkg &&
            (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            rememberSplashSource(event, pkg)
        }
    }

    override fun onInterrupt() {
        suspendScans()
    }

    private fun suspendScans() {
        stopSplashScan()
        pauseAdGuard.stop()
        jumpGuard.stop()
    }

    override fun onDestroy() {
        Prefs.removeChangeListener(settingsListener)
        if (receiverRegistered) unregisterReceiver(screenReceiver)
        receiverRegistered = false
        ProtectionStore.removePolicyListener(policyListener)
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

    private fun onWindowChanged(pkg: String, activity: String = "", windowId: Int = -1, fromActiveWindow: Boolean = false) {
        val prev = foregroundPkg
        val now = SystemClock.elapsedRealtime()
        val declared = AppInfoHelper.isDeclaredActivity(this, pkg, activity)
        val entry = if (pkg != prev || declared || fromActiveWindow) {
            splashResume.enter(pkg, activity.takeIf { declared }, windowId.takeIf { it >= 0 }, now)
        } else {
            splashResume.onTransientWindow(pkg, windowId)
            SplashResumeTracker.Entry.NONE
        }

        if (pkg != prev) {
            foregroundPkg = pkg
            foregroundActivity = ""
            activeRules = ProtectionStore.rules.filter { it.enabled && it.packageName == pkg }
        }

        // Initial activity transitions retain the original launch deadline; weak matching still ends at 3s.
        // Wake/share returns get a new finite, strong-only window even if the package is unchanged.
        val earlyLaunch = (entry == SplashResumeTracker.Entry.ACTIVITY || entry == SplashResumeTracker.Entry.RETURN) &&
            splashSession.packageName == pkg &&
            !splashSession.resumed && splashSession.pending(now, Prefs.splashWindowMs)
        val awaitingResult = pkg == prev && pendingSkip?.let { it.pkg == pkg && now - it.startedAt < 800L } == true
        if (pkg != prev || (entry != SplashResumeTracker.Entry.NONE && !earlyLaunch && !awaitingResult)) {
            stopSplashScan()
            if (Prefs.skipSplash && ProtectionStore.allows(pkg) && embeddedPagePackage != pkg && !AppInfoHelper.isHomeOrSystem(this, pkg)) {
                splashSession.start(pkg, now, resumed = entry != SplashResumeTracker.Entry.LAUNCH)
                skipTryCount = 0
            }
        }

        if (activity.contains('.') && !activity.startsWith("android.") && !activity.startsWith("androidx.")) {
            foregroundActivity = activity
        }

        scheduleSplashScan(0L)
    }

    private fun onContentChanged(pkg: String) {
        // 服务晚于应用启动时，只为当前活动应用补建一次检测窗口，后台内容事件不启动扫描。
        if (Prefs.skipSplash && foregroundPkg == null) {
            val root = rootInActiveWindow ?: return
            try {
                if (root.packageName?.toString() == pkg) onWindowChanged(pkg, windowId = root.windowId, fromActiveWindow = true)
            } finally { recycleNode(root) }
        } else if (splashSession.packageName == pkg) {
            scheduleSplashScan(0L)
        }
    }

    /** Resolve only a pending wake/temporary-window return, rate limited and driven by events. */
    private fun reconcileSplashResume() {
        val now = SystemClock.elapsedRealtime()
        if (!Prefs.skipSplash || ProtectionStore.paused || !splashResume.takeWindowCheck(now) || !isScreenInteractive()) return
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString() ?: return
            if (pkg == packageName || pkg == embeddedPagePackage || !ProtectionStore.allows(pkg) || AppInfoHelper.isHomeOrSystem(this, pkg)) return
            if (splashResume.canReconcile(pkg, root.windowId, now)) {
                onWindowChanged(pkg, windowId = root.windowId, fromActiveWindow = true)
            }
        } finally { recycleNode(root) }
    }

    private fun isScreenInteractive(): Boolean =
        getSystemService(android.os.PowerManager::class.java)?.isInteractive != false &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true

    private fun stopSplashScan() {
        clearPendingSkip()
        failedSkips.forEach { recycleNode(it.node) }
        failedSkips.clear()
        actionGeneration++
        gestureInFlight = false
        gestureTimeout?.let(handler::removeCallbacks)
        gestureTimeout = null
        handler.removeCallbacks(splashScan)
        nextSplashScanAt = Long.MAX_VALUE
        splashCacheDirty = false
        splashSession.clear()
        splashCadence.reset()
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
            splashCacheDirty = true
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
        if (!Prefs.skipSplash || !ProtectionStore.allows(pkg) || !isScreenInteractive() || AppInfoHelper.isProtectedFromAutomation(this, pkg)) {
            stopSplashScan()
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!splashSession.pending(now, Prefs.splashWindowMs)) {
            stopSplashScan()
            return
        }
        if (pendingSkip != null && !gestureInFlight) {
            verifyPendingSkip()
            return
        }
        if (gestureInFlight) {
            scheduleSplashScan(100L)
            return
        }
        if (jumpGuard.isReturningFrom(pkg)) {
            scheduleSplashScan(250L)
            return
        }
        val remaining = splashCadence.queryDelay(now)
        if (remaining > 0L) {
            // Keep the event's fast query, independently of the full-tree budget.
            scheduleSplashScan(remaining)
            return
        }
        if (splashCacheDirty && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            splashCacheDirty = false
            clearCache()
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
            if (!splashSession.pending(now, Prefs.splashWindowMs) || skipTryCount >= 384) {
                stopSplashScan()
                return
            }
            // 活动根节点暂缺时也检查应用窗口/事件子树。遮挡期间只提交节点动作。
            attemptSkip(pkg, now, covered, active, screenWindows)
            scheduleSplashScan(splashCadence.pollDelay(splashSession.age(now), covered))
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
        splashCadence.queried(now)
        if (!covered) skipTryCount++

        // Custom rules keep priority, but an inactive/unmatched rule must not force a full tree walk.
        if (!splashSession.resumed && activeRules.any { it.appliesTo(pkg, foregroundActivity, splashSession.age(now)) }) {
            val exact = findCandidates(pkg, false, covered, active, screenWindows, localOnly = true)
            if (exact.isNotEmpty()) {
                actOnCandidates(exact, pkg, covered)
                return
            }
        }
        val quick = tryQuickSkip(pkg, covered, active)
        if (quick != null) {
            recycleNode(quick.node)
            return
        }
        if (gestureInFlight) return

        // 「关闭 / ×」只在刚启动那一瞬间才可能是开屏按钮，过了这段时间基本都是页面自己的按钮
        val allowWeak = splashSession.allowWeak(now)
        if (!splashCadence.takeTreePass(now)) return
        val candidates = findCandidates(pkg, allowWeak, covered, active, screenWindows)
        actOnCandidates(candidates, pkg, covered)
    }

    private fun actOnCandidates(candidates: List<Candidate>, pkg: String, covered: Boolean) {
        try {
            // 先尝试多个候选的节点动作，避免不可点击的同名文字挡住真正的跳过按钮。
            val clicked = candidates.take(8).firstOrNull { clickCandidate(it, pkg, covered) }
            if (clicked == null && !covered) candidates.firstOrNull()?.let { submitGesture(it, pkg) }
        } finally {
            candidates.forEach { recycleNode(it.node) }
        }
    }

    private fun beginSkipVerification(candidate: Candidate, pkg: String, gesture: Boolean, schedule: Boolean = true) {
        clearPendingSkip()
        pendingSkip = PendingSkip(candidate.copy(node = copyNode(candidate.node)), pkg,
            Rect().also(candidate.node::getBoundsInScreen), gesture, SystemClock.elapsedRealtime())
        if (schedule) handler.postDelayed(verifySkip, 80L)
    }

    private fun clearPendingSkip() {
        handler.removeCallbacks(verifySkip)
        pendingSkip?.let { recycleNode(it.candidate.node) }
        pendingSkip = null
    }

    /** Accepted accessibility actions and completed gestures do not prove the advertisement closed. */
    private fun verifyPendingSkip(allowFallback: Boolean = true) {
        val pending = pendingSkip ?: return
        if (gestureInFlight) return
        if (!Prefs.skipSplash || !ProtectionStore.allows(pending.pkg) || !isScreenInteractive() ||
            splashSession.packageName != pending.pkg) { clearPendingSkip(); return }
        val root = rootInActiveWindow
        try {
            val pkg = root?.packageName?.toString()
            val elapsed = SystemClock.elapsedRealtime() - pending.startedAt
            val node = pending.candidate.node
            val refreshed = node.refresh()
            val sameNode = refreshed && node.packageName?.toString() == pending.pkg
            val matches = sameNode && if (pending.candidate.rule != null) nodeLabel(node) == pending.candidate.label
                else SkipRule.match(skipNodeLabel(node)) >= (if (pending.candidate.weak) SkipRule.WEAK else SkipRule.STRONG)
            if (pkg != pending.pkg) {
                if (sameNode && !matches && (pkg == null || pkg in IGNORED_PACKAGES || pkg in imePackages)) {
                    confirmPendingDisappearance()
                    return
                }
                pendingSkip = pending.copy(absentAt = null)
                if (elapsed < 800L && (pkg == null || pkg in IGNORED_PACKAGES || pkg in imePackages)) {
                    handler.removeCallbacks(verifySkip)
                    handler.postDelayed(verifySkip, 100L)
                } else { clearPendingSkip(); scheduleSplashScan(100L) }
                return
            }
            val stillTarget = matches && (node.isVisibleToUser || checkNotNull(root).windowId != node.windowId || applicationCovered(root.windowId))
            if (!stillTarget) {
                // Countdown widgets can replace their node every tick; look for the same control before counting.
                val replacement = findReplacementSkip(checkNotNull(root), pending)
                if (replacement != null) {
                    recycleNode(node)
                    pendingSkip = pending.copy(candidate = pending.candidate.copy(node = replacement), absentAt = null)
                } else {
                    confirmPendingDisappearance(windowChanged = root.windowId != node.windowId)
                    return
                }
            } else if (pending.absentAt != null) pendingSkip = pending.copy(absentAt = null)
            if (sameNode && matches && !node.isEnabled && elapsed < 800L) {
                handler.removeCallbacks(verifySkip)
                handler.postDelayed(verifySkip, 100L)
                return
            }
            if (!allowFallback || elapsed < 180L) {
                handler.removeCallbacks(verifySkip)
                handler.postDelayed(verifySkip, (180L - elapsed).coerceAtLeast(40L))
                return
            }
            val current = pendingSkip ?: return
            pendingSkip = null // Transfer ownership until submission has copied its node.
            handler.removeCallbacks(verifySkip)
            try {
                if (!current.gesture && current.candidate.gestureAllowed) submitGesture(current.candidate, current.pkg)
                if (pendingSkip == null) {
                    if (current.gesture || !current.candidate.gestureAllowed) {
                        failedSkips.removeAll { failed ->
                            (failed.node == current.candidate.node).also { if (it) recycleNode(failed.node) }
                        }
                        if (failedSkips.size >= 8) recycleNode(failedSkips.removeAt(0).node)
                        failedSkips.add(FailedSkip(copyNode(current.candidate.node), skipNodeLabel(current.candidate.node)))
                    }
                    scheduleSplashScan(120L)
                }
            } finally { recycleNode(current.candidate.node) }
        } finally { root?.let(::recycleNode) }
    }

    private fun confirmPendingDisappearance(windowChanged: Boolean = false) {
        val pending = pendingSkip ?: return
        val now = SystemClock.elapsedRealtime()
        if (windowChanged || pending.absentAt?.let { now - it >= 100L } == true) {
            recordSplashSkip(pending.pkg, pending.candidate.label, pending.candidate.rule?.name)
        } else {
            if (pending.absentAt == null) pendingSkip = pending.copy(absentAt = now)
            handler.removeCallbacks(verifySkip)
            handler.postDelayed(verifySkip, 100L)
        }
    }

    private fun applicationCovered(windowId: Int): Boolean {
        val screenWindows = windows.orEmpty()
        return try { hasSystemCover(windowId, screenWindows) } finally { recycleWindows(screenWindows) }
    }

    private fun findReplacementSkip(root: AccessibilityNodeInfo, pending: PendingSkip): AccessibilityNodeInfo? {
        val rule = pending.candidate.rule
        val nodes = if (rule != null) root.findAccessibilityNodeInfosByViewId(rule.viewId).orEmpty()
            else root.findAccessibilityNodeInfosByText(if (pending.candidate.weak) pending.candidate.label
                else if (pending.candidate.label.contains("广告") && !pending.candidate.label.contains("跳过")) "广告"
                else if (pending.candidate.label.contains("skip", ignoreCase = true)) "skip" else "跳过").orEmpty()
        try {
            for (node in nodes.take(8)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (node.packageName?.toString() != pending.pkg || node.isEditable ||
                    (!node.isVisibleToUser && !applicationCovered(root.windowId)) ||
                    !Rect.intersects(bounds, pending.bounds) || SkipTargetPolicy.score(
                        if (pending.candidate.weak) SkipRule.WEAK else SkipRule.STRONG,
                        bounds.toTargetBounds(), screenSize().x, screenSize().y) == 0) continue
                val matches = if (rule != null) node.viewIdResourceName == rule.viewId && nodeLabel(node) == pending.candidate.label
                    else SkipRule.match(skipNodeLabel(node)) >= (if (pending.candidate.weak) SkipRule.WEAK else SkipRule.STRONG)
                if (matches) return copyNode(node)
            }
            return null
        } finally { nodes.forEach(::recycleNode) }
    }

    private fun recordSplashSkip(pkg: String, label: String, ruleName: String? = null) {
        stopSplashScan()
        StatsStore.record(pkg, StatsStore.Kind.SPLASH)
        ProtectionStore.record(pkg, StatsStore.Kind.SPLASH, ruleName ?: "通用开屏规则")
        Log.i(TAG, "跳过开屏：\"$label\" @ $pkg")
        InterceptNotifier.notify(this, AppInfoHelper.label(this, pkg), StatsStore.Kind.SPLASH)
    }

    /** Query strong labels across the window; refreshed validation still requires live ad evidence. */
    private fun tryQuickSkip(pkg: String, covered: Boolean, active: AccessibilityNodeInfo?): Candidate? {
        val screen = screenSize()
        var gestureFallback: Candidate? = null
        fun candidateOf(node: AccessibilityNodeInfo): Candidate? {
            if (node.packageName?.toString() != pkg || !node.isEnabled) return null
            val label = skipNodeLabel(node)
            if (SkipRule.match(label) != SkipRule.STRONG) return null
            val rect = Rect().also(node::getBoundsInScreen)
            val score = scoreOf(SkipRule.STRONG, rect, screen)
            if (rect.isEmpty || score <= 0) return null
            if (node.isVisibleToUser) jumpGuard.onAdCandidate(pkg, label)
            return Candidate(copyNode(node), label, score, false)
        }
        fun click(node: AccessibilityNodeInfo): Candidate? {
            val candidate = candidateOf(node) ?: return null
            if (clickCandidate(candidate, pkg, covered)) {
                gestureFallback?.let { recycleNode(it.node) }
                gestureFallback = null
                return candidate
            }
            // Launch skip labels retain 2.2.0's own-coordinate fallback even without an accessible ad marker.
            if (!covered && gestureFallback == null && candidate.gestureAllowed &&
                (JumpAdRule.isAdSkip(candidate.label) || allowLaunchSkip(candidate.score))) gestureFallback = candidate
            else recycleNode(candidate.node)
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
        gestureFallback?.let { candidate ->
            try { submitGesture(candidate, pkg) } finally { recycleNode(candidate.node) }
        }
        return null
    }

    private fun findCandidates(
        pkg: String, allowWeak: Boolean, covered: Boolean,
        active: AccessibilityNodeInfo?, screenWindows: List<AccessibilityWindowInfo>, localOnly: Boolean = false
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
            if (localOnly) roots.values.flatMap { findLocalCandidates(it, pkg, screen, covered) }
            else roots.values.flatMap { findInTree(it, screen, weakAllowed, covered) }
                    .sortedWith(compareBy<Candidate> { it.weak }.thenByDescending { it.score })
        } finally {
            roots.values.forEach(::recycleNode)
        }
    }

    private fun findLocalCandidates(root: AccessibilityNodeInfo, pkg: String, screen: Point, covered: Boolean): List<Candidate> {
        val matches = mutableListOf<Candidate>()
        val age = splashSession.age(SystemClock.elapsedRealtime())
        for (rule in activeRules) {
            if (!rule.appliesTo(pkg, foregroundActivity, age)) continue
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(rule.viewId) }.getOrDefault(emptyList())
            try {
                for (node in nodes.take(8)) {
                    if (!node.isEnabled || node.isEditable || (!covered && !node.isVisibleToUser)) continue
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (rule.matches(node.packageName?.toString().orEmpty(), foregroundActivity, age,
                            node.viewIdResourceName, nodeLabel(node), bounds.toTargetBounds(), screen.x, screen.y)) {
                        matches.add(Candidate(copyNode(node), nodeLabel(node), 200, false, rule))
                    }
                }
            } finally { nodes.forEach(::recycleNode) }
        }
        return matches
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
        weakAllowed: Boolean,
        covered: Boolean
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>(4)
        var visited = 0
        var adHints = 0
        var standaloneAdHints = 0

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || visited >= 700) return
            visited++

            // 遮挡时只保留明确的跳过匹配供节点动作使用，不允许弱匹配或坐标点击。
            // A rendering host may omit its own visible flag while exposing visible child controls.
            val visible = node.isVisibleToUser
            val label = nodeLabel(node)

            if (label.isNotEmpty() && (visible || covered)) {
                val hintBounds = Rect().also(node::getBoundsInScreen)
                if (label.length <= 16 && hintBounds.centerY() < screen.y * 0.35f && SkipRule.hasAdEvidence(label)) adHints++
                if (SkipRule.isStandaloneAdMarker(label) && SkipTargetPolicy.smallTarget(hintBounds.toTargetBounds(), screen.x, screen.y)) standaloneAdHints++

                val buttonLabel = skipNodeLabel(node)
                val strength = SkipRule.match(buttonLabel)
                if (strength != SkipRule.NONE) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val score = scoreOf(strength, rect, screen)
                        if (score > 0) {
                            if (strength == SkipRule.STRONG && node.isVisibleToUser) {
                                node.packageName?.toString()?.let { jumpGuard.onAdCandidate(it, buttonLabel) }
                            }
                            candidates.add(
                                Candidate(copyNode(node), buttonLabel, score, strength == SkipRule.WEAK, treeEvidence = true)
                            )
                        }
                    }
                }
            }

            if (depth == 30) return
            for (i in 0 until node.childCount) {
                if (visited >= 700) break
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
            val allowed = if (candidate.weak) weakAllowed && adHints > 0 && visited <= SPLASH_MAX_NODES
                else JumpAdRule.isAdSkip(candidate.label) || allowLaunchSkip(candidate.score) ||
                    standaloneAdHints > 0 || (candidate.score != 80 && adHints > 0)
            if (!allowed) recycleNode(candidate.node)
            allowed
        }
    }

    /**
     * 位置打分。返回 0 表示"文案像但位置不像"，直接放弃，避免误点。
     */
    private fun scoreOf(strength: Int, rect: Rect, screen: Point): Int =
        SkipTargetPolicy.score(strength, rect.toTargetBounds(), screen.x, screen.y)

    private fun allowLaunchSkip(score: Int): Boolean =
        splashSession.allowPlainSkip(SystemClock.elapsedRealtime(), Prefs.splashWindowMs, score)

    private fun Rect.toTargetBounds() = SkipTargetPolicy.Bounds(left, top, right, bottom)

    private fun validatedBounds(candidate: Candidate, pkg: String, covered: Boolean): Rect? {
        if (!Prefs.skipSplash || !ProtectionStore.allows(pkg) || !isScreenInteractive() || AppInfoHelper.isProtectedFromAutomation(this, pkg) ||
            embeddedPagePackage == pkg || splashSession.packageName != pkg) return null
        if (!candidate.node.refresh() || candidate.node.packageName?.toString() != pkg ||
            !candidate.node.isEnabled || candidate.node.isEditable || (!covered && !candidate.node.isVisibleToUser)) return null
        val self = Rect().also(candidate.node::getBoundsInScreen)
        if (failedSkips.any { it.node == candidate.node && it.label == skipNodeLabel(candidate.node) }) return null
        candidate.rule?.let { rule ->
            val screen = screenSize()
            return self.takeIf { rule in ProtectionStore.rules && rule.matches(pkg, foregroundActivity,
                splashSession.age(SystemClock.elapsedRealtime()), candidate.node.viewIdResourceName,
                nodeLabel(candidate.node), self.toTargetBounds(), screen.x, screen.y) }
        }
        // 页面可能已更新；旧节点不能仅凭之前读到的文案继续点击。
        val label = skipNodeLabel(candidate.node)
        val strength = SkipRule.match(label)
        if (strength == SkipRule.NONE || (!candidate.weak && strength != SkipRule.STRONG) ||
            (covered && strength != SkipRule.STRONG)) return null
        val score = scoreOf(strength, self, screenSize())
        if (score <= 0) return null
        if ((candidate.weak || (!JumpAdRule.isAdSkip(label) && !allowLaunchSkip(score))) && !hasLiveAdMarker(pkg, candidate.node.windowId, covered,
                topOnly = candidate.weak, standaloneOnly = score == 80, treeEvidence = candidate.treeEvidence)) return null
        return self
    }

    private fun hasLiveAdMarker(pkg: String, windowId: Int, covered: Boolean, topOnly: Boolean,
        standaloneOnly: Boolean, treeEvidence: Boolean): Boolean {
        val screenWindows = windows.orEmpty()
        val root = screenWindows.firstOrNull { it.id == windowId }?.root ?: rootInActiveWindow
            ?: run { recycleWindows(screenWindows); return false }
        try {
            if (root.packageName?.toString() != pkg || root.windowId != windowId || (!covered && hasSystemCover(windowId, screenWindows))) return false
            val screen = screenSize()
            fun marker(node: AccessibilityNodeInfo): Boolean {
                val bounds = Rect().also(node::getBoundsInScreen)
                val text = nodeLabel(node)
                return node.packageName?.toString() == pkg && node.windowId == windowId && (node.isVisibleToUser || covered) &&
                    ((!standaloneOnly && text.length <= 16 && SkipRule.hasAdEvidence(text) && bounds.centerY() < screen.y * 0.35f) ||
                        (!topOnly && SkipRule.isStandaloneAdMarker(text) && SkipTargetPolicy.smallTarget(bounds.toTargetBounds(), screen.x, screen.y)))
            }
            for (query in if (topOnly) arrayOf("广告") else arrayOf("广告", "ad")) {
                val markers = root.findAccessibilityNodeInfosByText(query).orEmpty()
                try { if (markers.take(8).any(::marker)) return true }
                finally { markers.forEach(::recycleNode) }
            }
            // Only a candidate from a budgeted tree pass may repeat that pass for live proof.
            // SDK virtual labels can be readable as nodes while their text-search API returns nothing.
            if (!treeEvidence) return false
            var visited = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
                if (depth > 30 || visited >= (if (topOnly) SPLASH_MAX_NODES else 700)) return false
                visited++
                if (marker(node)) return true
                if (depth == 30) return false
                for (index in 0 until node.childCount) {
                    val child = node.getChild(index) ?: continue
                    try { if (walk(child, depth + 1)) return true } finally { recycleNode(child) }
                    if (visited >= (if (topOnly) SPLASH_MAX_NODES else 700)) break
                }
                return false
            }
            return walk(root, 0)
        } finally { recycleNode(root); recycleWindows(screenWindows) }
    }

    private fun clickCandidate(candidate: Candidate, pkg: String, covered: Boolean): Boolean {
        candidate.gestureAllowed = false
        val self = validatedBounds(candidate, pkg, covered) ?: return false
        candidate.gestureAllowed = true

        // 优先点节点自身。向上找可点击父节点时，必须确认父节点没有明显放大成容器，
        // 否则会点到容器中心 —— 广告层里「×」和「⋮」常挂在同一个可点击容器下，就会点偏。
        var node: AccessibilityNodeInfo? = copyNode(candidate.node)
        var depth = 0
        var ownAction = false
        try {
            while (node != null && depth < 4) {
                val hasClickAction = node.isClickable || node.actionList.any {
                    it.id == AccessibilityNodeInfo.ACTION_CLICK
                }
                if (depth == 0) ownAction = hasClickAction && !candidate.weak
                if (node.packageName?.toString() == pkg && node.windowId == candidate.node.windowId &&
                    node.isEnabled && hasClickAction && (node.isVisibleToUser || covered)) {
                    val parentRect = Rect()
                    node.getBoundsInScreen(parentRect)
                    val precise = SkipTargetPolicy.preciseParent(self.toTargetBounds(), parentRect.toTargetBounds())
                    if (precise && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        beginSkipVerification(candidate, pkg, gesture = false)
                        return true
                    }
                    // A large ancestor cannot be clicked. It need not veto the small control's own touch target.
                    if (!precise && !ownAction) candidate.gestureAllowed = false
                }
                val parent = node.parent
                recycleNode(node)
                node = parent
                depth++
            }
        } finally {
            node?.let(::recycleNode)
        }

        return false
    }

    private fun submitGesture(candidate: Candidate, pkg: String) {
        if (!candidate.gestureAllowed) return
        val checkedAt = SystemClock.elapsedRealtime()
        val bounds = validatedBounds(candidate, pkg, covered = false) ?: return
        if (!canTapApplication(pkg, candidate.node.windowId, bounds.centerX(), bounds.centerY(), checkedAt)) return
        val generation = ++actionGeneration
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0L, 60L)).build()
        gestureInFlight = true
        beginSkipVerification(candidate, pkg, gesture = true, schedule = false)
        fun complete(success: Boolean) {
            if (generation != actionGeneration || !gestureInFlight) return
            gestureInFlight = false
            gestureTimeout?.let(handler::removeCallbacks)
            gestureTimeout = null
            if (success && Prefs.skipSplash && ProtectionStore.allows(pkg) && splashSession.packageName == pkg && isScreenInteractive()) {
                handler.postDelayed(verifySkip, 80L)
            } else {
                clearPendingSkip()
                scheduleSplashScan(100L)
            }
        }
        gestureTimeout = Runnable { complete(false) }.also { handler.postDelayed(it, 700L) }
        val accepted = runCatching {
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = complete(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = complete(false)
            }, handler)
        }.getOrDefault(false)
        if (!accepted) complete(false)
    }

    /** 触摸会落到最上层窗口，提交手势前再次确认目标窗口及点击点没有被系统界面遮住。 */
    private fun canTapApplication(pkg: String, windowId: Int, x: Int, y: Int, checkedAt: Long): Boolean {
        // 节点遍历期间系统窗口可能已变化，而事件仍在主线程队列里，不能信任此前的窗口缓存。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clearCache()
        } else if (SystemClock.elapsedRealtime() - checkedAt > 250L) {
            return false
        }
        val active = rootInActiveWindow ?: return false
        val screenWindows = windows.orEmpty()
        try {
            if (active.packageName?.toString() != pkg || active.windowId != windowId) return false
            val target = screenWindows.firstOrNull { it.id == windowId } ?: return false
            if (!Rect().also(target::getBoundsInScreen).contains(x, y)) return false
            return screenWindows.none { window ->
                val bounds = Rect().also(window::getBoundsInScreen)
                window.layer > target.layer && bounds.contains(x, y)
            }
        } finally { recycleNode(active); recycleWindows(screenWindows) }
    }

    /** Read a split countdown only inside the same precise, clickable skip control. */
    private fun skipNodeLabel(node: AccessibilityNodeInfo): String {
        val label = SkipRule.buttonLabel(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
        if (SkipRule.match(label) != SkipRule.STRONG || JumpAdRule.isAdSkip(label)) return label
        val parent = node.parent ?: return label
        try {
            val screen = screenSize()
            val self = Rect().also(node::getBoundsInScreen)
            val box = Rect().also(parent::getBoundsInScreen)
            if (parent.packageName != node.packageName || parent.windowId != node.windowId ||
                !parent.isEnabled || parent.childCount > 6 ||
                !SkipTargetPolicy.smallTarget(box.toTargetBounds(), screen.x, screen.y) ||
                !SkipTargetPolicy.preciseParent(self.toTargetBounds(), box.toTargetBounds()) ||
                !(parent.isClickable || parent.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK })) return label
            for (index in 0 until parent.childCount) {
                val sibling = parent.getChild(index) ?: continue
                try {
                    val bounds = Rect().also(sibling::getBoundsInScreen)
                    if (sibling != node && sibling.packageName == node.packageName && sibling.windowId == node.windowId &&
                        sibling.isEnabled && sibling.isVisibleToUser && box.contains(bounds)) {
                        SkipRule.withCountdown(label, nodeLabel(sibling))?.let { return it }
                    }
                } finally { recycleNode(sibling) }
            }
            return label
        } finally { recycleNode(parent) }
    }

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
            "com.tencent.mm",
            "com.tencent.mobileqq"
        )

        /** 这些包名的事件会污染"前台应用"的判断，直接忽略 */
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui"
        )
    }
}

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
import com.adsbanner.clean.core.SkipRetryPolicy
import com.adsbanner.clean.core.SplashClosePolicy
import com.adsbanner.clean.core.SplashSkipPolicy
import com.adsbanner.clean.core.SplashCountdownTracker
import com.adsbanner.clean.core.SplashSkipConfirmationPolicy
import com.adsbanner.clean.core.KnownSplashControls
import com.adsbanner.clean.core.XunleiSplashPolicy
import com.adsbanner.clean.core.CainiaoSplashPolicy
import com.adsbanner.clean.core.VerifiedActivityWindows
import com.adsbanner.clean.core.SystemBarTargetPolicy
import com.adsbanner.clean.core.SplashScanSession
import com.adsbanner.clean.core.SplashScanCadence
import com.adsbanner.clean.core.SplashResumeTracker
import com.adsbanner.clean.core.SplashForegroundCheck
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
        val treeEvidence: Boolean = false,
        var cainiaoContainerOwned: Boolean = false
    )

    private data class PendingSkip(val candidate: Candidate, val pkg: String, val bounds: Rect,
        val gesture: Boolean, val startedAt: Long, val windowId: Int, val startedUptime: Long,
        val activity: String, val absentAt: Long? = null, val resultOnly: Boolean = false)
    private data class FailedSkip(val node: AccessibilityNodeInfo, val label: String, val failures: Int, val failedAt: Long)
    private data class ValidatedTarget(val bounds: Rect, val nativeOnly: Boolean, val coordinateOnly: Boolean = false)
    private enum class SkipAction { DIRECT, COORDINATE_ONLY, UNSAFE }
    private data class AdControlContext(val id: String? = null, val forbidden: Boolean = false)
    private var pendingSkip: PendingSkip? = null
    private val failedSkips = ArrayList<FailedSkip>(4)
    private val verifySkip = Runnable { verifyPendingSkip() }

    private val handler = Handler(Looper.getMainLooper())
    private val screenState = ScreenStateGate()
    private val splashResume = SplashResumeTracker()
    private val splashCountdown = SplashCountdownTracker()
    private val foregroundCheck = SplashForegroundCheck()
    private val reconcileForeground = Runnable { reconcileSplashForeground() }
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
    private var foregroundActivityWindowId = -1
    private val verifiedActivityWindows = VerifiedActivityWindows()
    private var coordinateWindowId = -1
    private var coordinateReadyAt = 0L
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
        if (pkg != null && pkg == splashSession.packageName &&
            (type == AccessibilityEvent.TYPE_VIEW_CLICKED || type == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
                type == AccessibilityEvent.TYPE_VIEW_SCROLLED) &&
            !(type == AccessibilityEvent.TYPE_VIEW_CLICKED && isPendingSkipClick(event))) {
            if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED && KnownSplashControls.hasControls(pkg) && pendingSkip == null) {
                splashSession.restrictToKnownControls()
                scheduleSplashScan(0L)
            } else {
                val keepResult = canFinishPendingAfterInteraction(event)
                splashSession.onUserInteraction()
                stopSplashScan(preservePendingResult = keepResult)
            }
        }
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
            requestSplashForeground(null)
            refreshSplashCache()
            scheduleSplashScan(0L)
            return
        }
        if (pkg == null) return
        if (pkg == packageName) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                !splashSession.awaitingTarget(SystemClock.elapsedRealtime())) {
                val active = rootInActiveWindow
                try {
                    if (active == null || active.packageName?.toString() == packageName) {
                        stopSplashScan()
                        foregroundPkg = null
                        splashResume.clear()
                    }
                } finally { active?.let(::recycleNode) }
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
        splashCountdown.invalidateProofs()
        pauseAdGuard.stop()
    }

    // ------------------------------------------------------------------ 窗口切换

    private fun onWindowChanged(pkg: String, activity: String = "", windowId: Int = -1, fromActiveWindow: Boolean = false) {
        val prev = foregroundPkg
        val now = SystemClock.elapsedRealtime()
        val declaredEvent = AppInfoHelper.isDeclaredActivity(this, pkg, activity)
        if (declaredEvent) verifiedActivityWindows.remember(pkg, windowId, activity)
        val page = if (declaredEvent) activity else verifiedActivityWindows.activity(pkg, windowId).orEmpty()
        val declared = page.isNotEmpty()
        if (!fromActiveWindow) {
            val active = rootInActiveWindow
            val current = active?.packageName?.toString()
            val currentWindowId = active?.windowId
            active?.let(::recycleNode)
            if (current != null && (current != pkg || (windowId >= 0 && currentWindowId != windowId))) {
                requestSplashForeground(pkg)
                return
            }
        }
        val confirmedWindowChanged = windowId >= 0 && windowId != coordinateWindowId && (declared || fromActiveWindow)
        if (pkg != prev || (declared && page != foregroundActivity) ||
            confirmedWindowChanged) {
            // Accessibility bounds may already be final while the entering window is still moving.
            // Late View events from the departing window cannot restart this grace period.
            coordinateWindowId = windowId
            coordinateReadyAt = now + 350L
        }
        val entry = if (pkg != prev || declared || fromActiveWindow) {
            splashResume.enter(pkg, page.takeIf { declared }, windowId.takeIf { it >= 0 }, now)
        } else {
            splashResume.onTransientWindow(pkg, windowId)
            SplashResumeTracker.Entry.NONE
        }

        if (pkg != prev) {
            foregroundPkg = pkg
            foregroundActivity = ""
            foregroundActivityWindowId = -1
            activeRules = ProtectionStore.rules.filter { it.enabled && it.packageName == pkg }
        }

        // Initial activity transitions retain the original launch deadline; weak matching still ends at 3s.
        // Wake/share returns get a new finite, strong-only window even if the package is unchanged.
        val earlyLaunch = (entry == SplashResumeTracker.Entry.ACTIVITY || entry == SplashResumeTracker.Entry.RETURN) &&
            splashSession.packageName == pkg &&
            !splashSession.resumed && splashSession.pending(now, Prefs.splashWindowMs)
        val awaitingResult = pkg == prev && pendingSkip?.let {
            it.pkg == pkg && now - it.startedAt < SplashSkipConfirmationPolicy.WINDOW_SETTLE_MS + SplashSkipConfirmationPolicy.ABSENCE_MS
        } == true
        val dedicatedSplashEntry = declared && page != foregroundActivity && KnownSplashControls.isDedicatedActivity(pkg, page)
        val mainReturn = declared && foregroundActivity.isNotEmpty() && page != foregroundActivity && KnownSplashControls.isMainActivity(pkg, page) &&
            !splashResume.allowsScan(entry)
        val canStartScan = splashResume.allowsScan(entry) || dedicatedSplashEntry || mainReturn
        if (pkg != prev || (canStartScan && !earlyLaunch && !awaitingResult)) {
            stopSplashScan()
            if (canStartScan && Prefs.skipSplash && ProtectionStore.allows(pkg) && embeddedPagePackage != pkg && !AppInfoHelper.isHomeOrSystem(this, pkg)) {
                splashSession.start(pkg, now, resumed = entry != SplashResumeTracker.Entry.LAUNCH, knownControlsOnly = mainReturn)
                skipTryCount = 0
            }
        }

        if (declared) {
            foregroundActivity = page
            foregroundActivityWindowId = windowId
        }

        scheduleSplashScan(0L)
    }

    private fun onContentChanged(pkg: String) {
        // Some apps expose their launch only through content/window-list changes.
        if (Prefs.skipSplash && foregroundPkg != pkg) requestSplashForeground(pkg)
        if (splashSession.packageName == pkg) {
            scheduleSplashScan(0L)
        }
    }

    private fun requestSplashForeground(pkg: String?) {
        if (!Prefs.skipSplash) return
        foregroundCheck.request(pkg, SystemClock.elapsedRealtime())
        handler.removeCallbacks(reconcileForeground)
        handler.post(reconcileForeground)
    }

    private fun reconcileSplashForeground() {
        val now = SystemClock.elapsedRealtime()
        if (!Prefs.skipSplash || ProtectionStore.paused || !isScreenInteractive()) {
            foregroundCheck.clear()
            return
        }
        if (foregroundCheck.take(now)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) clearCache()
            val root = rootInActiveWindow
            try {
                val pkg = root?.packageName?.toString()
                if (pkg == packageName && !splashSession.awaitingTarget(now)) {
                    stopSplashScan()
                    foregroundPkg = null
                    foregroundActivity = ""
                    foregroundActivityWindowId = -1
                    splashResume.clear()
                } else if (pkg != null && pkg != packageName && pkg !in IGNORED_PACKAGES && pkg !in imePackages &&
                    pkg != embeddedPagePackage && foregroundCheck.accepts(pkg) &&
                    !(splashSession.packageName != pkg && splashSession.awaitingTarget(now))) {
                    foregroundCheck.complete()
                    onWindowChanged(pkg, windowId = root.windowId, fromActiveWindow = true)
                }
            } finally { root?.let(::recycleNode) }
        }
        foregroundCheck.retryDelay(SystemClock.elapsedRealtime())?.let {
            handler.removeCallbacks(reconcileForeground)
            handler.postDelayed(reconcileForeground, it)
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

    private fun stopSplashScan(preservePendingResult: Boolean = false) {
        val keepResult = preservePendingResult && pendingSkip != null
        if (keepResult) pendingSkip = pendingSkip?.let {
            it.copy(resultOnly = true, candidate = it.candidate.copy(gestureAllowed = false))
        }
        splashCountdown.clear()
        splashSession.packageName?.let(splashResume::finishScan)
        handler.removeCallbacks(reconcileForeground)
        foregroundCheck.clear()
        if (!keepResult) clearPendingSkip()
        failedSkips.forEach { recycleNode(it.node) }
        failedSkips.clear()
        if (!keepResult) {
            actionGeneration++
            gestureInFlight = false
            gestureTimeout?.let(handler::removeCallbacks)
            gestureTimeout = null
        }
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

    internal fun isPendingSkipClick(event: AccessibilityEvent): Boolean {
        val pending = pendingSkip ?: return false
        val source = event.source ?: return false
        try {
            if (source.packageName?.toString() != pending.pkg || source.windowId != pending.windowId) return false
            return source == pending.candidate.node || SkipTargetPolicy.preciseParent(pending.bounds.toTargetBounds(),
                Rect().also(source::getBoundsInScreen).toTargetBounds()) ||
                SplashSkipConfirmationPolicy.ownGestureParentClick(pending.gesture, pending.startedUptime, event.eventTime,
                    pending.bounds.toTargetBounds(), Rect().also(source::getBoundsInScreen).toTargetBounds())
        } finally { recycleNode(source) }
    }

    /** New-page layout/scroll events stop scanning, but cannot erase an already disappeared skip control. */
    private fun canFinishPendingAfterInteraction(event: AccessibilityEvent? = null): Boolean {
        val pending = pendingSkip ?: return false
        if (SystemClock.elapsedRealtime() - pending.startedAt >
            SplashSkipConfirmationPolicy.WINDOW_SETTLE_MS + SplashSkipConfirmationPolicy.ABSENCE_MS) return false
        if (event?.packageName?.toString() == pending.pkg) {
            val source = event.source
            val related = try {
                source == null || (source.packageName?.toString() == pending.pkg && source.windowId == pending.windowId &&
                    Rect().also(source::getBoundsInScreen).contains(pending.bounds))
            } finally { source?.let(::recycleNode) }
            if (SplashSkipConfirmationPolicy.canKeepSubmittedResult(pending.startedUptime, event.eventTime,
                    pending.windowId, event.windowId, related)) return true
        }
        val root = resultRoot() ?: return false
        try {
            if (root.packageName?.toString() != pending.pkg || applicationCovered(root.windowId)) return false
            // Window events can precede the declared Activity event. Keep only the pending result
            // until that identity is resolved; stopping the scan prevents any further click.
            if (root.windowId != pending.windowId) return true
            if (pendingTargetMayRemain(root, pending)) return false
            val replacement = findReplacementSkip(root, pending)
            return if (replacement == null) true else { recycleNode(replacement); false }
        } finally { recycleNode(root) }
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
        if (pendingSkip != null && !gestureInFlight) {
            verifyPendingSkip()
            return
        }
        if (gestureInFlight) {
            scheduleSplashScan(100L)
            return
        }
        if (!splashSession.pending(now, Prefs.splashWindowMs)) {
            stopSplashScan()
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
            if (covered) splashCountdown.invalidateProofs()
            if (!splashSession.pending(now, Prefs.splashWindowMs) || skipTryCount >= 384) {
                stopSplashScan()
                return
            }
            // 活动根节点暂缺时也检查应用窗口/事件子树。遮挡期间只提交节点动作。
            attemptSkip(pkg, now, covered, active, screenWindows)
            scheduleSplashScan(if (covered) splashCadence.pollDelay(splashSession.age(now), true)
                else splashCountdown.nextCheckDelay(SystemClock.elapsedRealtime()) ?: splashCadence.pollDelay(splashSession.age(now), false))
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
        if (splashSession.knownControlsOnly) {
            tryQuickSkip(pkg, covered, active)?.let { recycleNode(it.node) }
            return
        }

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

        // 普通关闭只在启动初期参与；叉号另按原启动预算和独立广告证据校验。
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

    private fun beginSkipVerification(candidate: Candidate, pkg: String, gesture: Boolean, schedule: Boolean = true,
        startedAt: Long = SystemClock.elapsedRealtime(), startedUptime: Long = SystemClock.uptimeMillis()) {
        clearPendingSkip()
        pendingSkip = PendingSkip(candidate.copy(node = copyNode(candidate.node)), pkg,
            Rect().also(candidate.node::getBoundsInScreen), gesture, startedAt,
            candidate.node.windowId, startedUptime, knownSplashActivity(candidate.node))
        if (schedule) handler.postDelayed(verifySkip, 80L)
    }

    private fun clearPendingSkip() {
        handler.removeCallbacks(verifySkip)
        pendingSkip?.let { recycleNode(it.candidate.node) }
        pendingSkip = null
    }

    /** Accepted accessibility actions and completed gestures do not prove the advertisement closed. */
    private fun resultRoot(): AccessibilityNodeInfo? {
        val cached = rootInActiveWindow
        val screenWindows = windows.orEmpty()
        try {
            val active = screenWindows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            if (active != null && active.id != cached?.windowId) {
                active.root?.let { current -> cached?.let(::recycleNode); return current }
            }
            return cached
        } finally { recycleWindows(screenWindows) }
    }

    private fun verifyPendingSkip(allowFallback: Boolean = true) {
        val pending = pendingSkip ?: return
        if (gestureInFlight) return
        if (!Prefs.skipSplash || !ProtectionStore.allows(pending.pkg) || !isScreenInteractive() ||
            foregroundPkg != pending.pkg) { clearPendingSkip(); return }
        // Re-read the window after a disappearance; a cached empty tree can hide a replacement control.
        val originalActivity = pending.activity.ifEmpty { verifiedActivityWindows.activity(pending.pkg, pending.windowId).orEmpty() }
        val activityChanged = originalActivity.isNotEmpty() && foregroundActivity.isNotEmpty() && originalActivity != foregroundActivity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (pending.absentAt != null || splashCacheDirty || activityChanged)) {
            splashCacheDirty = false
            clearCache()
        }
        val root = resultRoot()
        try {
            val pkg = root?.packageName?.toString()
            val elapsed = SystemClock.elapsedRealtime() - pending.startedAt
            val node = pending.candidate.node
            val refreshed = node.refresh()
            val sameNode = refreshed && node.packageName?.toString() == pending.pkg
            val matches = sameNode && pendingOriginalLabelMatches(node, pending)
            if (pkg != pending.pkg) {
                if (sameNode && !matches && (pkg == null || pkg in IGNORED_PACKAGES || pkg in imePackages)) {
                    confirmPendingDisappearance()
                    return
                }
                pendingSkip = pending.copy(absentAt = null)
                if (elapsed < SplashSkipConfirmationPolicy.RESULT_SETTLE_MS && (pkg == null || pkg in IGNORED_PACKAGES || pkg in imePackages)) {
                    handler.removeCallbacks(verifySkip)
                    handler.postDelayed(verifySkip, 100L)
                } else { clearPendingSkip(); scheduleSplashScan(100L) }
                return
            }
            val stillTarget = pendingTargetMayRemain(checkNotNull(root), pending, refreshed, matches)
            if (!stillTarget) {
                // Countdown widgets can replace their node every tick; look for the same control before counting.
                val replacement = findReplacementSkip(checkNotNull(root), pending)
                if (replacement != null) {
                    recycleNode(node)
                    pendingSkip = pending.copy(candidate = pending.candidate.copy(node = replacement), absentAt = null)
                } else {
                    confirmPendingDisappearance()
                    return
                }
            } else if (pending.absentAt != null) pendingSkip = pending.copy(absentAt = null)
            if (SplashSkipConfirmationPolicy.awaitWindowResult(elapsed, root.windowId == pending.windowId)) {
                handler.removeCallbacks(verifySkip)
                handler.postDelayed(verifySkip, 100L)
                return
            }
            if (sameNode && matches && !node.isEnabled && elapsed < 800L) {
                handler.removeCallbacks(verifySkip)
                handler.postDelayed(verifySkip, 100L)
                return
            }
            // Native actions can also close asynchronously; do not retry before their result settles.
            val settleMs = SplashSkipConfirmationPolicy.RESULT_SETTLE_MS
            if (!allowFallback || elapsed < settleMs) {
                handler.removeCallbacks(verifySkip)
                handler.postDelayed(verifySkip, (settleMs - elapsed).coerceIn(40L, 100L))
                return
            }
            val current = pendingSkip ?: return
            pendingSkip = null // Transfer ownership until submission has copied its node.
            handler.removeCallbacks(verifySkip)
            try {
                if (!current.resultOnly && !current.gesture && current.candidate.gestureAllowed) submitGesture(current.candidate, current.pkg)
                if (pendingSkip == null) {
                    if (current.gesture || !current.candidate.gestureAllowed) {
                        val label = skipNodeLabel(current.candidate.node)
                        val previous = failedSkips.firstOrNull { it.node == current.candidate.node && it.label == label }
                        val failures = (previous?.failures ?: 0) + 1
                        failedSkips.removeAll { failed ->
                            (failed.node == current.candidate.node).also { if (it) recycleNode(failed.node) }
                        }
                        if (failedSkips.size >= 8) recycleNode(failedSkips.removeAt(0).node)
                        failedSkips.add(FailedSkip(copyNode(current.candidate.node), label, failures, SystemClock.elapsedRealtime()))
                    }
                    scheduleSplashScan(120L)
                }
            } finally { recycleNode(current.candidate.node) }
        } finally { root?.let(::recycleNode) }
    }

    private fun confirmPendingDisappearance() {
        val pending = pendingSkip ?: return
        val now = SystemClock.elapsedRealtime()
        if (pending.absentAt?.let { now - it >= SplashSkipConfirmationPolicy.ABSENCE_MS } == true) {
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

    private fun pendingTargetMayRemain(root: AccessibilityNodeInfo, pending: PendingSkip,
        refreshed: Boolean = pending.candidate.node.refresh(), knownMatches: Boolean? = null): Boolean {
        val node = pending.candidate.node
        val matches = knownMatches ?: (refreshed && node.packageName?.toString() == pending.pkg && pendingOriginalLabelMatches(node, pending))
        val sameWindow = root.windowId == node.windowId
        val originalActivity = pending.activity.ifEmpty { verifiedActivityWindows.activity(pending.pkg, pending.windowId).orEmpty() }
        val currentActivity = verifiedActivityWindows.activity(pending.pkg, root.windowId)
        val activityChanged = originalActivity.isNotEmpty() && currentActivity != null && currentActivity != originalActivity
        var originalWindowVisible = false
        if (!sameWindow) {
            if (activityChanged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) clearCache()
            val screenWindows = windows.orEmpty()
            try {
                val original = screenWindows.firstOrNull { it.id == node.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                val current = screenWindows.firstOrNull { it.id == root.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                originalWindowVisible = SplashSkipConfirmationPolicy.originalWindowMayBeVisible(
                    original?.let { Rect().also(it::getBoundsInScreen).toTargetBounds() },
                    current?.let { Rect().also(it::getBoundsInScreen).toTargetBounds() },
                    current != null && original != null && current.isActive && current.layer > original.layer,
                    activityChanged)
            } finally { recycleWindows(screenWindows) }
        }
        return SplashSkipConfirmationPolicy.targetMayRemain(matches, node.isVisibleToUser, sameWindow,
            originalWindowVisible, sameWindow && applicationCovered(root.windowId), sameWindow && isStatusBarTarget(node),
            activityChanged = activityChanged)
    }

    private fun isStatusBarTarget(node: AccessibilityNodeInfo,
        suppliedWindows: List<AccessibilityWindowInfo>? = null): Boolean {
        val pkg = node.packageName?.toString() ?: return false
        if (pkg != splashSession.packageName && pkg != pendingSkip?.pkg) return false
        val target = Rect().also(node::getBoundsInScreen).toTargetBounds()
        if (target.top >= screenSize().y * 0.12f || target.bottom <= 0) return false
        val screenWindows = suppliedWindows ?: windows.orEmpty()
        try {
            val app = screenWindows.firstOrNull { it.id == node.windowId && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?: return false
            if (hasSystemCover(app.id, screenWindows)) return false
            val appBounds = Rect().also(app::getBoundsInScreen).toTargetBounds()
            return screenWindows.any { window ->
                window.type == AccessibilityWindowInfo.TYPE_SYSTEM && window.layer > app.layer &&
                    SystemBarTargetPolicy.overlapsStatusBar(target, appBounds,
                        Rect().also(window::getBoundsInScreen).toTargetBounds())
            }
        } finally { if (suppliedWindows == null) recycleWindows(screenWindows) }
    }

    private fun findReplacementSkip(root: AccessibilityNodeInfo, pending: PendingSkip): AccessibilityNodeInfo? {
        val rule = pending.candidate.rule
        if (rule == null && pending.candidate.cainiaoContainerOwned) {
            findCainiaoReplacement(root, pending)?.let { return it }
        }
        val knownId = pending.candidate.node.viewIdResourceName?.takeIf {
            KnownSplashControls.isControlId(pending.pkg, it) || SkipRule.isAdSkipResourceId(it)
        }
        val iconId = pending.candidate.node.viewIdResourceName?.takeIf {
            pending.candidate.weak && SkipRule.isCloseIcon(pending.candidate.label) && SkipRule.isAdCloseResourceId(it)
        }
        val nodes = if (rule != null || iconId != null || knownId != null) root.findAccessibilityNodeInfosByViewId(rule?.viewId ?: iconId ?: knownId!!).orEmpty()
            else root.findAccessibilityNodeInfosByText(if (pending.candidate.weak) pending.candidate.label
                else if (pending.candidate.label.contains("广告") && !pending.candidate.label.contains("跳过")) "广告"
                else if (pending.candidate.label.contains("skip", ignoreCase = true)) "skip" else "跳过").orEmpty()
        try {
            for (node in nodes.take(8)) {
                val bounds = Rect().also(node::getBoundsInScreen)
                if (node.packageName?.toString() != pending.pkg || node.isEditable ||
                    (!node.isVisibleToUser && !applicationCovered(root.windowId) && !isStatusBarTarget(node)) ||
                    !Rect.intersects(bounds, pending.bounds) || SkipTargetPolicy.score(
                        if (pending.candidate.weak) SkipRule.WEAK else SkipRule.STRONG,
                        bounds.toTargetBounds(), screenSize().x, screenSize().y,
                        closeIcon = pending.candidate.weak && SkipRule.isCloseIcon(pending.candidate.label)) == 0) continue
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

    /** Query action labels across the window; text alone never authorizes a tap. */
    private fun tryQuickSkip(pkg: String, covered: Boolean, active: AccessibilityNodeInfo?): Candidate? {
        val screen = screenSize()
        var gestureFallback: Candidate? = null
        fun candidateOf(node: AccessibilityNodeInfo): Candidate? {
            if (node.packageName?.toString() != pkg) return null
            val raw = SkipRule.buttonLabel(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
            if (SkipRule.match(raw) != SkipRule.STRONG && !KnownSplashControls.isControlId(pkg, node.viewIdResourceName)) return null
            // Entering-window bounds can be cached before the control reaches its visible position.
            if (!node.refresh() || node.packageName?.toString() != pkg || !node.isEnabled) return null
            val label = skipNodeLabel(node)
            if (SkipRule.match(label) != SkipRule.STRONG) return null
            val rect = Rect().also(node::getBoundsInScreen)
            val score = scoreOf(SkipRule.STRONG, rect, screen)
            if (rect.isEmpty || score <= 0) return null
            if (splashSession.knownControlsOnly) {
                val context = namedAdControl(node, label, rect)
                if (context.forbidden || !KnownSplashControls.isControlId(pkg, context.id)) return null
            }
            return Candidate(copyNode(node), label, score, false)
        }
        fun click(node: AccessibilityNodeInfo): Candidate? {
            val candidate = candidateOf(node) ?: return null
            if (clickCandidate(candidate, pkg, covered)) {
                gestureFallback?.let { recycleNode(it.node) }
                gestureFallback = null
                return candidate
            }
            if (!covered && gestureFallback == null && candidate.gestureAllowed) gestureFallback = candidate
            else recycleNode(candidate.node)
            return null
        }
        fun query(root: AccessibilityNodeInfo): Candidate? {
            for (id in KnownSplashControls.queryIds(pkg)) {
                val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrDefault(emptyList())
                try {
                    for (node in nodes.take(8)) {
                        click(node)?.let { return it }
                        if (node.childCount in 1..6) {
                            val children = node.findAccessibilityNodeInfosByText("跳过").orEmpty()
                            try { for (child in children.take(6)) click(child)?.let { return it } }
                            finally { children.forEach(::recycleNode) }
                        }
                    }
                }
                finally { nodes.forEach(::recycleNode) }
            }
            if (splashSession.knownControlsOnly) return null
            // 查询在目标应用内执行，避免为了一个按钮逐个跨进程读取数百个无关节点。
            for (text in arrayOf("跳过", "skip", "关闭广告", "跳")) {
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
            else roots.values.flatMap { findInTree(it, screen, weakAllowed, covered, screenWindows) }
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
     * 普通关闭限制启动时段、页面规模和广告证据；叉号另查角落位置及相邻独立广告标记。
     * 容器内页与宿主应用的弱匹配限制，避免将导航控件当成广告关闭按钮。
     */
    private fun findInTree(
        root: AccessibilityNodeInfo,
        screen: Point,
        weakAllowed: Boolean,
        covered: Boolean,
        screenWindows: List<AccessibilityWindowInfo>
    ): List<Candidate> {
        val candidates = ArrayList<Candidate>(4)
        var visited = 0
        var adHints = 0
        val adMarkers = ArrayList<Pair<String, SkipTargetPolicy.Bounds>>(4)

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 30 || visited >= 700) return
            visited++

            // 遮挡时只保留明确的跳过匹配供节点动作使用，不允许弱匹配或坐标点击。
            // A rendering host may omit its own visible flag while exposing visible child controls.
            val visible = node.isVisibleToUser
            val label = nodeLabel(node)
            val buttonLabel = skipNodeLabel(node)

            if ((label.isNotEmpty() || buttonLabel.isNotEmpty()) &&
                (visible || covered || isStatusBarTarget(node, screenWindows))) {
                val hintBounds = Rect().also(node::getBoundsInScreen)
                if (label.length <= 16 && hintBounds.centerY() < screen.y * 0.35f && SkipRule.hasAdEvidence(label)) adHints++
                if ((SkipRule.isStandaloneAdMarker(label) || XunleiSplashPolicy.isMemberAdMarker(root.packageName?.toString().orEmpty(), label)) &&
                    SkipTargetPolicy.smallTarget(hintBounds.toTargetBounds(), screen.x, screen.y)) {
                    if (adMarkers.size < 16 && node.packageName == root.packageName && node.windowId == root.windowId)
                        adMarkers.add(label to hintBounds.toTargetBounds())
                }

                val strength = SkipRule.match(buttonLabel)
                if (strength != SkipRule.NONE) {
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    if (rect.width() > 0 && rect.height() > 0) {
                        val score = scoreOf(strength, rect, screen, buttonLabel)
                        if (score > 0) {
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
            val allowed = if (candidate.weak && SkipRule.isCloseIcon(candidate.label)) {
                root.packageName?.toString() !in WEAK_MATCH_BLOCKED &&
                    splashSession.allowAdClose(SystemClock.elapsedRealtime(), Prefs.splashWindowMs) &&
                    ((SkipRule.isSplashAdControlResourceId(candidate.node.viewIdResourceName) &&
                        SplashSkipPolicy.compactClose(Rect().also(candidate.node::getBoundsInScreen).toTargetBounds(), screen.x, screen.y)) ||
                        adMarkers.any { (label, marker) -> SplashClosePolicy.markerNearTarget(label, marker,
                            Rect().also(candidate.node::getBoundsInScreen).toTargetBounds(), screen.x, screen.y) })
            } else if (candidate.weak) weakAllowed && adHints > 0 && visited <= SPLASH_MAX_NODES
                else {
                    val rect = Rect().also(candidate.node::getBoundsInScreen)
                    val context = namedAdControl(candidate.node, candidate.label, rect)
                    !context.forbidden && (context.id != null ||
                        KnownSplashControls.matches(root.packageName?.toString().orEmpty(), knownSplashActivity(candidate.node),
                            candidate.node.viewIdResourceName, candidate.label, rect.toTargetBounds(), screen.x, screen.y) ||
                        adMarkers.any { (label, marker) -> XunleiSplashPolicy.matches(root.packageName?.toString().orEmpty(),
                            knownSplashActivity(candidate.node), candidate.label, candidate.node.viewIdResourceName,
                            rect.toTargetBounds(), label, marker, screen.x, screen.y) } ||
                        SplashSkipPolicy.hasEvidence(candidate.label, candidate.node.viewIdResourceName,
                            adMarkers.any { (label, marker) -> SplashSkipPolicy.markerNearTarget(label, marker,
                                rect.toTargetBounds(), screen.x, screen.y) }))
                }
            if (!allowed) recycleNode(candidate.node)
            allowed
        }
    }

    /**
     * 位置打分。返回 0 表示"文案像但位置不像"，直接放弃，避免误点。
     */
    private fun scoreOf(strength: Int, rect: Rect, screen: Point, label: String = ""): Int =
        SkipTargetPolicy.score(strength, rect.toTargetBounds(), screen.x, screen.y,
            closeIcon = strength == SkipRule.WEAK && SkipRule.isCloseIcon(label))

    private fun Rect.toTargetBounds() = SkipTargetPolicy.Bounds(left, top, right, bottom)

    private fun validatedBounds(candidate: Candidate, pkg: String, covered: Boolean): ValidatedTarget? {
        if (!Prefs.skipSplash || !ProtectionStore.allows(pkg) || !isScreenInteractive() || AppInfoHelper.isProtectedFromAutomation(this, pkg) ||
            embeddedPagePackage == pkg || splashSession.packageName != pkg ||
            !splashSession.pending(SystemClock.elapsedRealtime(), Prefs.splashWindowMs)) return null
        if (!candidate.node.refresh() || candidate.node.packageName?.toString() != pkg ||
            !candidate.node.isEnabled || candidate.node.isEditable) return null
        val statusBarTarget = isStatusBarTarget(candidate.node)
        if (!covered && !candidate.node.isVisibleToUser && !statusBarTarget) return null
        val self = Rect().also(candidate.node::getBoundsInScreen)
        val failed = failedSkips.firstOrNull { it.node == candidate.node && it.label == skipNodeLabel(candidate.node) }
        if (failed != null && !SkipRetryPolicy.allows(failed.failures, failed.failedAt, SystemClock.elapsedRealtime())) return null
        candidate.rule?.let { rule ->
            val screen = screenSize()
            return self.takeIf { rule in ProtectionStore.rules && rule.matches(pkg, foregroundActivity,
                splashSession.age(SystemClock.elapsedRealtime()), candidate.node.viewIdResourceName,
                nodeLabel(candidate.node), self.toTargetBounds(), screen.x, screen.y) }?.let { ValidatedTarget(it, statusBarTarget) }
        }
        // 页面可能已更新；旧节点不能仅凭之前读到的文案继续点击。
        val label = skipNodeLabel(candidate.node)
        val strength = SkipRule.match(label)
        if (strength == SkipRule.NONE || (!candidate.weak && strength != SkipRule.STRONG) ||
            (covered && strength != SkipRule.STRONG)) return null
        val score = scoreOf(strength, self, screenSize(), label)
        if (score <= 0) return null
        var coordinateOnly = false
        val namedCross = candidate.weak && SkipRule.isSplashAdControlResourceId(candidate.node.viewIdResourceName) &&
            SplashSkipPolicy.compactClose(self.toTargetBounds(), screenSize().x, screenSize().y)
        val weakAction = if (candidate.weak) skipActionOf(candidate.node, trustedControl = namedCross) else null
        if (weakAction == SkipAction.UNSAFE) return null
        if (candidate.weak && SkipRule.isCloseIcon(label)) {
            if (pkg in WEAK_MATCH_BLOCKED || !splashSession.allowAdClose(SystemClock.elapsedRealtime(), Prefs.splashWindowMs) ||
                (!namedCross &&
                    !hasLiveAdMarker(pkg, candidate.node.windowId, covered, self, closeIcon = true,
                        treeEvidence = candidate.treeEvidence))) return null
            coordinateOnly = weakAction == SkipAction.COORDINATE_ONLY
            if (coordinateOnly && !splashCountdown.canAttempt(candidate.node.windowId, self.toTargetBounds())) return null
        } else if (candidate.weak) {
            if (weakAction != SkipAction.DIRECT || !splashSession.allowWeak(SystemClock.elapsedRealtime()) || pkg in WEAK_MATCH_BLOCKED ||
                !hasLiveAdMarker(pkg, candidate.node.windowId, covered, self,
                    topOnly = true, treeEvidence = candidate.treeEvidence)) return null
        } else {
            val action = confirmedSkipControl(candidate.node, covered, candidate.treeEvidence) ?: return null
            coordinateOnly = action == SkipAction.COORDINATE_ONLY
            if (candidate.node.isVisibleToUser) jumpGuard.onAdCandidate(pkg, label)
        }
        return ValidatedTarget(self, statusBarTarget, coordinateOnly)
    }

    /** Shared with jump protection so ordinary ad-related text cannot authorize an automatic return either. */
    internal fun isConfirmedAdSkip(node: AccessibilityNodeInfo, covered: Boolean = false, treeEvidence: Boolean = false): Boolean =
        confirmedSkipControl(node, covered, treeEvidence) != null

    private fun confirmedSkipControl(node: AccessibilityNodeInfo, covered: Boolean, treeEvidence: Boolean): SkipAction? {
        val pkg = node.packageName?.toString() ?: return null
        val label = skipNodeLabel(node)
        if (SkipRule.match(label) != SkipRule.STRONG || JumpAdRule.skipCountdownSeconds(label) == 0) return null
        val target = Rect().also(node::getBoundsInScreen)
        val screen = screenSize()
        if (!node.isEnabled || (!node.isVisibleToUser && !covered && !isStatusBarTarget(node)) ||
            scoreOf(SkipRule.STRONG, target, screen, label) <= 0) return null
        val context = namedAdControl(node, label, target)
        if (context.forbidden) return null
        val dedicatedPage = KnownSplashControls.matches(pkg, knownSplashActivity(node), null, label, target.toTargetBounds(), screen.x, screen.y)
        val xunleiSplash = XunleiSplashPolicy.acceptsTarget(pkg, knownSplashActivity(node), label, node.viewIdResourceName,
            target.toTargetBounds(), screen.x, screen.y) && hasSafeAdEvidenceHierarchy(node) && hasLiveAdMarker(pkg, node.windowId, covered, target,
                treeEvidence = treeEvidence, xunleiOnly = true)
        val trustedControl = context.id != null || dedicatedPage || xunleiSplash
        // A text pair supplies ad proof, not permission to borrow a scrolling ancestor's action.
        val action = skipActionOf(node, context.id != null || dedicatedPage)
        if (action == SkipAction.UNSAFE) return null
        if (action == SkipAction.DIRECT) {
            if (trustedControl || SplashSkipPolicy.hasEvidence(label, node.viewIdResourceName, nearbyAd = false) ||
                SplashSkipPolicy.hasEvidence(label, node.viewIdResourceName,
                    hasLiveAdMarker(pkg, node.windowId, covered, target, treeEvidence = treeEvidence))) return action
            return null
        }
        val now = SystemClock.elapsedRealtime()
        if (!splashSession.pending(now, Prefs.splashWindowMs) || !splashCountdown.canAttempt(node.windowId, target.toTargetBounds())) return null
        if (trustedControl && !covered) return action
        return action.takeIf {
            SplashSkipPolicy.allowsCountdownFallback(label, node.viewIdResourceName, target.toTargetBounds(), screen.x, screen.y,
                activeScan = splashSession.packageName == pkg && splashSession.pending(now, Prefs.splashWindowMs),
                covered = covered, safeHierarchy = true) &&
                splashCountdown.observe(node.windowId, label, target.toTargetBounds(), now)
        }
    }

    /** A label must belong to its own button or a tightly fitting clickable parent, never a toggle or article. */
    private fun namedAdControl(target: AccessibilityNodeInfo, label: String, bounds: Rect): AdControlContext {
        val pkg = target.packageName?.toString() ?: return AdControlContext(forbidden = true)
        val screen = screenSize()
        if (CainiaoSplashPolicy.acceptsTarget(pkg, label, target.viewIdResourceName, bounds.toTargetBounds(), screen.x, screen.y)) {
            val raw = SkipRule.buttonLabel(target.text?.toString().orEmpty(), target.contentDescription?.toString().orEmpty())
            if (raw.isNotEmpty() || hasCainiaoAdEvidence(target, requireVisible = true)) {
                cainiaoSplashContainer(target, bounds)?.let { return AdControlContext(it) }
            }
        }
        val activity = knownSplashActivity(target)
        var controlId: String? = null
        var node: AccessibilityNodeInfo? = copyNode(target)
        try {
            repeat(4) {
                val current = node ?: return AdControlContext(controlId)
                if (current.packageName != target.packageName || current.windowId != target.windowId ||
                    current.isEditable || current.isCheckable ||
                    SkipRule.isOrdinaryResourceId(current.viewIdResourceName) ||
                    SkipRule.isSearchResourceId(current.viewIdResourceName)) return AdControlContext(forbidden = true)
                val id = current.viewIdResourceName
                if (KnownSplashControls.isControlId(pkg, id) &&
                    !KnownSplashControls.allowsControlActivity(pkg, activity)) return AdControlContext(forbidden = true)
                if (SkipRule.isAdSkipResourceId(id) || KnownSplashControls.matches(pkg, activity, id,
                        label, bounds.toTargetBounds(), screen.x, screen.y)) {
                    val box = Rect().also(current::getBoundsInScreen)
                    if (controlId == null && box.contains(bounds) &&
                        (current == target || SkipTargetPolicy.smallTarget(box.toTargetBounds(), screen.x, screen.y))) controlId = id
                }
                val parent = current.parent
                recycleNode(current)
                node = parent
            }
            return AdControlContext(controlId)
        } finally { node?.let(::recycleNode) }
    }

    private fun skipActionOf(target: AccessibilityNodeInfo, trustedControl: Boolean = false): SkipAction {
        val self = Rect().also(target::getBoundsInScreen).toTargetBounds()
        var direct = false
        var node: AccessibilityNodeInfo? = copyNode(target)
        try {
            repeat(30) { depth ->
                val current = node ?: return if (direct) SkipAction.DIRECT else SkipAction.COORDINATE_ONLY
                val navigation = current.isScrollable || SplashSkipPolicy.isNavigationContainer(current.className?.toString())
                if (current.packageName != target.packageName || current.windowId != target.windowId ||
                    !current.isEnabled || current.isEditable || current.isCheckable ||
                    SkipRule.isOrdinaryResourceId(current.viewIdResourceName) || SkipRule.isSearchResourceId(current.viewIdResourceName) ||
                    (navigation && (!trustedControl || depth == 0))) return SkipAction.UNSAFE
                val action = current.isClickable || current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
                if (!direct && action && !navigation && depth < 4 &&
                    SkipTargetPolicy.preciseParent(self, Rect().also(current::getBoundsInScreen).toTargetBounds())) {
                    // A parent describing a different action cannot borrow a child's skip label.
                    val parentLabel = SkipRule.buttonLabel(current.text?.toString().orEmpty(), current.contentDescription?.toString().orEmpty())
                    if (current != target && parentLabel.isNotEmpty() && SkipRule.match(parentLabel) == SkipRule.NONE)
                        return SkipAction.UNSAFE
                    // Finding an action does not waive checks on the remaining ancestors.
                    direct = true
                }
                val parent = current.parent
                recycleNode(current)
                node = parent
            }
            return if (node != null) SkipAction.UNSAFE else if (direct) SkipAction.DIRECT else SkipAction.COORDINATE_ONLY
        } finally { node?.let(::recycleNode) }
    }

    /** Neither half of an unnumbered text pair may come from a settings or scrolling surface. */
    private fun hasSafeAdEvidenceHierarchy(target: AccessibilityNodeInfo): Boolean {
        var node: AccessibilityNodeInfo? = copyNode(target)
        try {
            repeat(30) {
                val current = node ?: return true
                if (current.packageName != target.packageName || current.windowId != target.windowId ||
                    current.isEditable || current.isCheckable || current.isScrollable ||
                    SplashSkipPolicy.isNavigationContainer(current.className?.toString()) ||
                    SkipRule.isOrdinaryResourceId(current.viewIdResourceName) ||
                    SkipRule.isSearchResourceId(current.viewIdResourceName)) return false
                val parent = current.parent
                recycleNode(current)
                node = parent
            }
            return node == null
        } finally { node?.let(::recycleNode) }
    }

    private fun hasLiveAdMarker(pkg: String, windowId: Int, covered: Boolean, target: Rect,
        topOnly: Boolean = false, closeIcon: Boolean = false, treeEvidence: Boolean, xunleiOnly: Boolean = false): Boolean {
        val screenWindows = windows.orEmpty()
        val root = screenWindows.firstOrNull { it.id == windowId }?.root ?: rootInActiveWindow
            ?: run { recycleWindows(screenWindows); return false }
        try {
            if (root.packageName?.toString() != pkg || root.windowId != windowId || (!covered && hasSystemCover(windowId, screenWindows))) return false
            val screen = screenSize()
            fun marker(node: AccessibilityNodeInfo): Boolean {
                val bounds = Rect().also(node::getBoundsInScreen)
                val text = nodeLabel(node)
                if (node.packageName?.toString() != pkg || node.windowId != windowId ||
                    (!node.isVisibleToUser && !(covered && !closeIcon) && !isStatusBarTarget(node, screenWindows)) ||
                    (topOnly && bounds.centerY() >= screen.y * 0.35f)) return false
                if (xunleiOnly) return node.isVisibleToUser && XunleiSplashPolicy.matches(pkg,
                    verifiedActivityWindows.activity(pkg, windowId).orEmpty(), "跳过", null, target.toTargetBounds(),
                    text, bounds.toTargetBounds(), screen.x, screen.y) && hasSafeAdEvidenceHierarchy(node)
                val nearby = if (closeIcon) SplashClosePolicy.markerNearTarget(text, bounds.toTargetBounds(), target.toTargetBounds(), screen.x, screen.y)
                    else SplashSkipPolicy.markerNearTarget(text, bounds.toTargetBounds(), target.toTargetBounds(), screen.x, screen.y)
                return nearby && hasSafeAdEvidenceHierarchy(node)
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
        val validated = validatedBounds(candidate, pkg, covered) ?: return false
        val self = validated.bounds
        candidate.gestureAllowed = !validated.nativeOnly
        cainiaoGestureKey(candidate, pkg, self)?.let { key ->
            candidate.cainiaoContainerOwned = true
            if (!splashCountdown.canAttempt(candidate.node.windowId, key)) candidate.gestureAllowed = false
        }
        if (validated.coordinateOnly) return false

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
                    node.isEnabled && hasClickAction && (node.isVisibleToUser || covered || validated.nativeOnly)) {
                    val parentRect = Rect()
                    node.getBoundsInScreen(parentRect)
                    val precise = SkipTargetPolicy.preciseParent(self.toTargetBounds(), parentRect.toTargetBounds())
                    if (precise && node != candidate.node) {
                        val label = SkipRule.buttonLabel(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
                        if (node.isEditable || node.isCheckable || node.isScrollable ||
                            SplashSkipPolicy.isNavigationContainer(node.className?.toString()) ||
                            SkipRule.isOrdinaryResourceId(node.viewIdResourceName) || SkipRule.isSearchResourceId(node.viewIdResourceName) ||
                            (label.isNotEmpty() && SkipRule.match(label) == SkipRule.NONE)) {
                            candidate.gestureAllowed = false
                            return false
                        }
                    }
                    if (precise) {
                        // Native callbacks can be emitted while the cross-process action is still returning.
                        val startedAt = SystemClock.elapsedRealtime()
                        val startedUptime = SystemClock.uptimeMillis()
                        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            beginSkipVerification(candidate, pkg, gesture = false, startedAt = startedAt, startedUptime = startedUptime)
                            return true
                        }
                    }
                    // A large ancestor cannot be clicked. It need not veto the small control's own touch target.
                    // A cross has already passed independent nearby-ad proof and compact-target checks.
                    // Its own touch point can be used; the large ancestor itself still receives no action.
                    if (!precise && candidate.weak && !SkipRule.isCloseIcon(candidate.label)) candidate.gestureAllowed = false
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
        if (checkedAt < coordinateReadyAt) {
            scheduleSplashScan(coordinateReadyAt - checkedAt)
            return
        }
        val target = validatedBounds(candidate, pkg, covered = false) ?: return
        if (target.nativeOnly) { candidate.gestureAllowed = false; return }
        val bounds = target.bounds
        val cainiaoKey = cainiaoGestureKey(candidate, pkg, bounds)
        if (cainiaoKey != null && !splashCountdown.canAttempt(candidate.node.windowId, cainiaoKey)) {
            candidate.gestureAllowed = false
            return
        }
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
            if (success && Prefs.skipSplash && ProtectionStore.allows(pkg) && pendingSkip?.pkg == pkg && foregroundPkg == pkg && isScreenInteractive()) {
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
        if (accepted && (target.coordinateOnly || cainiaoKey != null)) {
            splashCountdown.consume(candidate.node.windowId, skipNodeLabel(candidate.node), cainiaoKey ?: bounds.toTargetBounds(), SystemClock.elapsedRealtime())
        }
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

    private fun knownSplashActivity(node: AccessibilityNodeInfo): String =
        verifiedActivityWindows.activity(node.packageName?.toString().orEmpty(), node.windowId).orEmpty()

    /** Read a split countdown only inside the same precise, clickable skip control. */
    private fun skipNodeLabel(node: AccessibilityNodeInfo): String {
        val raw = SkipRule.buttonLabel(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
        val label = if (KnownSplashControls.hasControls(node.packageName?.toString().orEmpty())) {
            val screen = screenSize()
            KnownSplashControls.normalizeLabel(node.packageName?.toString().orEmpty(), knownSplashActivity(node),
                node.viewIdResourceName, raw, Rect().also(node::getBoundsInScreen).toTargetBounds(), screen.x, screen.y) ?: raw
        } else raw
        if (label.isEmpty() && CainiaoSplashPolicy.isPackage(node.packageName?.toString().orEmpty())) {
            val screen = screenSize()
            val bounds = Rect().also(node::getBoundsInScreen)
            if (CainiaoSplashPolicy.unlabeledBounds(node.className?.toString(), bounds.toTargetBounds(), screen.x, screen.y) &&
                hasCainiaoAdEvidence(node, requireVisible = false)) return "跳过"
        }
        if (label.isEmpty() && SkipRule.isAdCloseResourceId(node.viewIdResourceName)) return "×"
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

    private fun cainiaoSplashContainer(target: AccessibilityNodeInfo, bounds: Rect): String? =
        findCainiaoSplashContainer(target, bounds)?.let { container ->
            try { container.viewIdResourceName } finally { recycleNode(container) }
        }

    private fun findCainiaoSplashContainer(target: AccessibilityNodeInfo, bounds: Rect): AccessibilityNodeInfo? {
        val pkg = target.packageName?.toString().orEmpty()
        if (!CainiaoSplashPolicy.isPackage(pkg)) return null
        val screen = screenSize()
        var container: AccessibilityNodeInfo? = null
        var transferred = false
        var node: AccessibilityNodeInfo? = copyNode(target)
        try {
            repeat(30) {
                val current = node ?: run { transferred = true; return container }
                if (current.packageName != target.packageName || current.windowId != target.windowId ||
                    !current.isEnabled || current.isEditable || current.isCheckable || current.isScrollable ||
                    SplashSkipPolicy.isNavigationContainer(current.className?.toString()) ||
                    SkipRule.isOrdinaryResourceId(current.viewIdResourceName) || SkipRule.isSearchResourceId(current.viewIdResourceName)) return null
                val box = Rect().also(current::getBoundsInScreen)
                if (container == null && box.contains(bounds) && CainiaoSplashPolicy.isContainer(pkg, current.viewIdResourceName,
                        box.toTargetBounds(), screen.x, screen.y)) container = copyNode(current)
                val parent = current.parent
                recycleNode(current)
                node = parent
            }
            if (node == null) { transferred = true; return container }
            return null
        } finally {
            node?.let(::recycleNode)
            if (!transferred) container?.let(::recycleNode)
        }
    }

    private fun hasCainiaoAdEvidence(target: AccessibilityNodeInfo, requireVisible: Boolean): Boolean {
        val pkg = target.packageName?.toString().orEmpty()
        if (!CainiaoSplashPolicy.isPackage(pkg)) return false
        val targetBounds = Rect().also(target::getBoundsInScreen)
        val container = findCainiaoSplashContainer(target, targetBounds) ?: return false
        val screen = screenSize()
        try {
            if (hasCainiaoSdkSibling(target, container, requireVisible)) return true
            val containerBounds = Rect().also(container::getBoundsInScreen)
            var visited = 0
            fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
                if (depth > 30 || visited++ >= 700 || node.packageName != target.packageName || node.windowId != target.windowId ||
                    !node.isEnabled || node.isEditable || node.isCheckable || node.isScrollable ||
                    SplashSkipPolicy.isNavigationContainer(node.className?.toString()) ||
                    SkipRule.isOrdinaryResourceId(node.viewIdResourceName) || SkipRule.isSearchResourceId(node.viewIdResourceName)) return false
                val bounds = Rect().also(node::getBoundsInScreen)
                if ((!requireVisible || node.isVisibleToUser) && containerBounds.contains(bounds) &&
                    CainiaoSplashPolicy.adLabel(pkg, nodeLabel(node), bounds.toTargetBounds(), targetBounds.toTargetBounds(), screen.x, screen.y)) return true
                for (index in 0 until node.childCount) {
                    if (visited >= 700) break
                    val child = node.getChild(index) ?: continue
                    try { if (walk(child, depth + 1)) return true } finally { recycleNode(child) }
                }
                return false
            }
            return walk(container, 0)
        } finally { recycleNode(container) }
    }

    /** Public SDK structure: the circular View immediately precedes an empty terminal TextView,
     * inside at least two FrameLayouts beneath the app's exact third-party splash container. */
    private fun hasCainiaoSdkSibling(target: AccessibilityNodeInfo, container: AccessibilityNodeInfo,
        requireVisible: Boolean): Boolean {
        val parent = target.parent ?: return false
        try {
            if (parent.childCount !in 2..12) return false
            val preceding = parent.getChild(parent.childCount - 2) ?: return false
            try { if (preceding != target) return false } finally { recycleNode(preceding) }
            val marker = parent.getChild(parent.childCount - 1) ?: return false
            try {
                val screen = screenSize()
                if (marker.packageName != target.packageName || marker.windowId != target.windowId ||
                    !marker.isEnabled || marker.isEditable || marker.isCheckable || marker.isScrollable ||
                    SkipRule.isOrdinaryResourceId(marker.viewIdResourceName) || SkipRule.isSearchResourceId(marker.viewIdResourceName) ||
                    (requireVisible && !marker.isVisibleToUser) || !CainiaoSplashPolicy.emptySdkMarker(
                        target.packageName?.toString().orEmpty(), marker.className?.toString(), nodeLabel(marker),
                        Rect().also(marker::getBoundsInScreen).toTargetBounds(), Rect().also(target::getBoundsInScreen).toTargetBounds(), screen.x, screen.y)) return false
            } finally { recycleNode(marker) }
            var node: AccessibilityNodeInfo? = copyNode(parent)
            var frames = 0
            try {
                repeat(30) {
                    val current = node ?: return false
                    if (current == container) return frames >= 2
                    if (current.className?.toString() != "android.widget.FrameLayout") return false
                    frames++
                    val ancestor = current.parent
                    recycleNode(current)
                    node = ancestor
                }
                return false
            } finally { node?.let(::recycleNode) }
        } finally { recycleNode(parent) }
    }

    private fun cainiaoGestureKey(candidate: Candidate, pkg: String, bounds: Rect): SkipTargetPolicy.Bounds? {
        if (!CainiaoSplashPolicy.isPackage(pkg)) return null
        val screen = screenSize()
        return CainiaoSplashPolicy.gestureKey(screen.x, screen.y).takeIf {
            CainiaoSplashPolicy.acceptsTarget(pkg, candidate.label, candidate.node.viewIdResourceName, bounds.toTargetBounds(), screen.x, screen.y) &&
                cainiaoSplashContainer(candidate.node, bounds) != null
        }
    }

    private fun pendingOriginalLabelMatches(node: AccessibilityNodeInfo, pending: PendingSkip): Boolean {
        if (pending.candidate.rule != null) return nodeLabel(node) == pending.candidate.label
        if (pending.candidate.cainiaoContainerOwned &&
            (pending.candidate.label == "跳过" || pending.candidate.label.equals("skip", ignoreCase = true)) &&
            SkipRule.buttonLabel(node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty()).isEmpty()) {
            // Identity verification does not need to reauthorize a touch or read a covered sibling marker.
            if (node.className?.toString()?.substringAfterLast('.') == "View") return true
        }
        return SkipRule.match(skipNodeLabel(node)) >= (if (pending.candidate.weak) SkipRule.WEAK else SkipRule.STRONG)
    }

    private fun findCainiaoReplacement(root: AccessibilityNodeInfo, pending: PendingSkip): AccessibilityNodeInfo? {
        val containers = root.findAccessibilityNodeInfosByViewId(CainiaoSplashPolicy.CONTAINER_ID).orEmpty()
        val covered = applicationCovered(root.windowId)
        val screen = screenSize()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
            if (depth > 30 || visited++ >= 700) return null
            val bounds = Rect().also(node::getBoundsInScreen)
            if (node.packageName?.toString() == pending.pkg && node.windowId == root.windowId &&
                (node.isVisibleToUser || covered || isStatusBarTarget(node)) &&
                CainiaoSplashPolicy.targetBounds(bounds.toTargetBounds(), screen.x, screen.y) &&
                (SkipRule.match(skipNodeLabel(node)) == SkipRule.STRONG ||
                    (covered && CainiaoSplashPolicy.unlabeledBounds(node.className?.toString(), bounds.toTargetBounds(), screen.x, screen.y)))) return copyNode(node)
            for (index in 0 until node.childCount) {
                if (visited >= 700) break
                val child = node.getChild(index) ?: continue
                try { walk(child, depth + 1)?.let { return it } } finally { recycleNode(child) }
            }
            return null
        }
        return try { for (container in containers.take(4)) walk(container, 0)?.let { return it }; null }
        finally { containers.forEach(::recycleNode) }
    }

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

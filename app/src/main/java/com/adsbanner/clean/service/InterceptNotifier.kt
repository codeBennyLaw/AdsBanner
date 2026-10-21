package com.adsbanner.clean.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.adsbanner.clean.R
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.StatsStore
import com.adsbanner.clean.ui.MainActivity

/**
 * 拦截时在状态栏 / 灵动岛弹一条 10 秒的实时提示。
 *
 * 每次拦截单独占一个通知位，所以同时拦多个应用时几条提示可以并存、各自倒计时，
 * 不会被后一条覆盖掉。同时展示的数量设了上限，超出就把最早的收走，避免刷屏。
 *
 * Android 16（API 36）起通知可被系统提升为 Live Update。申请提升要满足两类条件：
 * 1. 通知本身够格 —— ongoing、有进度、带摘要文案，缺一项 `hasPromotableCharacteristics()`
 *    就会返回 false
 * 2. 用户对本应用授权 —— 未授权时 `canPostPromotedNotifications()` 为 false
 *
 * 三个接口都晚于项目的 compileSdk，全部走反射，并用系统自己的判定校验结果。
 */
object InterceptNotifier {

    private const val TAG = "AdsBanner"
    private const val CHANNEL_ID = "adsbanner_intercept"

    /** 通知 ID 区间，同时用来识别哪些通知属于拦截提示 */
    private const val ID_BASE = 2002
    private const val ID_SPAN = 200

    /** 同时最多展示几条，避免连续拦截时通知栏被刷满 */
    private const val MAX_CONCURRENT = 4

    private const val DURATION_MS = 10_000L
    private const val TICK_MS = 1_000L
    private const val BRAND_COLOR = 0xFF2F6BFF.toInt()

    private const val ACTION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"
    private const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"

    private val handler = Handler(Looper.getMainLooper())

    /** 正在展示的提示，key 为通知 ID；LinkedHashMap 保序，超限时淘汰最早的一条 */
    private val active = LinkedHashMap<Int, Session>()
    private var nextId = ID_BASE

    private class Session(
        val id: Int,
        val title: String,
        val text: String,
        val startedAt: Long
    ) {
        var ticker: Runnable? = null
    }

    fun notify(context: Context, appLabel: String, kind: StatsStore.Kind) {
        if (!Prefs.interceptNotify) return

        val app = context.applicationContext
        val title = if (kind == StatsStore.Kind.SPLASH) "已跳过开屏广告" else "已拦截误触跳转"
        val session = Session(allocateId(), title, appLabel, SystemClock.elapsedRealtime())

        ensureChannel(app)
        synchronized(active) { active[session.id] = session }
        push(app, session, 0, DURATION_MS)
        schedule(app, session)
        trim(app)
    }

    private fun allocateId(): Int {
        val id = nextId
        nextId = if (nextId >= ID_BASE + ID_SPAN) ID_BASE else nextId + 1
        return id
    }

    private fun schedule(context: Context, session: Session) {
        val task = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - session.startedAt
                if (elapsed >= DURATION_MS) {
                    cancelOne(context, session.id)
                    return
                }
                push(
                    context,
                    session,
                    (elapsed * 100 / DURATION_MS).toInt().coerceIn(0, 100),
                    (DURATION_MS - elapsed).coerceAtLeast(1_000L)
                )
                handler.postDelayed(this, TICK_MS)
            }
        }
        session.ticker = task
        handler.postDelayed(task, TICK_MS)
    }

    /** 超出上限时收走最早展示的那条 */
    private fun trim(context: Context) {
        while (true) {
            val oldest = synchronized(active) {
                if (active.size <= MAX_CONCURRENT) return
                active.keys.first()
            }
            cancelOne(context, oldest)
        }
    }

    private fun cancelOne(context: Context, id: Int) {
        val session = synchronized(active) { active.remove(id) } ?: return
        session.ticker?.let { handler.removeCallbacks(it) }
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(id)
        }
    }

    fun cancel(context: Context) {
        val sessions = synchronized(active) {
            val copy = active.values.toList()
            active.clear()
            copy
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        sessions.forEach { session ->
            session.ticker?.let { handler.removeCallbacks(it) }
            runCatching { manager?.cancel(session.id) }
        }
    }

    // ------------------------------------------------------------------ 系统实时通知授权

    /** 系统是否提供实时通知接口（不代表已授权） */
    fun supportsLiveUpdates(): Boolean = LiveUpdate.isSupported()

    /** 系统是否支持并被授权发布实时通知 */
    fun canPostPromoted(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return LiveUpdate.canPost(manager)
    }

    /** 系统里是否存在实时通知的授权页面 */
    fun canOpenPromotionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        return runCatching {
            promotionIntent(context).resolveActivity(context.packageManager) != null
        }.getOrDefault(false)
    }

    fun openPromotionSettings(context: Context): Boolean = runCatching {
        context.startActivity(promotionIntent(context))
        true
    }.getOrDefault(false)

    private fun promotionIntent(context: Context): Intent =
        Intent(ACTION_PROMOTION_SETTINGS)
            .putExtra(EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 逐环节列出当前状态，用来确认到底卡在哪一步：
     * 系统版本 -> 接口 -> 授权 -> 通知总开关 -> 实际是否被提升。
     */
    fun describe(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)

        val live = manager?.activeNotifications
            ?.firstOrNull {
                it.packageName == context.packageName &&
                    it.id >= ID_BASE && it.id <= ID_BASE + ID_SPAN
            }
            ?.notification

        val promotedFlag = runCatching {
            Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null)
        }.getOrDefault(0)

        val last = when {
            live == null -> "最近一条提示：当前无正在显示的提示"
            promotedFlag != 0 && (live.flags and promotedFlag) != 0 ->
                "最近一条提示：已被系统提升为实时通知"
            else -> {
                val eligible = LiveUpdate.isEligible(live)
                "最近一条提示：未提升\n资格判定：" + when (eligible) {
                    true -> "符合提升条件，等系统决定"
                    false -> "不符合提升条件"
                    null -> "系统未返回判定"
                }
            }
        }

        return listOf(
            "系统：Android ${Build.VERSION.SDK_INT}（API ${Build.VERSION.SDK_INT}）",
            "实时通知接口：" + if (supportsLiveUpdates()) "可用" else "不可用",
            "实时通知授权：" + when {
                !supportsLiveUpdates() -> "不适用"
                canPostPromoted(context) -> "已授权"
                else -> "未授权"
            },
            "通知总开关：" + if (manager?.areNotificationsEnabled() == true) "开启" else "关闭",
            last
        ).joinToString("\n")
    }

    // ------------------------------------------------------------------ 内部实现

    private fun push(context: Context, session: Session, progress: Int, remainingMs: Long) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!hasPostPermission(context)) return

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(BRAND_COLOR)
            .setContentTitle(session.title)
            .setContentText(session.text)
            .setSubText("广告拦截")
            // 标准进度条：既是给人看的，也是提升为实时通知的判定条件之一
            .setProgress(100, progress, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_EVENT)
            .setContentIntent(contentIntent(context))
            // 即便进程被杀，超时后系统也会自己收走
            .setTimeoutAfter(remainingMs)

        val promoted = LiveUpdate.canPost(manager)
        if (promoted) LiveUpdate.applyStyle(builder, progress, session.title)

        runCatching {
            val notification = if (promoted) LiveUpdate.build(builder) else builder.build()
            manager.notify(session.id, notification)
        }.onFailure { Log.w(TAG, "发送拦截提示失败: ${it.message}") }
    }

    private fun contentIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        // 厂商的实时表面通常只认 IMPORTANCE_DEFAULT 及以上的通道
        val channel = NotificationChannel(
            CHANNEL_ID,
            "拦截提示",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "拦截广告时在状态栏与灵动岛显示 10 秒提示"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun hasPostPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Android 16 实时通知适配。接口全部反射，避免为了几个方法把 compileSdk 提到 36。
     */
    private object LiveUpdate {

        private val characteristics by lazy {
            runCatching { Notification::class.java.getMethod("hasPromotableCharacteristics") }.getOrNull()
        }
        private val permission by lazy {
            runCatching { NotificationManager::class.java.getMethod("canPostPromotedNotifications") }.getOrNull()
        }
        private val requestPromoted by lazy {
            runCatching {
                Notification.Builder::class.java.getMethod(
                    "setRequestPromotedOngoing",
                    Boolean::class.javaPrimitiveType
                )
            }.getOrNull()
        }

        fun isSupported(): Boolean =
            Build.VERSION.SDK_INT >= 36 && characteristics != null && permission != null

        fun canPost(manager: NotificationManager): Boolean {
            if (!isSupported()) return false
            return runCatching { permission?.invoke(manager) as? Boolean ?: false }.getOrDefault(false)
        }

        fun isEligible(notification: Notification): Boolean? = runCatching {
            characteristics?.invoke(notification) as? Boolean
        }.getOrNull()

        /** 系统样式进度条 + 状态栏短摘要，两者都是提升的判定条件 */
        fun applyStyle(builder: Notification.Builder, progress: Int, shortText: String) {
            runCatching {
                val type = Class.forName("android.app.Notification\$ProgressStyle")
                val style = type.getConstructor().newInstance()
                type.getMethod("setProgress", Int::class.javaPrimitiveType)
                    .invoke(style, progress.coerceIn(0, 100))
                builder.setStyle(style as Notification.Style)
            }.onFailure { Log.w(TAG, "ProgressStyle 不可用: ${it.message}") }

            runCatching {
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", String::class.java)
                    .invoke(builder, shortText)
            }.onFailure { Log.w(TAG, "实时通知摘要设置失败: ${it.message}") }
        }

        fun build(builder: Notification.Builder): Notification {
            builder.setColorized(false)
            runCatching { requestPromoted?.invoke(builder, true) }
                .onFailure { Log.w(TAG, "请求实时通知提升失败: ${it.message}") }

            val standard = builder.build()
            if (isEligible(standard) != false) return standard

            // AOSP Android 16 初版要求 colorized，16.1 的新规则反而禁止，
            // 因此以系统自身的资格判断为准，而不是版本号。
            val legacy = builder.setColorized(true).build()
            return if (isEligible(legacy) == true) legacy else builder.setColorized(false).build()
        }
    }
}

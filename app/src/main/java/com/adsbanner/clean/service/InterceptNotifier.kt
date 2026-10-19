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
 * Android 16（API 36）起通知可以被系统提升为 Live Update，在状态栏胶囊和厂商的岛上显示。
 * 申请提升的三个接口都晚于项目的 compileSdk，因此全部走反射，并在运行时用
 * `hasPromotableCharacteristics()` / `canPostPromotedNotifications()` 判断系统是否真的支持，
 * 不靠版本号硬猜 —— 部分 ROM 会保留新接口却沿用旧规则。
 */
object InterceptNotifier {

    private const val TAG = "AdsBanner"
    private const val CHANNEL_ID = "adsbanner_intercept"
    private const val NOTIFICATION_ID = 2002
    private const val DURATION_MS = 10_000L
    private const val TICK_MS = 1_000L

    private val handler = Handler(Looper.getMainLooper())

    private var ticker: Runnable? = null
    private var startedAt = 0L
    private var title = ""
    private var text = ""

    fun notify(context: Context, appLabel: String, kind: StatsStore.Kind) {
        if (!Prefs.interceptNotify) return

        val app = context.applicationContext
        title = if (kind == StatsStore.Kind.SPLASH) "已跳过开屏广告" else "已拦截误触跳转"
        text = appLabel
        startedAt = SystemClock.elapsedRealtime()

        ensureChannel(app)
        push(app, 0)

        ticker?.let { handler.removeCallbacks(it) }
        val task = object : Runnable {
            override fun run() {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (elapsed >= DURATION_MS) {
                    cancel(app)
                    return
                }
                push(app, (elapsed * 100 / DURATION_MS).toInt().coerceIn(0, 100))
                handler.postDelayed(this, TICK_MS)
            }
        }
        ticker = task
        handler.postDelayed(task, TICK_MS)
    }

    fun cancel(context: Context) {
        ticker?.let { handler.removeCallbacks(it) }
        ticker = null
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    // ------------------------------------------------------------------ 内部实现

    private fun push(context: Context, progress: Int) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!hasPostPermission(context)) return

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_EVENT)
            .setContentIntent(contentIntent(context))

        // 能提升就按实时通知的样式构建，否则走普通通知
        val enhanced = LiveUpdate.canPost(manager)
        if (enhanced) LiveUpdate.applyStyle(builder, progress, title)

        runCatching {
            val notification = if (enhanced) LiveUpdate.build(builder) else builder.build()
            manager.notify(NOTIFICATION_ID, notification)
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
        // OEM 的实时表面通常只认 IMPORTANCE_DEFAULT 及以上的通道
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
     * Android 16 实时通知适配。接口全部反射，避免为了三个方法把 compileSdk 提到 36。
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

        fun canPost(manager: NotificationManager): Boolean {
            if (Build.VERSION.SDK_INT < 36) return false
            if (characteristics == null || permission == null) return false
            return runCatching { permission?.invoke(manager) as? Boolean ?: false }.getOrDefault(false)
        }

        fun isEligible(notification: Notification): Boolean? = runCatching {
            characteristics?.invoke(notification) as? Boolean
        }.getOrNull()

        /** 进度条样式 + 状态栏上的短摘要，两者都是提升的判定条件 */
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

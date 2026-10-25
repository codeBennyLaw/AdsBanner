package com.adsbanner.clean.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.adsbanner.clean.R
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.StatsStore
import com.adsbanner.clean.ui.MainActivity
import java.time.LocalDate
import java.time.ZoneId

/**
 * 常驻通知服务。
 *
 * 注意它的定位是"提高进程优先级"，真正的存活主要依赖无障碍服务本身：
 * 无障碍服务由 system_server 绑定，进程被杀后系统通常会重新拉起并重新绑定。
 * 这里不做过激的互相拉起、双进程守护等方案 —— 那些在 Android 8+ 上基本无效，
 * 反而会触发系统的后台限制甚至被判定为恶意行为。
 */
class KeepAliveService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var foregroundReady = false
    private var displayedCount: Int? = null
    private var refreshScheduled = false
    private var receiverRegistered = false
    private val refresh = Runnable {
        refreshScheduled = false
        refreshNotification()
    }
    private val statsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scheduleRefresh() }
    private val dayRefresh = object : Runnable {
        override fun run() {
            refreshNotification()
            scheduleNextDay()
        }
    }
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleRefresh()
            scheduleNextDay()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        StatsStore.init(this)
        StatsStore.addChangeListener(statsListener)
        ensureForeground()
        ContextCompat.registerReceiver(this, dateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
        scheduleNextDay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        scheduleNextDay()
        return START_STICKY
    }

    private fun ensureForeground() {
        if (foregroundReady) {
            refreshNotification()
            return
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "运行状态",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(channel)
            }

            val count = StatsStore.todayTotal()
            val notification = buildNotification(count)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            displayedCount = count
            foregroundReady = true
        } catch (t: Throwable) {
            // Android 12+ 后台启动前台服务可能被系统拒绝，这里静默降级
            Log.w(TAG, "startForeground 失败: ${t.message}")
        }
    }

    private fun buildNotification(count: Int): Notification {
        val title = getString(R.string.guard_notification_title)
        val ready = getString(R.string.guard_notification_ready)
        val today = getString(R.string.guard_notification_today, count)
        fun content(layout: Int) = RemoteViews(packageName, layout).apply {
            setTextViewText(R.id.notificationTitle, title)
            setTextViewText(R.id.notificationReady, ready)
            setTextViewText(R.id.notificationToday, today)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText("$ready\n$today")
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(content(R.layout.notification_guard))
            .setCustomBigContentView(content(R.layout.notification_guard_expanded))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(pending)
            .build()
    }

    private fun scheduleRefresh() {
        if (foregroundReady && !refreshScheduled) {
            refreshScheduled = true
            // Coalesce bursts of successful interceptions into one quiet notification update.
            handler.postDelayed(refresh, 250L)
        }
    }

    private fun refreshNotification() {
        if (!foregroundReady || !Prefs.keepAlive) return
        val count = StatsStore.todayTotal()
        if (displayedCount == count) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(count))
            displayedCount = count
        }.onFailure { Log.w(TAG, "更新常驻通知失败: ${it.message}") }
    }

    private fun scheduleNextDay() {
        handler.removeCallbacks(dayRefresh)
        if (!foregroundReady) return
        val nextDay = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        handler.postDelayed(dayRefresh, (nextDay - System.currentTimeMillis()).coerceAtLeast(1_000L))
    }

    override fun onDestroy() {
        foregroundReady = false
        StatsStore.removeChangeListener(statsListener)
        if (receiverRegistered) unregisterReceiver(dateReceiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AdsBanner"
        private const val CHANNEL_ID = "adsbanner_status"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            Prefs.init(context)
            if (!Prefs.keepAlive) return
            runCatching {
                val intent = Intent(context.applicationContext, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.applicationContext.startForegroundService(intent)
                } else {
                    context.applicationContext.startService(intent)
                }
            }.onFailure { Log.w(TAG, "启动常驻服务失败: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, KeepAliveService::class.java)
                )
            }
        }
    }
}

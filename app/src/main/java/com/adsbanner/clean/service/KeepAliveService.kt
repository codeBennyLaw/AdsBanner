package com.adsbanner.clean.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.adsbanner.clean.R
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.ui.MainActivity

/**
 * 常驻通知服务。
 *
 * 注意它的定位是"提高进程优先级"，真正的存活主要依赖无障碍服务本身：
 * 无障碍服务由 system_server 绑定，进程被杀后系统通常会重新拉起并重新绑定。
 * 这里不做过激的互相拉起、双进程守护等方案 —— 那些在 Android 8+ 上基本无效，
 * 反而会触发系统的后台限制甚至被判定为恶意行为。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        return START_STICKY
    }

    private fun ensureForeground() {
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

            val pending = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle("AdsBanner 正在守护")
                .setContentText("开屏跳过与误触拦截已就绪")
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setContentIntent(pending)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Android 12+ 后台启动前台服务可能被系统拒绝，这里静默降级
            Log.w(TAG, "startForeground 失败: ${t.message}")
        }
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

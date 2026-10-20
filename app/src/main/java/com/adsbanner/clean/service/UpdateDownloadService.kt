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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AppForeground
import com.adsbanner.clean.ui.MainActivity
import com.adsbanner.clean.update.ApkInstaller
import com.adsbanner.clean.update.UpdateDownloader
import java.io.File

/**
 * 更新包下载服务。
 *
 * 用前台服务而不是普通线程，是为了让下载在用户切出应用后也能跑完；
 * 下载完成时如果应用还在前台，直接拉起系统安装器，否则发一条通知，点通知回来再装
 * —— Android 10 起后台应用不允许启动 Activity，这是系统限制，绕不过去。
 */
class UpdateDownloadService : Service() {

    companion object {
        private const val TAG = "AdsBanner"
        private const val CHANNEL_ID = "adsbanner_update"
        private const val PROGRESS_ID = 3001
        private const val READY_ID = 3002

        private const val EXTRA_URL = "url"
        private const val EXTRA_FILE = "file"
        private const val EXTRA_VERSION = "version"

        /** MainActivity 读取：下载完成后待安装的文件路径 */
        const val EXTRA_INSTALL_PATH = "install_path"

        fun start(context: Context, url: String, fileName: String, version: String) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, UpdateDownloadService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_FILE, fileName)
                putExtra(EXTRA_VERSION, version)
            }
            runCatching {
                ContextCompat.startForegroundService(appContext, intent)
            }.onFailure { Log.w(TAG, "启动下载服务失败: ${it.message}") }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var handle: UpdateDownloader.Handle? = null
    private var version = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL)
        val fileName = intent?.getStringExtra(EXTRA_FILE)
        version = intent?.getStringExtra(EXTRA_VERSION).orEmpty()

        if (url.isNullOrBlank() || fileName.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithProgress(0)

        handle = UpdateDownloader.download(
            context = this,
            url = url,
            fileName = fileName,
            onProgress = { percent -> handler.post { updateProgress(percent) } },
            onComplete = { file -> handler.post { onDownloaded(file) } },
            onError = { reason -> handler.post { onFailed(reason) } }
        )

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handle?.cancel()
        handle = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ 下载结果

    private fun onDownloaded(file: File) {
        handle = null
        stopForeground(STOP_FOREGROUND_REMOVE)

        val hasPermission = ApkInstaller.hasInstallPermission(this)
        // 只有应用还在前台时才能直接拉起安装器
        val launched = hasPermission &&
            AppForeground.visible &&
            ApkInstaller.launch(this, file)

        if (!launched) postReadyNotification(file, hasPermission)
        stopSelf()
    }

    private fun onFailed(reason: String) {
        handle = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        runCatching {
            NotificationManagerCompat.from(this).notify(
                READY_ID,
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_notify)
                    .setContentTitle("更新下载失败")
                    .setContentText(reason)
                    .setAutoCancel(true)
                    .build()
            )
        }
        stopSelf()
    }

    private fun postReadyNotification(file: File, hasPermission: Boolean) {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_INSTALL_PATH, file.absolutePath)
        }
        val pending = PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("更新已下载")
            .setContentText(
                if (hasPermission) "点击安装 AdsBanner $version"
                else "点击继续，需要先允许 AdsBanner 安装应用"
            )
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        runCatching { NotificationManagerCompat.from(this).notify(READY_ID, notification) }
    }

    // ------------------------------------------------------------------ 通知

    private fun startForegroundWithProgress(percent: Int) {
        val notification = buildProgressNotification(percent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(PROGRESS_ID, notification)
        }
    }

    private fun updateProgress(percent: Int) {
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(PROGRESS_ID, buildProgressNotification(percent))
        }
    }

    private fun buildProgressNotification(percent: Int): Notification {
        val title = if (version.isBlank()) "正在下载更新" else "正在下载 AdsBanner $version"
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText("$percent%")
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pending)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "应用更新", NotificationManager.IMPORTANCE_LOW).apply {
                description = "下载更新时的进度与完成提示"
                setShowBadge(false)
            }
        )
    }
}

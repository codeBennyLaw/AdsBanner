package com.adsbanner.clean.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AccessibilityHelper
import com.adsbanner.clean.core.AppForeground
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.StatsStore
import com.adsbanner.clean.service.InterceptNotifier
import com.adsbanner.clean.service.KeepAliveService
import com.adsbanner.clean.service.UpdateDownloadService
import com.adsbanner.clean.update.ApkInstaller
import com.adsbanner.clean.update.ReleaseUpdates
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var tvServiceState: TextView
    private lateinit var tvServiceHint: TextView
    private lateinit var tvTodayCount: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var tvStatsMeta: TextView
    private lateinit var tvTargetHint: TextView
    private lateinit var tvBatteryHint: TextView
    private lateinit var tvNotifyHint: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnCheckUpdate: TextView
    private lateinit var linkRepo: View
    private lateinit var content: View
    private lateinit var switchSplash: MaterialSwitch
    private lateinit var switchJump: MaterialSwitch
    private lateinit var switchKeepAlive: MaterialSwitch
    private lateinit var switchNotify: MaterialSwitch
    private lateinit var switchInAppAd: MaterialSwitch

    private var binding = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) KeepAliveService.start(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        applySystemBarAppearance()
        // 清单里已声明 portrait，分屏、悬浮窗等场景下部分 ROM 会忽略，运行时再锁一次
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        bindViews()
        applyInsets()
        Motion.enter(content)
        setupClicks()
        setupSwitches()
        requestNotificationPermissionIfNeeded()
        handleInstallIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AppForeground.visible = true
        refresh()
    }

    override fun onPause() {
        AppForeground.visible = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleInstallIntent(intent)
    }

    private fun bindViews() {
        statusDot = findViewById(R.id.statusDot)
        tvServiceState = findViewById(R.id.tvServiceState)
        tvServiceHint = findViewById(R.id.tvServiceHint)
        tvTodayCount = findViewById(R.id.tvTodayCount)
        tvTotalCount = findViewById(R.id.tvTotalCount)
        tvStatsMeta = findViewById(R.id.tvStatsMeta)
        tvTargetHint = findViewById(R.id.tvTargetHint)
        tvBatteryHint = findViewById(R.id.tvBatteryHint)
        tvNotifyHint = findViewById(R.id.tvNotifyHint)
        tvVersion = findViewById(R.id.tvVersion)
        linkRepo = findViewById(R.id.linkRepo)
        content = findViewById(R.id.content)
        switchSplash = findViewById(R.id.switchSplash)
        switchJump = findViewById(R.id.switchJump)
        switchKeepAlive = findViewById(R.id.switchKeepAlive)
        switchNotify = findViewById(R.id.switchNotify)
        switchInAppAd = findViewById(R.id.switchInAppAd)

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0.1"
        tvVersion.text = "版本 $versionName"
        linkRepo.setOnClickListener { openRepository() }

        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnCheckUpdate.setOnClickListener { checkForUpdates() }
    }

    // ------------------------------------------------------------------ 检查更新

    private fun checkForUpdates() {
        if (!btnCheckUpdate.isEnabled) return
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = "检查中…"

        val current = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0.0"

        Thread {
            val result = ReleaseUpdates.check(current)
            runOnUiThread {
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.text = "检查更新"
                when (result) {
                    is ReleaseUpdates.Result.New -> showUpdateDialog(result)
                    ReleaseUpdates.Result.Current -> toast("当前已是最新版本")
                    is ReleaseUpdates.Result.Failed -> toast("检查失败：${result.reason}")
                }
            }
        }.start()
    }

    private fun showUpdateDialog(info: ReleaseUpdates.Result.New) {
        val view = layoutInflater.inflate(R.layout.dialog_update, null)
        view.findViewById<TextView>(R.id.tvUpdateVersion).text = "版本 ${info.version}"
        val body = view.findViewById<TextView>(R.id.tvUpdateBody)
        ReleaseUpdates.renderMarkdown(this, body, info.notes)

        // 更新说明可能很长，超过半屏就固定高度并可滚动
        val scroll = view.findViewById<ScrollView>(R.id.updateScroll)
        scroll.post {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.5f).toInt()
            if (body.height > maxHeight) {
                scroll.layoutParams = scroll.layoutParams.apply { height = maxHeight }
                scroll.requestLayout()
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("发现新版本")
            .setView(view)
            .setNegativeButton("稍后", null)
            .setPositiveButton("下载更新") { _, _ -> startDownload(info) }
            .show()
    }

    // ------------------------------------------------------------------ 应用内下载更新

    private fun startDownload(info: ReleaseUpdates.Result.New) {
        val url = info.downloadUrl
        if (url.isNullOrBlank()) {
            toast("该版本没有提供安装包")
            return
        }
        // 交给前台服务，切出应用也能继续下载
        UpdateDownloadService.start(this, url, "AdsBanner-${info.version}.apk", info.version)
        toast("已开始下载，进度见通知栏")
    }

    /** 从下载完成的通知点回来时，带着待安装的文件路径 */
    private fun handleInstallIntent(intent: Intent?) {
        val path = intent?.getStringExtra(UpdateDownloadService.EXTRA_INSTALL_PATH) ?: return
        intent.removeExtra(UpdateDownloadService.EXTRA_INSTALL_PATH)
        if (path.isBlank()) return
        installApk(File(path))
    }

    private fun installApk(file: File) {
        if (!file.exists() || file.length() <= 0L) {
            toast("安装包不存在，请重新下载")
            return
        }

        // Android 8 起安装未知来源应用需要单独授权
        if (!ApkInstaller.hasInstallPermission(this)) {
            MaterialAlertDialogBuilder(this)
                .setTitle("需要安装权限")
                .setMessage("系统还没有允许 AdsBanner 安装应用。开启后回到应用即可继续安装。")
                .setNegativeButton("取消", null)
                .setPositiveButton("去设置") { _, _ ->
                    runCatching { startActivity(ApkInstaller.permissionIntent(this)) }
                        .onFailure { toast("无法打开设置页") }
                }
                .show()
            return
        }

        if (!ApkInstaller.launch(this, file)) toast("无法启动安装器")
    }

    private fun openRepository() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
        }.onFailure { toast("没有可用的浏览器") }
    }

    /**
     * 沉浸的关键：内边距加在内容容器上，而不是加在 ScrollView 上。
     * 前者让内容滚动时可以滑到状态栏下方，系统栏区域始终由页面背景铺满；
     * 后者会把整块内容永远压到状态栏以下，看着就像没有沉浸。
     */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            content.setPadding(
                dp(18) + bars.left,
                dp(18) + bars.top,
                dp(18) + bars.right,
                dp(32) + bars.bottom
            )
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun setupClicks() {
        findViewById<View>(R.id.btnService).setOnClickListener {
            AccessibilityHelper.openAccessibilitySettings(this)
        }
        findViewById<MaterialCardView>(R.id.cardStats).setOnClickListener {
            startActivity(Intent(this, StatsActivity::class.java))
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.fade_out)
        }
        findViewById<MaterialCardView>(R.id.cardTargets).setOnClickListener {
            startActivity(Intent(this, TargetListActivity::class.java))
            overridePendingTransition(android.R.anim.slide_in_left, android.R.anim.fade_out)
        }
        findViewById<View>(R.id.rowBattery).setOnClickListener { requestIgnoreBatteryOptimization() }
        findViewById<View>(R.id.rowRom).setOnClickListener { showRomGuide() }
        findViewById<View>(R.id.rowNotify).setOnClickListener { onNotifyRowClick() }
    }

    private fun onNotifyRowClick() {
        val canOpenSettings = InterceptNotifier.canOpenPromotionSettings(this)
        MaterialAlertDialogBuilder(this)
            .setTitle("实时通知诊断")
            .setMessage(InterceptNotifier.describe(this))
            .setPositiveButton("发一条测试") { _, _ -> sendTestNotification() }
            .setNeutralButton("系统设置") { _, _ ->
                if (canOpenSettings) {
                    InterceptNotifier.openPromotionSettings(this)
                } else {
                    toast("系统没有提供实时通知设置页")
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun sendTestNotification() {
        if (!Prefs.interceptNotify) {
            toast("先打开右侧开关")
            return
        }
        InterceptNotifier.notify(this, "示例应用", StatsStore.Kind.SPLASH)
        toast("已发送 10 秒测试提示")
    }

    private fun setupSwitches() {
        switchSplash.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            Prefs.skipSplash = checked
        }
        switchJump.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            Prefs.jumpGuard = checked
        }
        switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            Prefs.keepAlive = checked
            if (checked) {
                if (hasNotificationPermission()) {
                    KeepAliveService.start(this)
                } else {
                    requestNotificationPermissionIfNeeded(force = true)
                }
            } else {
                KeepAliveService.stop(this)
            }
        }
        switchNotify.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            Prefs.interceptNotify = checked
            if (checked) {
                requestNotificationPermissionIfNeeded(force = true)
                guidePromotionIfNeeded()
            }
        }
        switchInAppAd.setOnCheckedChangeListener { _, checked ->
            if (binding) return@setOnCheckedChangeListener
            Prefs.inAppAdGuard = checked
        }
    }

    /** 系统支持实时通知但未授权时，引导用户去系统设置里打开 */
    private fun guidePromotionIfNeeded() {
        if (!InterceptNotifier.canOpenPromotionSettings(this)) return
        if (InterceptNotifier.canPostPromoted(this)) return
        MaterialAlertDialogBuilder(this)
            .setTitle("开启实时通知")
            .setMessage(
                "本机支持实时通知（状态栏胶囊 / 灵动岛），但系统还没有对本应用开放。\n\n" +
                    "在接下来的设置页里打开「允许实时通知」，拦截时才能在岛上显示。" +
                    "不开启也能正常拦截，只是提示会退回普通通知。"
            )
            .setNegativeButton("暂不", null)
            .setPositiveButton("去设置") { _, _ ->
                InterceptNotifier.openPromotionSettings(this)
            }
            .show()
    }

    private fun refresh() {
        val enabled = AccessibilityHelper.isServiceEnabled(this)
        statusDot.setBackgroundResource(if (enabled) R.drawable.dot_on else R.drawable.dot_off)
        tvServiceState.text = if (enabled) "无障碍服务运行中" else "无障碍服务未开启"
        tvServiceState.setTextColor(
            ContextCompat.getColor(this, if (enabled) R.color.text_primary else R.color.danger)
        )
        tvServiceHint.text =
            if (enabled) "AdsBanner 已可以拦截广告" else "未开启时无法拦截，点击右侧去开启"

        val snapshot = StatsStore.snapshot()
        tvTodayCount.text = snapshot.todayTotal.toString()
        tvTotalCount.text = snapshot.total.toString()

        val age = StatsStore.lastInterceptAgeMs()
        tvStatsMeta.text = when {
            age < 0 -> "暂无拦截记录"
            age < 60_000 -> "最近一次拦截：刚刚"
            age < 3_600_000 -> "最近一次拦截：${age / 60_000} 分钟前"
            else -> "最近一次拦截：${age / 3_600_000} 小时前"
        }

        binding = true
        switchSplash.isChecked = Prefs.skipSplash
        switchJump.isChecked = Prefs.jumpGuard
        switchKeepAlive.isChecked = Prefs.keepAlive
        switchNotify.isChecked = Prefs.interceptNotify
        switchInAppAd.isChecked = Prefs.inAppAdGuard
        binding = false

        tvTargetHint.text = "已设置 ${Prefs.jumpTargets.size} 个拦截目标"

        // 实时通知状态直接显示出来，不用猜卡在哪一步
        when {
            !Prefs.interceptNotify -> {
                tvNotifyHint.text = "已关闭，拦截时不再提示"
                tvNotifyHint.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            }
            Build.VERSION.SDK_INT < 36 -> {
                tvNotifyHint.text =
                    "Android ${Build.VERSION.SDK_INT} 不支持实时通知，走普通通知"
                tvNotifyHint.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            }
            !InterceptNotifier.supportsLiveUpdates() -> {
                tvNotifyHint.text = "系统缺少实时通知接口，走普通通知"
                tvNotifyHint.setTextColor(ContextCompat.getColor(this, R.color.text_tertiary))
            }
            InterceptNotifier.canPostPromoted(this) -> {
                tvNotifyHint.text = "已接入实时通知，拦截时上岛（是否提升由系统决定）"
                tvNotifyHint.setTextColor(ContextCompat.getColor(this, R.color.success))
            }
            else -> {
                tvNotifyHint.text = "系统支持但未授权，点此前往开启"
                tvNotifyHint.setTextColor(ContextCompat.getColor(this, R.color.warning))
            }
        }

        val powerManager = getSystemService(PowerManager::class.java)
        val ignoring = powerManager?.isIgnoringBatteryOptimizations(packageName) == true
        tvBatteryHint.text =
            if (ignoring) "已在白名单中" else "避免系统休眠时断开服务"
        tvBatteryHint.setTextColor(
            ContextCompat.getColor(this, if (ignoring) R.color.success else R.color.text_tertiary)
        )
    }

    private fun requestIgnoreBatteryOptimization() {
        val powerManager = getSystemService(PowerManager::class.java)
        if (powerManager?.isIgnoringBatteryOptimizations(packageName) == true) {
            toast("已在电池优化白名单中")
            return
        }
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val ok = runCatching { startActivity(direct) }.isSuccess ||
            runCatching { startActivity(fallback) }.isSuccess
        if (!ok) toast("当前系统不支持该设置，请在系统设置中手动关闭电池优化")
    }

    private fun showRomGuide() {
        val message = """
            国内 ROM 会主动清理后台，需要手动放行一次：

            小米 / Redmi
            设置 → 应用设置 → 应用管理 → AdsBanner → 省电策略选"无限制"，并打开"自启动"

            华为 / 荣耀
            设置 → 应用启动管理 → AdsBanner → 关闭"自动管理"，勾选三项允许

            OPPO / 一加 / realme
            设置 → 电池 → 应用耗电管理 → AdsBanner → 允许后台运行、允许自启动

            vivo / iQOO
            设置 → 电池 → 后台耗电管理 → AdsBanner → 允许后台高耗电；i管家 → 自启动管理

            · 最近任务里下拉/长按"AdsBanner"卡片加锁，可避免一键清理时被杀
            · 无障碍服务本身由系统守护，即使进程被杀，系统通常会自动重新绑定
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("后台保活设置指引")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestNotificationPermissionIfNeeded(force: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasNotificationPermission()) return
        if (!force && !Prefs.keepAlive) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REPO_URL = "https://github.com/codeBennyLaw/AdsBanner"
    }
}

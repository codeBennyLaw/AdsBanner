package com.adsbanner.clean.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AccessibilityHelper
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.StatsStore
import com.adsbanner.clean.service.InterceptNotifier
import com.adsbanner.clean.service.KeepAliveService

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var tvServiceState: TextView
    private lateinit var tvServiceHint: TextView
    private lateinit var tvTodayCount: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var tvStatsMeta: TextView
    private lateinit var tvTargetHint: TextView
    private lateinit var tvBatteryHint: TextView
    private lateinit var tvVersion: TextView
    private lateinit var linkRepo: View
    private lateinit var content: View
    private lateinit var switchSplash: MaterialSwitch
    private lateinit var switchJump: MaterialSwitch
    private lateinit var switchKeepAlive: MaterialSwitch
    private lateinit var switchNotify: MaterialSwitch

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

        bindViews()
        applyInsets()
        setupClicks()
        setupSwitches()
        requestNotificationPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refresh()
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
        tvVersion = findViewById(R.id.tvVersion)
        linkRepo = findViewById(R.id.linkRepo)
        content = findViewById(R.id.content)
        switchSplash = findViewById(R.id.switchSplash)
        switchJump = findViewById(R.id.switchJump)
        switchKeepAlive = findViewById(R.id.switchKeepAlive)
        switchNotify = findViewById(R.id.switchNotify)

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "1.0.1"
        tvVersion.text = "版本 $versionName"
        linkRepo.setOnClickListener { openRepository() }
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
        }
        findViewById<MaterialCardView>(R.id.cardTargets).setOnClickListener {
            startActivity(Intent(this, TargetListActivity::class.java))
        }
        findViewById<View>(R.id.rowBattery).setOnClickListener { requestIgnoreBatteryOptimization() }
        findViewById<View>(R.id.rowRom).setOnClickListener { showRomGuide() }
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
    }

    /** 系统支持实时通知但未授权时，引导用户去系统设置里打开 */
    private fun guidePromotionIfNeeded() {
        if (!InterceptNotifier.canOpenPromotionSettings(this)) return
        if (InterceptNotifier.canPostPromoted(this)) return
        AlertDialog.Builder(this)
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
        binding = false

        tvTargetHint.text = "已启用 ${Prefs.jumpTargets.size} 个应用"

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

        AlertDialog.Builder(this)
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

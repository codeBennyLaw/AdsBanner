package com.adsbanner.clean.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.StatsStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 按应用维度展示拦截统计。
 *
 * 顶部两个维度独立选择：左边是时间范围（今天 / 全部），右边下拉是拦截类型
 * （全部 / 开屏 / 跳转 / 页面内广告）。两者组合出筛选条件，避免为每种组合都放一个按钮。
 */
class StatsActivity : AppCompatActivity() {

    private enum class Range { TODAY, ALL }

    private enum class Type { ALL, SPLASH, JUMP, PAGE }

    private lateinit var container: LinearLayout
    private lateinit var tvSummary: TextView
    private lateinit var tvStatCount: TextView
    private lateinit var tvStatCaption: TextView
    private lateinit var statsOverview: View
    private lateinit var tvEmpty: TextView
    private lateinit var toggleTime: MaterialButtonToggleGroup
    private lateinit var btnType: MaterialButton
    private lateinit var filterRow: View

    private lateinit var listScroll: View
    private lateinit var heatmapScroll: View
    private lateinit var heatScroll: HorizontalScrollView
    private lateinit var heatmap: HeatmapView
    private lateinit var tvHeatMeta: TextView
    private lateinit var tvHeatDetail: TextView
    private lateinit var btnHeatmap: MaterialButton

    private var range = Range.TODAY
    private var type = Type.ALL
    private var heatmapVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_stats)
        applySystemBarAppearance()
        ViewCompat.setAccessibilityHeading(findViewById(R.id.tvPageTitle), true)
        Motion.preparePage(this, getString(R.string.transition_stats), R.color.brand_light)
        // 清单里已声明 portrait，分屏、悬浮窗等场景下部分 ROM 会忽略，运行时再锁一次
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        container = findViewById(R.id.listContainer)
        tvSummary = findViewById(R.id.tvSummary)
        tvStatCount = findViewById(R.id.tvStatCount)
        tvStatCaption = findViewById(R.id.tvStatCaption)
        statsOverview = findViewById(R.id.statsOverview)
        tvEmpty = findViewById(R.id.tvEmpty)
        toggleTime = findViewById(R.id.toggleTime)
        btnType = findViewById(R.id.btnType)
        filterRow = findViewById(R.id.filterRow)

        listScroll = findViewById(R.id.listScroll)
        heatmapScroll = findViewById(R.id.heatmapScroll)
        heatScroll = findViewById(R.id.heatScroll)
        heatmap = findViewById(R.id.heatmap)
        tvHeatMeta = findViewById(R.id.tvHeatMeta)
        tvHeatDetail = findViewById(R.id.tvHeatDetail)
        btnHeatmap = findViewById(R.id.btnHeatmap)

        heatmap.onCellClick = { date, count ->
            tvHeatDetail.visibility = View.VISIBLE
            tvHeatDetail.text =
                "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日 · 拦截 $count 次"
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        btnHeatmap.setOnClickListener { toggleView() }
        btnType.setOnClickListener { showTypeMenu() }

        toggleTime.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            range = if (checkedId == R.id.btnTimeToday) Range.TODAY else Range.ALL
            if (!heatmapVisible) render()
        }
        toggleTime.check(R.id.btnTimeToday)
    }

    override fun onResume() {
        super.onResume()
        if (heatmapVisible) renderHeatmap() else render()
    }

    private fun toggleView() {
        heatmapVisible = !heatmapVisible
        btnHeatmap.text = if (heatmapVisible) "列表" else "热力图"

        val showing = if (heatmapVisible) heatmapScroll else listScroll
        val hiding = if (heatmapVisible) listScroll else heatmapScroll
        Motion.switchViews(showing, hiding, forward = heatmapVisible)
        filterRow.visibility = if (heatmapVisible) View.GONE else View.VISIBLE
        statsOverview.visibility = if (heatmapVisible) View.GONE else View.VISIBLE

        if (heatmapVisible) renderHeatmap() else render()
    }

    // ------------------------------------------------------------------ 列表

    private fun showTypeMenu() {
        PopupMenu(this, btnType).apply {
            menu.add(0, Type.ALL.ordinal, 0, "全部类型")
            menu.add(0, Type.SPLASH.ordinal, 1, "开屏广告")
            menu.add(0, Type.JUMP.ordinal, 2, "误触跳转")
            menu.add(0, Type.PAGE.ordinal, 3, "页面内广告")
            menu.setGroupCheckable(0, true, true)
            menu.findItem(type.ordinal).isChecked = true
            setOnMenuItemClickListener { item ->
                type = Type.entries.getOrElse(item.itemId) { Type.ALL }
                btnType.text = item.title
                render()
                true
            }
        }.show()
    }

    private fun render() {
        container.removeAllViews()
        val snapshot = StatsStore.snapshot()

        val rows = snapshot.apps
            .mapNotNull { stat ->
                val count = countOf(stat)
                if (count <= 0) null else stat to count
            }
            // 按当前筛选条件下的次数排序，而不是跟随存储里的默认顺序
            .sortedByDescending { it.second }

        val total = rows.sumOf { it.second }
        tvStatCaption.text = if (range == Range.TODAY) "今日拦截" else "累计拦截"
        tvStatCount.text = total.toString()
        tvStatCount.contentDescription = "${tvStatCaption.text} $total 次"
        tvSummary.text = "${typeLabel(type)} · ${rows.size} 个应用"

        val empty = rows.isEmpty()
        tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        tvEmpty.text = if (snapshot.total > 0) {
            "当前筛选下暂无记录\n试试切换时间范围或广告类型"
        } else {
            "还没有拦截记录\n开启无障碍服务后，拦截记录会显示在这里"
        }
        container.visibility = if (empty) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for ((stat, count) in rows) {
            val item = inflater.inflate(R.layout.item_stat, container, false)
            item.findViewById<TextView>(R.id.tvName).text =
                AppInfoHelper.label(this, stat.pkg)
            item.findViewById<TextView>(R.id.tvDetail).text = when (type) {
                // 小项明细也要跟随时间范围，选「今天」时不能显示累计值
                Type.ALL -> {
                    val splash = if (range == Range.TODAY) stat.todaySplash else stat.splash
                    val jump = if (range == Range.TODAY) stat.todayJump else stat.jump
                    val page = if (range == Range.TODAY) stat.todayPage else stat.page
                    "开屏 $splash · 跳转 $jump · 页面 $page"
                }
                Type.SPLASH -> "开屏广告跳过"
                Type.JUMP -> "误触跳转拦截"
                Type.PAGE -> "页面内广告关闭"
            }

            // 最近一次时间单独占一行，按当前筛选类型取对应的时间戳
            val lastAt = when (type) {
                Type.ALL -> stat.lastAt
                Type.SPLASH -> stat.lastSplashAt
                Type.JUMP -> stat.lastJumpAt
                Type.PAGE -> stat.lastPageAt
            }
            val recent = item.findViewById<TextView>(R.id.tvRecent)
            if (lastAt > 0L) {
                recent.visibility = View.VISIBLE
                recent.text = "最近 " + formatTime(lastAt)
            } else {
                recent.visibility = View.GONE
            }

            item.findViewById<TextView>(R.id.tvCount).text = count.toString()
            item.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(
                AppInfoHelper.icon(this, stat.pkg)
            )
            container.addView(item)
        }
        Motion.staggerChildren(container)
    }

    private fun countOf(stat: StatsStore.AppStat): Int = when (type) {
        Type.ALL -> if (range == Range.TODAY) stat.todayTotal else stat.total
        Type.SPLASH -> if (range == Range.TODAY) stat.todaySplash else stat.splash
        Type.JUMP -> if (range == Range.TODAY) stat.todayJump else stat.jump
        Type.PAGE -> if (range == Range.TODAY) stat.todayPage else stat.page
    }

    private fun typeLabel(value: Type): String = when (value) {
        Type.ALL -> "全部类型"
        Type.SPLASH -> "开屏广告"
        Type.JUMP -> "误触跳转"
        Type.PAGE -> "页面内广告"
    }

    private fun formatTime(timestamp: Long): String {
        val date = Date(timestamp)
        val cal = Calendar.getInstance().apply { time = date }
        // 今年的记录省掉年份，跨年才补上，避免一行太长
        val pattern = if (cal.get(Calendar.YEAR) == Calendar.getInstance().get(Calendar.YEAR)) {
            "MM-dd HH:mm"
        } else {
            "yyyy-MM-dd HH:mm"
        }
        return SimpleDateFormat(pattern, Locale.getDefault()).format(date)
    }

    // ------------------------------------------------------------------ 热力图

    private fun renderHeatmap() {
        val counts = StatsStore.dailyCounts()
        val installed = StatsStore.installedOn()
        val today = LocalDate.now()

        heatmap.submit(counts, installed, today)

        val span = ChronoUnit.DAYS.between(installed, today).toInt() + 1
        val activeDays = counts.count { it.value > 0 }
        val total = counts.values.sum()
        tvHeatMeta.text = "自 $installed 起 · $span 天 · $activeDays 天有拦截 · 共 $total 次"

        // 日期是从左到右累积的，进来先滚到最近的日期
        heatScroll.post { heatScroll.fullScroll(View.FOCUS_RIGHT) }
    }
}

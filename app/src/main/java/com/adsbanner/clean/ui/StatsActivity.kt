package com.adsbanner.clean.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
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
 * 按应用维度展示拦截统计，右上角可切换到按天的热力图。
 * 应用数量有限（通常几十个），直接向 LinearLayout 里 addView，
 * 不引入 RecyclerView / 分页，减少依赖与状态同步的复杂度。
 */
class StatsActivity : AppCompatActivity() {

    private enum class Mode { ALL, SPLASH, JUMP }

    private lateinit var container: LinearLayout
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var toggleGroup: MaterialButtonToggleGroup
    private lateinit var btnHeatmap: MaterialButton

    private lateinit var listScroll: View
    private lateinit var heatmapScroll: View
    private lateinit var heatScroll: HorizontalScrollView
    private lateinit var heatmap: HeatmapView
    private lateinit var tvHeatMeta: TextView
    private lateinit var tvHeatDetail: TextView

    private var mode = Mode.ALL
    private var heatmapVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_stats)
        applySystemBarAppearance()

        container = findViewById(R.id.listContainer)
        tvSummary = findViewById(R.id.tvSummary)
        tvEmpty = findViewById(R.id.tvEmpty)
        toggleGroup = findViewById(R.id.toggleGroup)
        btnHeatmap = findViewById(R.id.btnHeatmap)

        listScroll = findViewById(R.id.listScroll)
        heatmapScroll = findViewById(R.id.heatmapScroll)
        heatScroll = findViewById(R.id.heatScroll)
        heatmap = findViewById(R.id.heatmap)
        tvHeatMeta = findViewById(R.id.tvHeatMeta)
        tvHeatDetail = findViewById(R.id.tvHeatDetail)

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

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnHeatmap.setOnClickListener { toggleView() }

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.btnTabSplash -> Mode.SPLASH
                R.id.btnTabJump -> Mode.JUMP
                else -> Mode.ALL
            }
            if (!heatmapVisible) render()
        }
        toggleGroup.check(R.id.btnTabAll)
    }

    override fun onResume() {
        super.onResume()
        if (heatmapVisible) renderHeatmap() else render()
    }

    private fun toggleView() {
        heatmapVisible = !heatmapVisible
        btnHeatmap.text = if (heatmapVisible) "列表" else "热力图"

        listScroll.visibility = if (heatmapVisible) View.GONE else View.VISIBLE
        heatmapScroll.visibility = if (heatmapVisible) View.VISIBLE else View.GONE
        toggleGroup.visibility = if (heatmapVisible) View.GONE else View.VISIBLE
        tvSummary.visibility = if (heatmapVisible) View.GONE else View.VISIBLE

        if (heatmapVisible) renderHeatmap() else render()
    }

    // ------------------------------------------------------------------ 列表

    private fun render() {
        container.removeAllViews()
        val snapshot = StatsStore.snapshot()

        val rows = snapshot.apps.mapNotNull { stat ->
            val count = when (mode) {
                Mode.ALL -> stat.total
                Mode.SPLASH -> stat.splash
                Mode.JUMP -> stat.jump
            }
            if (count <= 0) null else stat to count
        }

        tvSummary.text = "共 ${rows.size} 个应用 · 今日 ${snapshot.todayTotal} 次 · 累计 ${snapshot.total} 次"

        tvEmpty.visibility = if (snapshot.total == 0) View.VISIBLE else View.GONE
        container.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for ((stat, count) in rows) {
            val item = inflater.inflate(R.layout.item_stat, container, false)
            item.findViewById<TextView>(R.id.tvName).text =
                AppInfoHelper.label(this, stat.pkg)
            item.findViewById<TextView>(R.id.tvDetail).text = when (mode) {
                Mode.ALL -> "开屏 ${stat.splash} 次 · 跳转 ${stat.jump} 次" + recentOf(stat.lastAt)
                Mode.SPLASH -> "开屏广告跳过" + recentOf(stat.lastSplashAt)
                Mode.JUMP -> "误触跳转拦截" + recentOf(stat.lastJumpAt)
            }
            item.findViewById<TextView>(R.id.tvCount).text = count.toString()
            item.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(
                AppInfoHelper.icon(this, stat.pkg)
            )
            container.addView(item)
        }
    }

    /** 追加到副标题后面的「最近一次」时间 */
    private fun recentOf(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        return " · 最近 " + formatTime(timestamp)
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

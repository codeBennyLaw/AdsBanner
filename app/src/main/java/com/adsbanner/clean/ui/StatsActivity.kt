package com.adsbanner.clean.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.RadioButton
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.StatsStore
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Calendar
import java.util.Date
import java.util.Locale

class StatsActivity : AppCompatActivity() {
    private enum class Range { TODAY, ALL }
    private enum class Type { ALL, SPLASH, JUMP, PAGE }
    private data class RowsKey(val snapshot: StatsStore.Snapshot, val range: Range, val type: Type, val day: LocalDate)
    private data class RenderKey(val rows: RowsKey, val sort: StatsSort)
    private data class Rows(val key: RowsKey, val rows: List<StatAppRow>) {
        val total = rows.sumOf { it.count }
    }
    private lateinit var list: RecyclerView
    private lateinit var adapter: StatAppAdapter
    private lateinit var icons: AppIconLoader
    private lateinit var summary: TextView
    private lateinit var count: TextView
    private lateinit var caption: TextView
    private lateinit var empty: TextView
    private lateinit var typeButton: MaterialButton
    private lateinit var sortButton: MaterialButton
    private lateinit var sortDirectionButton: MaterialButton
    private lateinit var sortCaption: TextView
    private lateinit var heatmapButton: MaterialButton
    private lateinit var heatmapScroll: View
    private lateinit var heatScroll: HorizontalScrollView
    private lateinit var heatmap: HeatmapView
    private lateinit var heatMeta: TextView
    private lateinit var heatLegend: HeatmapLegendView
    private lateinit var heatDetail: TextView
    private val worker = UiWorker { key, task -> Motion.whenPageReady(this, key, task) }
    private var renderedKey: RenderKey? = null
    private var pendingKey: RenderKey? = null
    private val cachedRows = mutableMapOf<Pair<Range, Type>, Rows>()
    private var cachedSnapshot: StatsStore.Snapshot? = null
    private var cachedDay: LocalDate? = null
    private var loadingType: Type? = null
    private var range = Range.TODAY
    private var type = Type.ALL
    private var sort = StatsSort()
    private var heatmapVisible = false
    private var typeMenu: OriginDialog? = null
    private var sortMenu: OriginDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_stats)
        applySystemBarAppearance()
        ViewCompat.setAccessibilityHeading(findViewById(R.id.tvPageTitle), true)
        Motion.preparePage(this, getString(R.string.transition_stats), R.color.brand_light, savedInstanceState != null)
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        range = Range.entries.getOrElse(savedInstanceState?.getInt("range") ?: 0) { Range.TODAY }
        type = Type.entries.getOrElse(savedInstanceState?.getInt("type") ?: 0) { Type.ALL }
        sort = StatsSort(
            StatsSort.Field.entries.getOrElse(savedInstanceState?.getInt("sortField") ?: 0) { StatsSort.Field.COUNT },
            savedInstanceState?.getBoolean("sortDescending", true) ?: true
        )
        heatmapVisible = savedInstanceState?.getBoolean("heatmap") ?: false
        list = findViewById(R.id.listScroll)
        list.layoutManager = LinearLayoutManager(this)
        val header = layoutInflater.inflate(R.layout.header_stats, list, false)
        empty = layoutInflater.inflate(R.layout.item_empty_stats, list, false) as TextView
        summary = header.findViewById(R.id.tvSummary)
        count = header.findViewById(R.id.tvStatCount)
        caption = header.findViewById(R.id.tvStatCaption)
        count.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        typeButton = header.findViewById(R.id.btnType)
        typeButton.text = typeLabel(type)
        sortButton = header.findViewById(R.id.btnSort)
        sortDirectionButton = header.findViewById(R.id.btnSortDirection)
        sortCaption = header.findViewById(R.id.tvSortCaption)
        updateSortControls()
        icons = AppIconLoader(this) { key, task -> Motion.whenPageReady(this, key, task) }
        adapter = StatAppAdapter(icons)
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        list.adapter = ConcatAdapter(SingleViewAdapter(header), adapter, SingleViewAdapter(empty))
        // Navigation owns the motion; list updates should settle in one layout.
        list.itemAnimator = null
        heatmapScroll = findViewById(R.id.heatmapScroll)
        heatScroll = findViewById(R.id.heatScroll)
        heatmap = findViewById(R.id.heatmap)
        heatScroll.setOnScrollChangeListener { _, x, _, previousX, _ ->
            if (x != previousX) heatmap.invalidate()
        }
        heatMeta = findViewById(R.id.tvHeatMeta)
        heatLegend = findViewById(R.id.tvHeatLegend)
        heatDetail = findViewById(R.id.tvHeatDetail)
        heatmapButton = findViewById(R.id.btnHeatmap)
        heatmap.onCellClick = { date, total ->
            heatDetail.visibility = View.VISIBLE
            heatDetail.text = "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日 · 拦截 $total 次"
        }
        val listBottom = list.paddingBottom
        val heatmapBottom = heatmapScroll.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            // Keep the viewport edge-to-edge; only the final scroll position avoids the bar.
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, listBottom + bars.bottom)
            heatmapScroll.setPadding(heatmapScroll.paddingLeft, heatmapScroll.paddingTop,
                heatmapScroll.paddingRight, heatmapBottom + bars.bottom)
            insets
        }
        heatmapButton.setOnClickListener { toggleView() }
        typeButton.setOnClickListener { showTypeMenu() }
        sortButton.setOnClickListener { showSortMenu() }
        sortDirectionButton.setOnClickListener {
            sort = sort.copy(descending = !sort.descending)
            updateSortControls()
            render()
        }
        val toggle = header.findViewById<StatsRangeSelector>(R.id.toggleTime)
        toggle.check(if (range == Range.TODAY) R.id.btnTimeToday else R.id.btnTimeAll)
        toggle.setOnCheckedChangeListener { _, id ->
            val selectedRange = if (id == R.id.btnTimeToday) Range.TODAY else Range.ALL
            if (range != selectedRange) {
                range = selectedRange
                if (!heatmapVisible) render()
            }
        }
        list.visibility = if (heatmapVisible) View.GONE else View.VISIBLE
        heatmapScroll.visibility = if (heatmapVisible) View.VISIBLE else View.GONE
        heatmapButton.text = if (heatmapVisible) "列表" else "热力图"
    }

    override fun onResume() {
        super.onResume()
        invalidateRows()
        if (heatmapVisible) renderHeatmap() else render()
    }

    private fun toggleView() {
        heatmapVisible = !heatmapVisible
        heatmapButton.text = if (heatmapVisible) "列表" else "热力图"
        Motion.switchViews(if (heatmapVisible) heatmapScroll else list,
            if (heatmapVisible) list else heatmapScroll, forward = heatmapVisible)
        if (heatmapVisible) renderHeatmap() else render()
    }

    private fun showTypeMenu() {
        if (typeMenu?.isShowing == true) return
        val content = layoutInflater.inflate(R.layout.dropdown_stats_type, null)
        val dialog = OriginDialog.Builder(this, typeButton).asDropdown().setView(content).create()
        val choices = listOf(R.id.typeAll, R.id.typeSplash, R.id.typeJump, R.id.typePage)
            .map { content.findViewById<RadioButton>(it) }
        var selected = false
        var closing = false
        choices.forEachIndexed { index, choice ->
            choice.isChecked = Type.entries[index] == type
            choice.setOnClickListener {
                if (selected || closing) return@setOnClickListener
                selected = true
                type = Type.entries[index]
                // Keep the destination label ready while statistics wait for the return to finish.
                typeButton.text = typeLabel(type)
                dialog.dismiss()
            }
        }
        dialog.onClosing = { closing = true }
        dialog.setOnDismissListener {
            if (typeMenu === dialog) {
                typeMenu = null
                ViewCompat.setStateDescription(typeButton, null)
            }
            if (selected && !isFinishing && !isDestroyed) render()
        }
        typeMenu = dialog
        ViewCompat.setAccessibilityPaneTitle(content, "广告类型")
        ViewCompat.setStateDescription(typeButton, "已展开")
        dialog.show()
    }

    private fun showSortMenu() {
        if (sortMenu?.isShowing == true) return
        val content = layoutInflater.inflate(R.layout.dropdown_stats_sort, null)
        val dialog = OriginDialog.Builder(this, sortButton).asDropdown().setView(content).create()
        val choices = listOf(R.id.sortCount, R.id.sortTime).map { content.findViewById<RadioButton>(it) }
        var selected = false
        var closing = false
        choices.forEachIndexed { index, choice ->
            choice.isChecked = StatsSort.Field.entries[index] == sort.field
            choice.setOnClickListener {
                if (selected || closing) return@setOnClickListener
                selected = true
                sort = sort.copy(field = StatsSort.Field.entries[index])
                updateSortControls()
                dialog.dismiss()
            }
        }
        dialog.onClosing = { closing = true }
        dialog.setOnDismissListener {
            if (sortMenu === dialog) {
                sortMenu = null
                ViewCompat.setStateDescription(sortButton, sortLabel(sort.field))
            }
            if (selected && !isFinishing && !isDestroyed) render()
        }
        sortMenu = dialog
        ViewCompat.setAccessibilityPaneTitle(content, getString(R.string.stats_sort_field))
        ViewCompat.setStateDescription(sortButton, getString(R.string.stats_sort_expanded, sortLabel(sort.field)))
        dialog.show()
    }

    private fun updateSortControls() {
        val field = sortLabel(sort.field)
        val order = getString(if (sort.descending) R.string.stats_sort_descending else R.string.stats_sort_ascending)
        val nextOrder = getString(if (sort.descending) R.string.stats_sort_ascending else R.string.stats_sort_descending)
        sortButton.text = field
        // Keep the anchor's identity stable when selecting another field during its return.
        sortButton.contentDescription = getString(R.string.stats_sort_field)
        ViewCompat.setStateDescription(sortButton, if (sortMenu?.isShowing == true)
            getString(R.string.stats_sort_expanded, field) else field)
        sortDirectionButton.setIconResource(if (sort.descending) R.drawable.ic_sort_descending else R.drawable.ic_sort_ascending)
        ViewCompat.setStateDescription(sortDirectionButton, getString(R.string.stats_sort_state, field, order))
        TooltipCompat.setTooltipText(sortDirectionButton, getString(R.string.stats_sort_switch_to, nextOrder))
    }

    private fun sortLabel(field: StatsSort.Field) = getString(
        if (field == StatsSort.Field.COUNT) R.string.stats_sort_count else R.string.stats_sort_time
    )

    private fun invalidateRows() {
        cachedRows.clear()
        cachedSnapshot = null
        cachedDay = LocalDate.now()
        loadingType = null
    }

    private fun render() {
        if (cachedDay != LocalDate.now() ||
            cachedSnapshot?.let { it.revision != StatsStore.revision } == true) invalidateRows()
        if (renderedKey == null) {
            // Bind the inexpensive in-memory overview before the first draw. App labels,
            // icons and list layouts still wait for the page transition to finish.
            val snapshot = cachedSnapshot ?: StatsStore.snapshot().also { cachedSnapshot = it }
            var total = 0
            var apps = 0
            snapshot.apps.forEach {
                val value = countOf(it, range, type)
                if (value > 0) {
                    total += value
                    apps++
                }
            }
            showOverview(snapshot, range, type, total, apps)
        }
        cachedRows[range to type]?.let { showRows(it); return }
        // One background pass prepares both ranges. Rapid taps only change the desired range.
        if (loadingType == type) return
        val requestedType = type
        val snapshot = cachedSnapshot
        loadingType = requestedType
        worker.submit({ buildRows(snapshot ?: StatsStore.snapshot(), requestedType) }) { result ->
            loadingType = null
            result.onSuccess { models ->
                cachedSnapshot = models.first().key.snapshot
                models.forEach { cachedRows[it.key.range to it.key.type] = it }
                if (!heatmapVisible) render()
            }.onFailure { summary.text = "加载失败，请返回后重试" }
        }
    }

    private fun buildRows(snapshot: StatsStore.Snapshot, requestedType: Type): List<Rows> {
        val recentFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val olderFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val calendar = Calendar.getInstance()
        val day = LocalDate.now()
        return Range.entries.map { requestedRange ->
            val key = RowsKey(snapshot, requestedRange, requestedType, day)
            val rows = key.snapshot.apps.mapNotNull { stat ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val today = requestedRange == Range.TODAY
                val total = countOf(stat, requestedRange, requestedType)
                if (total <= 0) null else {
                    val detail = when (requestedType) {
                        Type.ALL -> "开屏 ${if (today) stat.todaySplash else stat.splash} · 跳转 ${if (today) stat.todayJump else stat.jump} · 页面 ${if (today) stat.todayPage else stat.page}"
                        Type.SPLASH -> "开屏广告跳过"
                        Type.JUMP -> "误触跳转拦截"
                        Type.PAGE -> "页面内广告关闭"
                    }
                    val last = when (requestedType) {
                        Type.ALL -> stat.lastAt
                        Type.SPLASH -> stat.lastSplashAt
                        Type.JUMP -> stat.lastJumpAt
                        Type.PAGE -> stat.lastPageAt
                    }
                    val recent = if (last <= 0) "" else {
                        calendar.timeInMillis = last
                        "最近 " + (if (calendar.get(Calendar.YEAR) == year) recentFormat else olderFormat).format(Date(last))
                    }
                    val hostLabel = AppInfoHelper.label(applicationContext, stat.pkg)
                    StatAppRow(stat.key, if (stat.isHistoricalSource) stat.historicalLabel ?: "未命名的历史来源" else hostLabel,
                        if (stat.isHistoricalSource) "$hostLabel · 历史记录\n$detail" else detail, recent, total, stat.pkg, last)
                }
            }
            Rows(key, rows)
        }
    }

    private fun showRows(data: Rows) {
        val key = RenderKey(data.key, sort)
        if ((pendingKey ?: renderedKey) == key) return
        pendingKey = key
        // Even a return to the currently displayed range must supersede an in-flight diff.
        // Sorting reuses loaded labels and icons; it never rereads statistics or rebuilds rows.
        adapter.submitList(sort.apply(data.rows)) {
            Motion.whenPageReady(this, summary) {
                if (pendingKey != key) return@whenPageReady
                pendingKey = null
                renderedKey = key
                sortCaption.text = getString(R.string.stats_sort_caption, sortLabel(key.sort.field),
                    getString(if (key.sort.descending) R.string.stats_sort_descending else R.string.stats_sort_ascending))
                showOverview(data.key.snapshot, data.key.range, data.key.type, data.total, data.rows.size)
            }
        }
    }

    private fun countOf(stat: StatsStore.AppStat, selectedRange: Range, selectedType: Type): Int {
        val today = selectedRange == Range.TODAY
        return when (selectedType) {
            Type.ALL -> if (today) stat.todayTotal else stat.total
            Type.SPLASH -> if (today) stat.todaySplash else stat.splash
            Type.JUMP -> if (today) stat.todayJump else stat.jump
            Type.PAGE -> if (today) stat.todayPage else stat.page
        }
    }

    private fun showOverview(snapshot: StatsStore.Snapshot, selectedRange: Range, selectedType: Type, total: Int, apps: Int) {
        caption.text = if (selectedRange == Range.TODAY) "今日拦截" else "累计拦截"
        count.text = total.toString()
        count.contentDescription = "${caption.text} $total 次"
        val sources = if (snapshot.apps.any { it.isHistoricalSource }) "个来源" else "个应用"
        summary.text = "${typeLabel(selectedType)} · $apps $sources"
        empty.text = when {
            snapshot.total == 0 -> "还没有拦截记录\n开启无障碍服务后，拦截记录会显示在这里"
            selectedRange == Range.TODAY -> "今天暂无这类拦截记录\n可以切换“全部时间”查看历史记录"
            else -> "当前类型暂无拦截记录\n试试切换其他广告类型"
        }
        empty.visibility = if (apps == 0) View.VISIBLE else View.GONE
    }

    private fun typeLabel(value: Type) = when (value) {
        Type.ALL -> "全部类型"
        Type.SPLASH -> "开屏广告"
        Type.JUMP -> "误触跳转"
        Type.PAGE -> "页面内广告"
    }

    private fun renderHeatmap() {
        loadingType = null
        worker.submit({ Triple(StatsStore.dailyCounts(), StatsStore.installedOn(), LocalDate.now()) }) { result ->
            result.onSuccess { (counts, installed, today) ->
                val changed = heatmap.submit(counts, installed, today)
                if (changed) showHeatmapLegend()
                val span = ChronoUnit.DAYS.between(installed, today) + 1
                heatMeta.text = "自 $installed 起 · $span 天 · ${counts.count { it.value > 0 }} 天有拦截 · 共 ${counts.values.sum()} 次"
                if (changed) heatScroll.post { heatScroll.fullScroll(View.FOCUS_RIGHT) }
            }.onFailure { heatMeta.text = "加载失败，请返回后重试" }
        }
    }

    private fun showHeatmapLegend() {
        heatLegend.setEntries(heatmap.legendEntries())
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        Motion.pageEnterComplete(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("range", range.ordinal)
        outState.putInt("type", type.ordinal)
        outState.putInt("sortField", sort.field.ordinal)
        outState.putBoolean("sortDescending", sort.descending)
        outState.putBoolean("heatmap", heatmapVisible)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.close()
        typeMenu?.dismissImmediately()
        typeMenu = null
        sortMenu?.dismissImmediately()
        sortMenu = null
        icons.close()
        list.adapter = null
        super.onDestroy()
    }
}

package com.adsbanner.clean.ui

import android.app.Dialog
import android.text.StaticLayout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsbanner.clean.BuildConfig
import com.adsbanner.clean.R
import com.adsbanner.clean.update.ReleaseNotesRepository.NotesPage
import com.adsbanner.clean.update.ReleaseNotesRepository.ReleaseNote
import com.adsbanner.clean.update.ReleaseUpdates

/** Keep the directory mounted behind details, preserving its scroll position. */
internal object ReleaseNotesDialog {
    fun show(activity: AppCompatActivity, loadPage: (Int) -> NotesPage = { ReleaseUpdates.releaseNotes(it) },
        cachedPage: (Int) -> NotesPage? = { ReleaseUpdates.cachedReleaseNotes(it) }): Dialog {
        val content = activity.layoutInflater.inflate(R.layout.dialog_release_notes, null)
        val status = content.findViewById<TextView>(R.id.tvReleaseStatus)
        status.text = "正在读取更新日志…"
        val density = activity.resources.displayMetrics.density
        val statusWidth = (minOf(activity.resources.displayMetrics.widthPixels - (48 * density).toInt(),
            (560 * density).toInt()) - (112 * density).toInt()).coerceAtLeast(1)
        // Reserve common status wrapping before measuring the expanding popup.
        status.minLines = listOf("正在加载全部版本…（已加载 9999 个）", "共 9999 个版本 · 点选查看更新说明",
            "已显示缓存，正在刷新更新日志…", "暂时无法刷新，已保存的更新日志仍可查看").maxOf {
            StaticLayout.Builder.obtain(it, 0, it.length, status.paint, statusWidth).build().lineCount
        }
        val retry = content.findViewById<View>(R.id.btnRetryReleases)
        val list = content.findViewById<RecyclerView>(R.id.listReleases)
        content.findViewById<View>(R.id.releaseDataRegion).layoutParams =
            content.findViewById<View>(R.id.releaseDataRegion).layoutParams.apply {
            height = (activity.resources.displayMetrics.heightPixels * .5f).toInt()
        }
        list.layoutManager = LinearLayoutManager(activity)
        list.itemAnimator = null
        val dialog = OriginDialog.Builder(activity, activity.findViewById(R.id.btnReleaseNotes))
            .setTitle("更新日志")
            .setView(content)
            .setPositiveButton("关闭", null)
            .create()
        val worker = UiWorker { key, task -> dialog.whenReady(key, task) }
        var details: Dialog? = null
        val adapter = NotesAdapter { release, source ->
            if (details?.isShowing != true) details = showDetails(activity, release, source)
        }
        list.adapter = adapter
        var nextPage: Int? = 1
        var loading = false
        var closed = false
        lateinit var local: LocalPageLoading
        var refreshLoading: LocalPageLoading? = null
        val refreshPlaceholder = content.findViewById<View>(R.id.releaseRefreshLoading).apply {
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            findViewById<View>(R.id.loadingLabel).visibility = View.GONE
        }

        fun loadNext() {
            val page = nextPage ?: return
            if (loading || closed) return
            loading = true
            if (adapter.itemCount > 0 && local.isComplete && refreshLoading?.isComplete != false) {
                refreshLoading?.close()
                refreshLoading = LocalPageLoading(activity, null, refreshPlaceholder, "",
                    schedule = dialog::whenReady, navigationHost = dialog.loadingHost())
            }
            retry.visibility = View.GONE
            status.text = if (page == 1 && adapter.itemCount > 0) "已显示缓存，正在刷新更新日志…"
                else if (adapter.itemCount == 0) "正在加载更新日志…"
                else "正在加载全部版本…（已加载 ${adapter.itemCount} 个）"
            status.setTextColor(activity.getColor(R.color.text_secondary))
            worker.submit({ loadPage(page) }) { result ->
                loading = false
                if (closed || activity.isFinishing || activity.isDestroyed) return@submit
                result.onSuccess { data ->
                    local.bind {
                        if (page == 1) adapter.replace(data.notes) else adapter.append(data.notes)
                        local.ready()
                        nextPage = data.nextPage
                        status.text = when {
                            nextPage != null -> "正在加载全部版本…（已加载 ${adapter.itemCount} 个）"
                            adapter.itemCount == 0 -> "暂无更新日志"
                            else -> "共 ${adapter.itemCount} 个版本 · 点选查看更新说明"
                        }
                        if (nextPage != null) content.post { if (!closed) loadNext() }
                        else refreshLoading?.ready()
                    }
                }.onFailure {
                    refreshLoading?.ready()
                    if (adapter.itemCount == 0) {
                        status.text = "更新日志"
                        local.failed("暂时无法获取更新日志，请重试", ::loadNext)
                    } else {
                        status.text = "暂时无法刷新，已保存的更新日志仍可查看"
                        status.setTextColor(activity.getColor(R.color.warning))
                        retry.visibility = View.VISIBLE
                    }
                }
            }
        }
        retry.setOnClickListener { loadNext() }
        dialog.onClosing = {
            closed = true
            worker.close()
            (details as? OriginDialog)?.dismissImmediately()
        }
        dialog.setOnDismissListener {
            local.close()
            refreshLoading?.close()
            list.adapter = null
        }
        dialog.show()
        local = LocalPageLoading(activity, list, content.findViewById(R.id.releaseLoading),
            "正在读取更新日志…", schedule = dialog::whenReady, navigationHost = dialog.loadingHost())
        // Read disk/memory first, so a slow connection never hides previously fetched releases.
        worker.submit({
            buildList<ReleaseNote> {
                val visited = mutableSetOf<Int>()
                var page: Int? = 1
                while (page != null && visited.add(page)) {
                    val cached = cachedPage(page) ?: break
                    addAll(cached.notes)
                    page = cached.nextPage
                }
            }
        }) { result ->
            if (closed || activity.isFinishing || activity.isDestroyed) return@submit
            val cached = result.getOrNull().orEmpty()
            if (cached.isNotEmpty()) {
                local.bind { adapter.replace(cached); local.ready(); loadNext() }
            } else loadNext()
        }
        return dialog
    }

    private fun showDetails(activity: AppCompatActivity, release: ReleaseNote, source: View): Dialog {
        val content = activity.layoutInflater.inflate(R.layout.dialog_update, null)
        content.findViewById<View>(R.id.tvUpdateVersion).visibility = View.GONE
        content.findViewById<TextView>(R.id.tvUpdatePublished).apply {
            visibility = View.VISIBLE
            text = "发布时间：${ReleaseTime.format(release.publishedAt)}"
        }
        content.findViewById<View>(R.id.tvUpdateHeading).visibility = View.GONE
        val body = content.findViewById<TextView>(R.id.tvUpdateBody)
        ReleaseUpdates.renderMarkdown(activity, body, release.notes)
        val scroll = content.findViewById<ScrollView>(R.id.updateScroll)
        scroll.layoutParams = scroll.layoutParams.apply {
            height = (activity.resources.displayMetrics.heightPixels * .5f).toInt()
        }
        return OriginDialog.Builder(activity, source)
            .setTitle("${release.version} 更新日志")
            .setView(content)
            .setPositiveButton("关闭", null)
            .show()
    }

    private class NotesAdapter(private val open: (ReleaseNote, View) -> Unit) : RecyclerView.Adapter<NotesAdapter.Holder>() {
        private val notes = mutableListOf<ReleaseNote>()
        private val seen = mutableSetOf<String>()

        fun replace(values: List<ReleaseNote>) {
            if (notes == values) return
            notes.clear()
            seen.clear()
            notes += values.filter { seen.add(if (it.id > 0) "id:${it.id}" else "tag:${it.version}") }
            notifyDataSetChanged()
        }

        fun append(values: List<ReleaseNote>) {
            val start = notes.size
            notes += values.filter { seen.add(if (it.id > 0) "id:${it.id}" else "tag:${it.version}") }
            if (notes.size > start) notifyItemRangeInserted(start, notes.size - start)
        }

        override fun getItemCount() = notes.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_release_note, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val release = notes[position]
            val badge = when {
                release.version.removePrefix("v") == BuildConfig.VERSION_NAME -> " · 当前版本"
                position == 0 -> " · 最新发布"
                else -> ""
            }
            val time = ReleaseTime.format(release.publishedAt)
            holder.version.text = release.version + badge
            holder.time.text = time
            holder.itemView.contentDescription = "查看 ${release.version}$badge 更新日志，$time"
            holder.itemView.setOnClickListener { open(release, it) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val version: TextView = view.findViewById(R.id.tvReleaseVersion)
            val time: TextView = view.findViewById(R.id.tvReleaseTime)

            init {
                view.accessibilityDelegate = object : View.AccessibilityDelegate() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = Button::class.java.name
                    }
                }
            }
        }
    }
}

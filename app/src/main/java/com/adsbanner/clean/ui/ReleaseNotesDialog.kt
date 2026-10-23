package com.adsbanner.clean.ui

import android.app.Dialog
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
        val retry = content.findViewById<View>(R.id.btnRetryReleases)
        val list = content.findViewById<RecyclerView>(R.id.listReleases)
        list.layoutParams = list.layoutParams.apply {
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

        fun loadNext() {
            val page = nextPage ?: return
            if (loading || closed) return
            loading = true
            retry.visibility = View.GONE
            status.text = if (page == 1 && adapter.itemCount > 0) "已显示缓存，正在刷新更新日志…"
                else if (adapter.itemCount == 0) "正在加载更新日志…"
                else "正在加载全部版本…（已加载 ${adapter.itemCount} 个）"
            status.setTextColor(activity.getColor(R.color.text_secondary))
            worker.submit({ loadPage(page) }) { result ->
                loading = false
                if (closed || activity.isFinishing || activity.isDestroyed) return@submit
                result.onSuccess { data ->
                    if (page == 1) adapter.replace(data.notes) else adapter.append(data.notes)
                    nextPage = data.nextPage
                    status.text = when {
                        nextPage != null -> "正在加载全部版本…（已加载 ${adapter.itemCount} 个）"
                        adapter.itemCount == 0 -> "暂无更新日志"
                        else -> "共 ${adapter.itemCount} 个版本 · 点选查看更新说明"
                    }
                    // Yield to input/drawing between cached pages, as well as network pages.
                    if (nextPage != null) content.post { if (!closed) loadNext() }
                }.onFailure {
                    status.text = if (adapter.itemCount == 0) "暂时无法获取更新日志，请重试"
                        else "暂时无法刷新，已保存的更新日志仍可查看"
                    status.setTextColor(activity.getColor(R.color.warning))
                    retry.visibility = View.VISIBLE
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
            list.adapter = null
        }
        dialog.show()
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
            result.getOrNull()?.let(adapter::replace)
            loadNext()
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

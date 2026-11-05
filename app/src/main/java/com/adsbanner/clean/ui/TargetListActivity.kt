package com.adsbanner.clean.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.children
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.ProtectionStore
import java.util.Locale

/** Searching never rebuilds the header or reloads every application icon. */
class TargetListActivity : AppCompatActivity() {
    private lateinit var list: RecyclerView
    private lateinit var adapter: TargetAppAdapter
    private lateinit var icons: AppIconLoader
    private lateinit var empty: TextView
    private lateinit var search: EditText
    private lateinit var selectionSummary: TextView
    private lateinit var listMeta: TextView
    private lateinit var loading: LocalPageLoading
    private val worker = UiWorker { key, task -> Motion.whenPageReady(this, key, task) }
    private var allApps: List<TargetAppRow> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var installedSelectedCount = 0
    private var appsLoaded = false
    private var query = ""
    private val exclusionsMode get() = intent.getBooleanExtra(EXTRA_EXCLUSIONS, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_target_list)
        applySystemBarAppearance()
        ViewCompat.setAccessibilityHeading(findViewById(R.id.tvPageTitle), true)
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        if (exclusionsMode) findViewById<TextView>(R.id.tvPageTitle).text = "例外应用"
        Motion.preparePage(this, getString(if (exclusionsMode) R.string.transition_exclusions else R.string.transition_targets), R.color.surface, savedInstanceState != null)
        list = findViewById(R.id.appList)
        list.layoutManager = LinearLayoutManager(this)
        val header = layoutInflater.inflate(R.layout.header_targets, list, false)
        empty = layoutInflater.inflate(R.layout.item_empty_targets, list, false) as TextView
        search = header.findViewById(R.id.etSearch)
        selectionSummary = header.findViewById(R.id.tvSelectionSummary)
        listMeta = header.findViewById(R.id.tvAppListMeta)
        if (exclusionsMode) header.findViewById<TextView>(R.id.tvSelectionDescription).text =
            "这些应用不执行自动点击或跳转返回，已有统计保留。勾选后自动保存。"
        selected.addAll(if (exclusionsMode) ProtectionStore.exclusions else Prefs.jumpTargets)
        icons = AppIconLoader(this) { key, task -> Motion.whenPageReady(this, key, task) }
        adapter = TargetAppAdapter(icons, selected) { pkg ->
            if (selected.remove(pkg)) installedSelectedCount-- else {
                selected.add(pkg)
                installedSelectedCount++
            }
            if (exclusionsMode) ProtectionStore.setExclusions(selected) else Prefs.jumpTargets = selected
            updateSelectionSummary()
        }
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        val placeholder = layoutInflater.inflate(R.layout.view_local_loading, list, false)
        loading = LocalPageLoading(this, null, placeholder, getString(R.string.local_loading_apps),
            layoutHost = list, revealTargets = {
                list.children.filter { it !== header && it !== placeholder }.toList()
            })
        list.adapter = ConcatAdapter(SingleViewAdapter(header), SingleViewAdapter(placeholder), adapter, SingleViewAdapter(empty))
        selectionSummary.text = if (exclusionsMode) "例外应用" else "拦截目标"
        // Navigation owns the motion; list updates should settle in one layout.
        list.itemAnimator = null
        val listBottom = list.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout())
            val keyboard = insets.getInsets(WindowInsetsCompat.Type.ime())
            // The keyboard occludes the viewport; the transparent navigation bar doesn't.
            view.setPadding(bars.left, bars.top, bars.right, keyboard.bottom)
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight,
                listBottom + (bars.bottom - keyboard.bottom).coerceAtLeast(0))
            insets
        }
        query = savedInstanceState?.getString("query").orEmpty()
        search.setText(query)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                render()
            }
        })
        loadApplications()
    }

    private fun loadApplications() {
        worker.submit({
            AppInfoHelper.launchableApps(applicationContext).map { (pkg, name) -> TargetAppRow(pkg, name) }
        }) { result ->
            result.onSuccess {
                appsLoaded = true
                allApps = it
                installedSelectedCount = allApps.count { row -> row.pkg in selected }
                updateSelectionSummary()
                render()
            }.onFailure {
                loading.failed("应用名单读取失败，请重试", ::loadApplications)
            }
        }
    }

    private fun render() {
        if (!appsLoaded) return
        val key = query.trim().lowercase(Locale.ROOT)
        val rows = if (key.isEmpty()) allApps else allApps.filter { key in it.searchKey }
        loading.bind {
            listMeta.text = if (key.isEmpty()) "全部应用 · ${rows.size}" else "搜索结果 · ${rows.size}"
            adapter.submitList(rows) {
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                loading.ready()
            }
        }
    }

    private fun updateSelectionSummary() {
        selectionSummary.text = if (exclusionsMode) "已放行 $installedSelectedCount 个已安装应用" else "已选择 $installedSelectedCount 个已安装应用"
    }

    override fun onEnterAnimationComplete() {
        super.onEnterAnimationComplete()
        Motion.pageEnterComplete(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("query", query)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        worker.close()
        icons.close()
        list.adapter = null
        super.onDestroy()
    }

    companion object { const val EXTRA_EXCLUSIONS = "exclusions" }
}

package com.adsbanner.clean.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.Prefs
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
    private val worker = UiWorker()
    private var allApps: List<TargetAppRow> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var installedSelectedCount = 0
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_target_list)
        applySystemBarAppearance()
        ViewCompat.setAccessibilityHeading(findViewById(R.id.tvPageTitle), true)
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        Motion.preparePage(this, getString(R.string.transition_targets), R.color.surface)
        list = findViewById(R.id.appList)
        list.layoutManager = LinearLayoutManager(this)
        val header = layoutInflater.inflate(R.layout.header_targets, list, false)
        empty = layoutInflater.inflate(R.layout.item_empty_targets, list, false) as TextView
        search = header.findViewById(R.id.etSearch)
        selectionSummary = header.findViewById(R.id.tvSelectionSummary)
        listMeta = header.findViewById(R.id.tvAppListMeta)
        selected.addAll(Prefs.jumpTargets)
        icons = AppIconLoader(this)
        adapter = TargetAppAdapter(icons, selected) { pkg ->
            if (selected.remove(pkg)) installedSelectedCount-- else {
                selected.add(pkg)
                installedSelectedCount++
            }
            Prefs.jumpTargets = selected
            updateSelectionSummary()
        }
        adapter.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        list.adapter = ConcatAdapter(SingleViewAdapter(header), adapter, SingleViewAdapter(empty))
        (list.itemAnimator as? DefaultItemAnimator)?.apply {
            supportsChangeAnimations = false
            addDuration = 120
            removeDuration = 100
            moveDuration = 160
        }
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        findViewById<View>(R.id.btnBack).setOnClickListener { onBackPressedDispatcher.onBackPressed() }
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
        worker.submit({
            AppInfoHelper.launchableApps(applicationContext).map { (pkg, name) -> TargetAppRow(pkg, name) }
        }) { result ->
            result.onSuccess {
                allApps = it
                installedSelectedCount = allApps.count { row -> row.pkg in selected }
                updateSelectionSummary()
                render()
            }.onFailure { listMeta.text = "加载失败，请返回后重试" }
        }
    }

    private fun render() {
        val key = query.trim().lowercase(Locale.ROOT)
        val rows = if (key.isEmpty()) allApps else allApps.filter { key in it.searchKey }
        listMeta.text = if (key.isEmpty()) "全部应用 · ${rows.size}" else "搜索结果 · ${rows.size}"
        adapter.submitList(rows) {
            empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun updateSelectionSummary() {
        selectionSummary.text = "已选择 $installedSelectedCount 个已安装应用"
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
}

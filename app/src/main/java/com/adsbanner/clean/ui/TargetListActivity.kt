package com.adsbanner.clean.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.Prefs
import com.google.android.material.card.MaterialCardView

/**
 * 跳转拦截名单。列出所有带桌面图标的应用，勾选即加入名单。
 */
class TargetListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText
    private lateinit var tvSelectionSummary: TextView
    private lateinit var tvAppListMeta: TextView

    private var allApps: List<Pair<String, String>> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_target_list)
        applySystemBarAppearance()
        ViewCompat.setAccessibilityHeading(findViewById(R.id.tvPageTitle), true)
        // 清单里已声明 portrait，分屏、悬浮窗等场景下部分 ROM 会忽略，运行时再锁一次
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }

        container = findViewById(R.id.listContainer)
        tvEmpty = findViewById(R.id.tvEmpty)
        etSearch = findViewById(R.id.etSearch)
        tvSelectionSummary = findViewById(R.id.tvSelectionSummary)
        tvAppListMeta = findViewById(R.id.tvAppListMeta)
        Motion.enter(findViewById(R.id.root))

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.slide_out_right)
        }

        selected.addAll(Prefs.jumpTargets)
        updateSelectionSummary()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                render()
            }
        })

        loadApps()
    }

    private fun loadApps() {
        Thread {
            val apps = AppInfoHelper.launchableApps(applicationContext)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps = apps
                updateSelectionSummary()
                render()
            }
        }.start()
    }

    private fun filtered(): List<Pair<String, String>> {
        if (query.isEmpty()) return allApps
        val q = query.lowercase()
        return allApps.filter {
            it.second.lowercase().contains(q) || it.first.lowercase().contains(q)
        }
    }

    private fun render() {
        container.removeAllViews()
        val list = filtered()
        tvAppListMeta.text = if (query.isEmpty()) "全部应用 · ${list.size}" else "搜索结果 · ${list.size}"

        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        container.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for ((pkg, name) in list) {
            val item = inflater.inflate(R.layout.item_app_check, container, false) as MaterialCardView
            item.findViewById<TextView>(R.id.tvName).text = name
            item.findViewById<TextView>(R.id.tvPkg).text = pkg
            item.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(
                AppInfoHelper.icon(this, pkg)
            )
            val checkBox = item.findViewById<CheckBox>(R.id.cbChecked)
            item.isCheckable = true
            item.checkedIcon = null
            fun updateCheckedState() {
                val checked = pkg in selected
                item.isChecked = checked
                checkBox.isChecked = checked
                item.strokeColor = ContextCompat.getColor(
                    this, if (checked) R.color.brand else R.color.card_stroke
                )
                item.setCardBackgroundColor(ContextCompat.getColor(
                    this, if (checked) R.color.brand_light else R.color.surface
                ))
            }
            updateCheckedState()

            item.setOnClickListener {
                if (selected.contains(pkg)) selected.remove(pkg) else selected.add(pkg)
                updateCheckedState()
                persist()
                updateSelectionSummary()
            }
            container.addView(item)
        }
    }

    private fun persist() {
        Prefs.jumpTargets = selected
    }

    private fun updateSelectionSummary() {
        val installedCount = allApps.count { it.first in selected }
        tvSelectionSummary.text = "已选择 $installedCount 个已安装应用"
    }
}

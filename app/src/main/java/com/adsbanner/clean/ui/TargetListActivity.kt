package com.adsbanner.clean.ui

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
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.Prefs

/**
 * 跳转拦截名单。列出所有带桌面图标的应用，勾选即加入名单。
 */
class TargetListActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var etSearch: EditText

    private var allApps: List<Pair<String, String>> = emptyList()
    private val selected = LinkedHashSet<String>()
    private var query: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_target_list)

        container = findViewById(R.id.listContainer)
        tvEmpty = findViewById(R.id.tvEmpty)
        etSearch = findViewById(R.id.etSearch)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }

        selected.addAll(Prefs.jumpTargets)

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
                allApps = apps
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

        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        container.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for ((pkg, name) in list) {
            val item = inflater.inflate(R.layout.item_app_check, container, false)
            item.findViewById<TextView>(R.id.tvName).text = name
            item.findViewById<TextView>(R.id.tvPkg).text = pkg
            item.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(
                AppInfoHelper.icon(this, pkg)
            )
            val checkBox = item.findViewById<CheckBox>(R.id.cbChecked)
            checkBox.isChecked = pkg in selected

            item.setOnClickListener {
                if (selected.contains(pkg)) selected.remove(pkg) else selected.add(pkg)
                checkBox.isChecked = pkg in selected
                persist()
            }
            container.addView(item)
        }
    }

    private fun persist() {
        Prefs.jumpTargets = selected
    }
}

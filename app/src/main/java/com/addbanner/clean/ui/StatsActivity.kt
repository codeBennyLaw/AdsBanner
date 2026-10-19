package com.addbanner.clean.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButtonToggleGroup
import com.addbanner.clean.R
import com.addbanner.clean.core.AppInfoHelper
import com.addbanner.clean.core.StatsStore

/**
 * 按应用维度展示拦截统计。
 * 应用数量有限（通常几十个），直接向 LinearLayout 里 addView，
 * 不引入 RecyclerView / 分页，减少依赖与状态同步的复杂度。
 */
class StatsActivity : AppCompatActivity() {

    private enum class Mode { ALL, SPLASH, JUMP }

    private lateinit var container: LinearLayout
    private lateinit var tvSummary: TextView
    private lateinit var tvEmpty: TextView
    private lateinit var toggleGroup: MaterialButtonToggleGroup

    private var mode = Mode.ALL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_stats)

        container = findViewById(R.id.listContainer)
        tvSummary = findViewById(R.id.tvSummary)
        tvEmpty = findViewById(R.id.tvEmpty)
        toggleGroup = findViewById(R.id.toggleGroup)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnClear).setOnClickListener { confirmClear() }

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            mode = when (checkedId) {
                R.id.btnTabSplash -> Mode.SPLASH
                R.id.btnTabJump -> Mode.JUMP
                else -> Mode.ALL
            }
            render()
        }
        toggleGroup.check(R.id.btnTabAll)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

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

        tvSummary.text = if (rows.isEmpty()) {
            "共 0 个应用 · 累计 ${snapshot.total} 次"
        } else {
            "共 ${rows.size} 个应用 · 今日 ${snapshot.todayTotal} 次 · 累计 ${snapshot.total} 次"
        }

        tvEmpty.visibility = if (snapshot.total == 0) View.VISIBLE else View.GONE
        container.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE

        val inflater = LayoutInflater.from(this)
        for ((stat, count) in rows) {
            val item = inflater.inflate(R.layout.item_stat, container, false)
            item.findViewById<TextView>(R.id.tvName).text =
                AppInfoHelper.label(this, stat.pkg)
            item.findViewById<TextView>(R.id.tvDetail).text = when (mode) {
                Mode.ALL -> "开屏 ${stat.splash} 次 · 跳转 ${stat.jump} 次"
                Mode.SPLASH -> "开屏广告跳过"
                Mode.JUMP -> "误触跳转拦截"
            }
            item.findViewById<TextView>(R.id.tvCount).text = count.toString()
            item.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(
                AppInfoHelper.icon(this, stat.pkg)
            )
            container.addView(item)
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("清空统计")
            .setMessage("将删除所有应用的拦截次数记录，此操作不可撤销。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                StatsStore.clear()
                render()
            }
            .show()
    }
}

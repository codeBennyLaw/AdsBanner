package com.adsbanner.clean.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.adsbanner.clean.R
import com.adsbanner.clean.core.AccessibilityHelper
import com.adsbanner.clean.core.AdRuleCodec
import com.adsbanner.clean.core.AppInfoHelper
import com.adsbanner.clean.core.LocalAdRule
import com.adsbanner.clean.core.ProtectionStore
import com.adsbanner.clean.core.StatsStore
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ProtectionActivity : AppCompatActivity() {
    private lateinit var rulesAdapter: RuleAdapter
    private lateinit var actionsAdapter: ActionAdapter
    private var allRulesAdapter: RuleAdapter? = null
    private var allActionsAdapter: ActionAdapter? = null
    private var ruleRows = emptyList<RuleRow>()
    private var actionRows = emptyList<ActionRow>()
    private val worker = UiWorker { key, task -> Motion.whenPageReady(this, key, task) }
    private val io = UiWorker()
    private val appWorker = UiWorker()
    private val dialogs = mutableSetOf<OriginDialog>()
    private var editor: Editor? = null
    private var pendingEditor: Bundle? = null
    private var openingPage = false
    private var binding = false
    private var ioBusy = false
    private var alive = true
    private lateinit var loading: LocalPageLoading
    private val changed: () -> Unit = { if (alive) refresh() }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importRules(uri)
    }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportRules(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_protection)
        applySystemBarAppearance()
        Motion.preparePage(this, getString(R.string.transition_protection), R.color.surface, savedInstanceState != null)
        Motion.prepareChildSources(this)
        ViewCompat.setAccessibilityHeading(findViewById(R.id.tvPageTitle), true)
        rulesAdapter = newRuleAdapter()
        actionsAdapter = ActionAdapter(::showAction)
        configureList(findViewById(R.id.rulesList), rulesAdapter)
        configureList(findViewById(R.id.actionsList), actionsAdapter)
        val scroll = findViewById<ScrollView>(R.id.protectionScroll)
        loading = LocalPageLoading(this, scroll, findViewById(R.id.pageLoading),
            getString(R.string.local_loading_protection), ::restoreEditorWhenReady)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { view, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            scroll.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        findViewById<View>(R.id.btnPauseProtection).setOnClickListener {
            if (ProtectionStore.paused) ProtectionStore.resume() else ProtectionStore.pause()
        }
        findViewById<View>(R.id.cardExceptions).also { card ->
            Motion.bindPressFeedback(card)
            card.setOnClickListener {
                if (!openingPage) {
                    openingPage = true
                    Motion.openPage(this, card, Intent(this, TargetListActivity::class.java)
                        .putExtra(TargetListActivity.EXTRA_EXCLUSIONS, true))
                }
            }
        }
        findViewById<View>(R.id.btnAddRule).setOnClickListener { editRule(null, it) }
        findViewById<View>(R.id.btnAllRules).setOnClickListener { showAllRules(it) }
        findViewById<View>(R.id.btnAllHistory).setOnClickListener { showAllHistory(it) }
        findViewById<View>(R.id.btnImportRules).setOnClickListener {
            importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
        }
        findViewById<View>(R.id.btnExportRules).setOnClickListener { exportFile.launch("AdsBanner-rules.json") }
        findViewById<View>(R.id.btnClearHistory).setOnClickListener { source ->
            track(OriginDialog.Builder(this, source).setTitle("清除操作记录？")
                .setMessage("仅清除最近操作，累计拦截统计保留。")
                .setPositiveButton("清除") { _, _ -> ProtectionStore.clearHistory() }
                .setNegativeButton("取消", null).create()).show()
        }
        findViewById<MaterialSwitch>(R.id.switchActionHistory).setOnCheckedChangeListener { _, checked ->
            if (!binding) ProtectionStore.historyEnabled = checked
        }
        pendingEditor = savedInstanceState?.getBundle("editor")
    }

    override fun onResume() {
        super.onResume()
        openingPage = false
        ProtectionStore.addPolicyListener(changed)
        ProtectionStore.addHistoryListener(changed)
        refresh()
        restoreEditorWhenReady()
    }

    private fun restoreEditorWhenReady() {
        if (!loading.isComplete) return
        pendingEditor?.let { draft ->
            Motion.whenPageReady(this, "editor") {
                pendingEditor = null
                editRule(ProtectionStore.rules.find { it.id == draft.getString("id") }, findViewById(R.id.btnAddRule), draft)
            }
        }
    }

    override fun onPause() {
        ProtectionStore.removePolicyListener(changed)
        ProtectionStore.removeHistoryListener(changed)
        super.onPause()
    }

    private fun newRuleAdapter() = RuleAdapter({ rule, source -> editRule(rule, source) }) { rule, enabled ->
        ProtectionStore.setRules(ProtectionStore.rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it })
    }

    private fun configureList(list: RecyclerView, adapter: RecyclerView.Adapter<*>) {
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.itemAnimator = null
    }

    private fun refresh() {
        val paused = ProtectionStore.paused
        findViewById<TextView>(R.id.tvProtectionState).text = when {
            paused -> "保护已暂停"
            !AccessibilityHelper.isServiceEnabled(this) -> "服务未开启"
            else -> "保护状态"
        }
        findViewById<TextView>(R.id.tvProtectionHint).text = if (paused) {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + ProtectionStore.pauseRemainingMs))
            "将在 $time 自动恢复，也可随时提前恢复。"
        } else if (!AccessibilityHelper.isServiceEnabled(this)) {
            "在系统设置中开启无障碍服务后，拦截功能才会运行。"
        } else "可临时暂停全部拦截，例外应用始终放行。"
        findViewById<MaterialButton>(R.id.btnPauseProtection).text = if (paused) "恢复保护" else "暂停 10 分钟"
        findViewById<TextView>(R.id.tvExceptionsHint).text = "已放行 ${ProtectionStore.exclusions.size} 个应用 · 点击管理"
        val rules = ProtectionStore.rules
        val history = ProtectionStore.history.asReversed()
        findViewById<TextView>(R.id.tvRulesHint).text = ProtectionStore.loadError ?: if (rules.isEmpty()) {
            "开屏广告无需配置规则。可按需添加补充匹配，一次启动最多执行一次。"
        } else "${rules.count { it.enabled }} 条已启用 / 共 ${rules.size} 条 · 点击规则编辑"
        binding = true
        findViewById<MaterialSwitch>(R.id.switchActionHistory).isChecked = ProtectionStore.historyEnabled
        binding = false
        findViewById<TextView>(R.id.tvHistoryHint).text = when {
            !ProtectionStore.historyEnabled -> "已停止新增记录；已有记录可手动清除。累计统计继续保留。"
            history.isEmpty() -> "暂无操作记录。仅保存最近 100 条的应用、时间、动作和规则名称，不保存页面文字或截图。"
            else -> "最近 ${history.size} 条开屏与跳转操作 · 点击记录可将应用加入例外"
        }
        findViewById<View>(R.id.btnClearHistory).isEnabled = history.isNotEmpty()
        findViewById<View>(R.id.btnAllRules).visibility = if (rules.size > 5) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btnAllHistory).visibility = if (history.size > 5) View.VISIBLE else View.GONE
        worker.submit({
            val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())
            rules.map { RuleRow(it, AppInfoHelper.label(applicationContext, it.packageName)) } to history.map { action ->
                val kind = if (action.kind == StatsStore.Kind.SPLASH) "跳过开屏" else "返回广告跳转"
                ActionRow(action, AppInfoHelper.label(applicationContext, action.packageName),
                    "${format.format(Date(action.at))} · $kind\n${action.rule}")
            }
        }) { result ->
            result.onSuccess { (rules, actions) ->
                ruleRows = rules
                actionRows = actions
                var remaining = 2
                val committed = { if (--remaining == 0) loading.ready() }
                rulesAdapter.submitList(rules.take(5), committed)
                actionsAdapter.submitList(actions.take(5), committed)
                allRulesAdapter?.submitList(rules)
                allActionsAdapter?.submitList(actions)
            }.onFailure { loading.failed("管理信息读取失败，请重试", ::refresh) }
        }
    }

    private fun dialogList(adapter: RecyclerView.Adapter<*>) = RecyclerView(this).apply {
        layoutParams = ViewGroup.LayoutParams(-1, minOf(dp(400), resources.displayMetrics.heightPixels / 2))
        configureList(this, adapter)
        setPadding(dp(16), 0, dp(16), dp(16))
        clipToPadding = false
    }

    private fun showAllRules(source: View) {
        val adapter = newRuleAdapter().also { it.submitList(ruleRows); allRulesAdapter = it }
        val dialog = OriginDialog.Builder(this, source).setTitle("本地规则")
            .setView(dialogList(adapter)).setPositiveButton("关闭", null).create()
        track(dialog) { allRulesAdapter = null }.show()
    }

    private fun showAllHistory(source: View) {
        val adapter = ActionAdapter(::showAction).also { it.submitList(actionRows); allActionsAdapter = it }
        val dialog = OriginDialog.Builder(this, source).setTitle("最近操作")
            .setView(dialogList(adapter)).setPositiveButton("关闭", null).create()
        track(dialog) { allActionsAdapter = null }.show()
    }

    private fun showAction(action: ProtectionStore.Action, source: View) {
        val from = action.source.takeIf { it.isNotEmpty() }
        val app = AppInfoHelper.label(this, action.packageName)
        val message = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(action.at)))
            append("\n规则：${action.rule}\n应用：$app")
            if (from != null) append("\n来源：${AppInfoHelper.label(this@ProtectionActivity, from)}")
            append("\n\n如影响正常操作，可将相关应用加入例外。")
        }
        val builder = OriginDialog.Builder(this, source).setTitle("操作详情").setMessage(message)
            .setPositiveButton(if (from == null) "放行此应用" else "放行来源") { _, _ ->
                ProtectionStore.setExclusions(ProtectionStore.exclusions + (from ?: action.packageName))
            }.setNegativeButton("关闭", null)
        if (from != null) builder.setNeutralButton("放行目标") { _, _ ->
            ProtectionStore.setExclusions(ProtectionStore.exclusions + action.packageName)
        }
        track(builder.create()).show()
    }

    private data class Editor(val id: String, val dialog: OriginDialog, val fields: List<TextInputEditText>,
        val region: RadioGroup, val age: Slider, val enabled: Boolean) {
        fun save() = Bundle().apply {
            putString("id", id)
            putStringArrayList("fields", ArrayList(fields.map { it.text.toString() }))
            putInt("region", (0 until region.childCount).indexOfFirst { region.getChildAt(it).id == region.checkedRadioButtonId }.coerceAtLeast(0))
            putInt("age", age.value.toInt())
            putBoolean("enabled", enabled)
        }
    }

    private fun input(form: LinearLayout, title: String, value: String = "", identifier: Boolean = false): TextInputEditText {
        val input = TextInputEditText(this).apply {
            background = null
            setPaddingRelative(dp(16), dp(16), dp(16), dp(16))
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or if (identifier) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(value)
            textSize = 15f
            minHeight = dp(56)
        }
        form.addView(TextInputLayout(this).apply {
            hint = title
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            val corner = resources.getDimension(R.dimen.corner_control)
            setBoxCornerRadii(corner, corner, corner, corner)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        return input
    }

    private fun editRule(rule: LocalAdRule?, source: View, draft: Bundle? = null) {
        if (editor?.dialog?.isShowing == true) return
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(16)) }
        val values = draft?.getStringArrayList("fields")
        fun value(index: Int, fallback: String) = values?.getOrNull(index) ?: fallback
        val name = input(form, "规则名称", value(0, rule?.name.orEmpty()))
        val pkg = input(form, "应用包名", value(1, rule?.packageName.orEmpty()), true)
        val choose = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "选择已安装应用"; minHeight = dp(48)
        }
        form.addView(choose, LinearLayout.LayoutParams(-1, -2))
        val activity = input(form, "页面前缀（可选）", value(2, rule?.activityPrefix.orEmpty()), true)
        val viewId = input(form, "完整节点 ID", value(3, rule?.viewId.orEmpty()), true)
        val text = input(form, "匹配文字（可选）", value(4, rule?.text.orEmpty()))
        choose.setOnClickListener { pickApp(choose) { selected ->
            pkg.setText(selected)
            if (viewId.text.isNullOrBlank()) viewId.setText("$selected:id/")
        } }
        form.addView(TextView(this).apply {
            setTextColor(getColor(R.color.text_secondary)); textSize = 13f
            this.text = "节点 ID 格式如 example.app:id/skip，可从应用的无障碍节点信息获得。规则只在启动后的有限时间内匹配，不能绕过系统和小程序保护。"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        val region = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        listOf("顶部（推荐）", "右上角", "指定页面任意位置").forEachIndexed { index, label ->
            region.addView(MaterialRadioButton(this).apply {
                id = View.generateViewId(); this.text = label; minHeight = dp(48)
                isChecked = index == (draft?.getInt("region") ?: rule?.region?.ordinal ?: 0)
            }, RadioGroup.LayoutParams(-1, -2))
        }
        form.addView(region)
        val window = TextView(this).apply { setTextColor(getColor(R.color.text_primary)); textSize = 14f }
        val slider = Slider(this).apply {
            valueFrom = 1f; valueTo = 10f; stepSize = 1f
            value = (draft?.getInt("age") ?: rule?.maxAgeMs?.div(1000)?.toInt() ?: 10).toFloat()
            contentDescription = "启动后匹配时长"
            addOnChangeListener { _, value, _ -> window.text = "启动后 ${value.toInt()} 秒内匹配" }
        }
        window.text = "启动后 ${slider.value.toInt()} 秒内匹配"
        form.addView(window)
        form.addView(slider, LinearLayout.LayoutParams(-1, -2))
        val error = TextView(this).apply {
            setTextColor(getColor(R.color.danger)); textSize = 14f; visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        form.addView(error)
        val scroll = ScrollView(this).apply { addView(form); isFillViewport = false }
        val builder = OriginDialog.Builder(this, source).setTitle(if (rule == null) "新增规则" else "编辑规则")
            .setView(scroll).setPositiveButton("保存", null).setNegativeButton("取消", null)
        if (rule != null) builder.setNeutralButton("删除", null)
        val dialog = builder.create()
        val state = Editor(draft?.getString("id") ?: rule?.id ?: UUID.randomUUID().toString(), dialog,
            listOf(name, pkg, activity, viewId, text), region, slider, draft?.getBoolean("enabled") ?: rule?.enabled ?: true)
        editor = state
        var saving = false
        dialog.setOnShowListener {
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (saving) return@setOnClickListener
                runCatching {
                    val index = (0 until region.childCount).indexOfFirst { region.getChildAt(it).id == region.checkedRadioButtonId }.coerceAtLeast(0)
                    val item = LocalAdRule(state.id, name.text.toString().trim(), pkg.text.toString().trim(), viewId.text.toString().trim(),
                        activity.text.toString().trim(), text.text.toString().trim(), LocalAdRule.Region.entries[index],
                        slider.value.toLong() * 1000, state.enabled)
                    val remaining = ProtectionStore.rules.filterNot { it.id == state.id }
                    ProtectionStore.setRules(remaining + item)
                }.onSuccess { saving = true; dialog.dismiss() }.onFailure {
                    error.text = it.message ?: "规则无法保存，请检查填写内容"
                    error.visibility = View.VISIBLE
                    scroll.post { scroll.smoothScrollTo(0, error.bottom) }
                }
            }
            if (rule != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { delete ->
                var confirmed = false
                val confirm = OriginDialog.Builder(this, delete).setTitle("删除此规则？").setMessage(rule.name)
                    .setPositiveButton("删除") { _, _ ->
                        ProtectionStore.setRules(ProtectionStore.rules.filterNot { it.id == rule.id }); confirmed = true
                    }.setNegativeButton("取消", null).create()
                track(confirm) { if (confirmed) dialog.dismiss() }.show()
            }
        }
        track(dialog) { if (editor?.dialog === dialog) editor = null }.show()
    }

    private fun pickApp(source: View, selected: (String) -> Unit) {
        appWorker.submit({ AppInfoHelper.launchableApps(applicationContext) }) { result ->
            result.onSuccess { apps ->
                val form = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(12))
                    minimumHeight = minOf(dp(440), resources.displayMetrics.heightPixels / 2)
                }
                val search = input(form, "搜索名称或包名")
                val adapter = object : ArrayAdapter<String>(this, R.layout.item_app_picker, apps.map { "${it.second}\n${it.first}" }) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                        (super.getView(position, convertView, parent) as TextView).apply {
                            minHeight = dp(64); textSize = 14f; setTextColor(getColor(R.color.text_primary)); maxLines = 3
                        }
                }
                val list = ListView(this).apply { this.adapter = adapter; divider = null; selector = ContextCompat.getDrawable(context, R.drawable.bg_round_feedback) }
                form.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
                val dialog = OriginDialog.Builder(this, source).setTitle("选择应用").setView(form).setNegativeButton("取消", null).create()
                search.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) {
                        val key = s.toString().trim().lowercase(Locale.ROOT)
                        adapter.clear()
                        adapter.addAll(apps.filter { key in "${it.first}\n${it.second}".lowercase(Locale.ROOT) }.map { "${it.second}\n${it.first}" })
                    }
                })
                list.setOnItemClickListener { _, _, position, _ ->
                    adapter.getItem(position)?.substringAfterLast('\n')?.let(selected)
                    dialog.dismiss()
                }
                track(dialog).show()
            }.onFailure { toast("无法读取应用列表") }
        }
    }

    private fun importRules(uri: Uri) {
        busy(true)
        io.submit({
            val raw = contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= AdRuleCodec.MAX_BYTES) { "规则文件不能超过 256 KB" }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            } ?: error("无法读取文件")
            AdRuleCodec.decode(raw)
        }) { result ->
            busy(false)
            result.onSuccess { rules ->
                track(OriginDialog.Builder(this, findViewById(R.id.btnImportRules)).setTitle("导入 ${rules.size} 条规则？")
                    .setMessage("将替换当前 ${ProtectionStore.rules.size} 条本地规则。\n例外应用、统计和操作记录保持不变。")
                    .setPositiveButton("导入") { _, _ -> ProtectionStore.setRules(rules) }
                    .setNegativeButton("取消", null).create()).show()
            }.onFailure { toast(if (it is IllegalArgumentException) it.message ?: "规则格式不正确" else "无法读取规则，请选择 AdsBanner JSON 文件") }
        }
    }

    private fun exportRules(uri: Uri) {
        val snapshot = ProtectionStore.rules
        busy(true)
        io.submit({
            val encoded = AdRuleCodec.encode(snapshot)
            contentResolver.openOutputStream(uri, "wt")?.bufferedWriter(Charsets.UTF_8)?.use { it.write(encoded) }
                ?: error("无法写入文件")
        }) { result ->
            busy(false)
            result.onSuccess { toast("已导出 ${snapshot.size} 条规则") }.onFailure { toast("导出失败，请检查保存位置") }
        }
    }

    private fun busy(value: Boolean) {
        ioBusy = value
        findViewById<View>(R.id.btnImportRules).isEnabled = !value
        findViewById<View>(R.id.btnExportRules).isEnabled = !value
    }
    private fun track(dialog: OriginDialog, dismissed: () -> Unit = {}): OriginDialog {
        dialogs.add(dialog)
        dialog.setOnDismissListener { dialogs.remove(dialog); dismissed() }
        return dialog
    }
    private fun toast(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onEnterAnimationComplete() { super.onEnterAnimationComplete(); Motion.pageEnterComplete(this) }
    override fun onSaveInstanceState(outState: Bundle) {
        editor?.takeIf { it.dialog.isShowing }?.let { outState.putBundle("editor", it.save()) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        alive = false
        worker.close(); io.close(); appWorker.close()
        dialogs.toList().forEach { it.dismissImmediately() }
        dialogs.clear()
        super.onDestroy()
    }
}

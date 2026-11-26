package com.adsbanner.clean.core

import android.content.Context
import android.content.SharedPreferences
import com.adsbanner.clean.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** A short local outcome trail. Probe builds add bounded structural metadata without page labels or screenshots. */
object SplashDiagnostics {
    private const val LIMIT = 8
    private var storage: SharedPreferences? = null
    private val entries = ArrayList<JSONObject>(LIMIT)

    @Synchronized fun init(context: Context) {
        if (storage != null) return
        storage = context.applicationContext.getSharedPreferences("adsbanner_splash_diagnostics", Context.MODE_PRIVATE)
        val saved = runCatching { JSONArray(storage?.getString("entries", "[]")) }.getOrElse { JSONArray() }
        for (index in 0 until minOf(saved.length(), LIMIT)) saved.optJSONObject(index)?.let { entry ->
            if (!entry.optBoolean("ended")) entry.put("stage", "上次检测已中断，未确认结果").put("ended", true)
            entries.add(entry)
        }
    }

    @Synchronized fun start(context: Context, pkg: String) {
        init(context)
        entries.add(0, JSONObject().put("pkg", pkg).put("time", System.currentTimeMillis())
            .put("stage", "正在查找开屏控件").put("attempts", 0).put("found", false).put("ended", false).apply {
                if (BuildConfig.DIAGNOSTIC_BUILD.isNotEmpty()) put("build", BuildConfig.DIAGNOSTIC_BUILD)
            })
        while (entries.size > LIMIT) entries.removeAt(entries.lastIndex)
        persist()
    }

    @Synchronized fun found(pkg: String) = update(pkg) { entry ->
        if (entry.optBoolean("found") || entry.optBoolean("ended")) false else {
            entry.put("found", true)
            if (entry.optInt("attempts") == 0) entry.put("stage", "已找到候选，正在核对操作条件")
            true
        }
    }

    @Synchronized fun submitted(pkg: String) = update(pkg) { entry ->
        entry.put("attempts", entry.optInt("attempts") + 1).put("stage", "已提交跳过，等待广告关闭").put("ended", false)
        true
    }

    @Synchronized fun unconfirmed(pkg: String) = update(pkg) { entry ->
        if (entry.optBoolean("completed") || entry.optInt("attempts") == 0) false else {
            val stage = "已提交跳过，未能确认广告关闭，未统计"
            if (entry.optString("stage") == stage) false else { entry.put("stage", stage); true }
        }
    }

    @Synchronized fun end(pkg: String, reason: String?) = update(pkg) { entry ->
        if (entry.optBoolean("completed") || entry.optBoolean("ended")) false else {
            val stage = if (entry.optInt("attempts") > 0) "已提交跳过，未能确认广告关闭，未统计"
                else reason ?: if (entry.optBoolean("found")) "候选未通过操作条件，未执行跳过" else "未找到可确认的开屏控件，未执行跳过"
            entry.put("stage", stage).put("ended", true)
            true
        }
    }

    @Synchronized fun completed(pkg: String, notification: String) = update(pkg) { entry ->
        entry.put("stage", "广告关闭已确认，统计已保存").put("notification", notification)
            .put("completed", true).put("ended", true)
        true
    }

    /** Keep the three most informative structural samples from the actual failed launch. */
    @Synchronized fun sample(pkg: String, report: JSONObject) = update(pkg) { entry ->
        if (entry.optBoolean("ended")) false else {
            val samples = entry.optJSONArray("samples") ?: JSONArray().also { entry.put("samples", it) }
            if (samples.length() < 3) samples.put(report) else {
                var weakest = 0
                for (index in 1 until samples.length())
                    if (samples.getJSONObject(index).optInt("rank") < samples.getJSONObject(weakest).optInt("rank")) weakest = index
                if (report.optInt("rank") >= samples.getJSONObject(weakest).optInt("rank")) samples.put(weakest, report)
                else return@update false
            }
            true
        }
    }

    @Synchronized fun scanState(pkg: String, state: String) = update(pkg) { entry ->
        if (entry.optBoolean("ended") || entry.optString("scanState") == state) false else {
            entry.put("scanState", state.take(240)); true
        }
    }

    @Synchronized fun describe(context: Context): String {
        init(context)
        if (entries.isEmpty()) return "最近开屏检测：暂无记录"
        val format = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
        fun timestamp(entry: JSONObject) = format.format(java.util.Date(entry.optLong("time")))
        val evidence = entries.asSequence().filter { CainiaoSplashPolicy.isPackage(it.optString("pkg")) && !it.optBoolean("completed") }
            .mapNotNull { entry -> entry.optJSONArray("samples")?.let(DiagnosticText::cainiaoSample)?.let { sample ->
                "菜鸟结构 · ${timestamp(entry)}\n${entry.optString("scanState").let { if (it.isEmpty()) "" else "扫描状态：$it\n" }}$sample"
            } }.take(2).joinToString("\n\n", postfix = "\n\n").takeIf(String::isNotBlank).orEmpty()
        return evidence + "最近开屏检测（仅保存在本机，最多 $LIMIT 条）：\n" + entries.joinToString("\n\n") { entry ->
            val pkg = entry.optString("pkg")
            "${AppInfoHelper.label(context, pkg)} · ${timestamp(entry)}\n" +
                entry.optString("stage") + "\n已提交动作：${entry.optInt("attempts")} 次" +
                entry.optString("notification").takeIf(String::isNotEmpty)?.let { "\n$it" }.orEmpty() +
                entry.optString("build").takeIf(String::isNotEmpty)?.let { "\n记录构建：$it" }.orEmpty() +
                entry.optString("scanState").takeIf(String::isNotEmpty)?.let { "\n扫描状态：$it" }.orEmpty() +
                entry.optJSONArray("samples")?.let { "\n结构样本：${it.length()} 份" }.orEmpty()
        }
    }

    private fun update(pkg: String, change: (JSONObject) -> Boolean) {
        val entry = entries.firstOrNull { it.optString("pkg") == pkg } ?: return
        if (change(entry)) persist()
    }

    private fun persist() {
        storage?.edit()?.putString("entries", JSONArray(entries).toString())?.apply()
    }
}

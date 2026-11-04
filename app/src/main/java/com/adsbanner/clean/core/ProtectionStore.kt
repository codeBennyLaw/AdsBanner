package com.adsbanner.clean.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/** Policy reads in accessibility callbacks are immutable in-memory lookups, never JSON parsing. */
internal object ProtectionStore {
    const val PAUSE_MS = 10 * 60 * 1000L
    const val HISTORY_LIMIT = 100
    data class Action(val at: Long, val packageName: String, val kind: StatsStore.Kind,
        val rule: String, val source: String = "")
    private lateinit var preferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val policyListeners = CopyOnWriteArraySet<() -> Unit>()
    private val historyListeners = CopyOnWriteArraySet<() -> Unit>()
    @Volatile var exclusions: Set<String> = emptySet()
        private set
    @Volatile var rules: List<LocalAdRule> = emptyList()
        private set
    @Volatile var history: List<Action> = emptyList()
        private set
    @Volatile var loadError: String? = null
        private set
    @Volatile private var pauseDeadline = 0L
    private val expirePause = Runnable { resume() }
    val paused: Boolean get() = SystemClock.elapsedRealtime() < pauseDeadline
    val pauseRemainingMs: Long get() = (pauseDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    var historyEnabled: Boolean
        get() = preferences.getBoolean("historyEnabled", true)
        set(value) {
            preferences.edit().putBoolean("historyEnabled", value).apply()
            historyListeners.forEach { it() }
        }
    fun allows(pkg: String) = !paused && pkg !in exclusions

    fun init(context: Context) {
        if (::preferences.isInitialized) return
        preferences = context.applicationContext.getSharedPreferences("adsbanner_protection", Context.MODE_PRIVATE)
        exclusions = preferences.getStringSet("exclusions", emptySet()).orEmpty().filter(AdRuleCodec::validPackage).toSet()
        rules = runCatching { AdRuleCodec.decode(preferences.getString("rules", "{\"schemaVersion\":1,\"rules\":[]}")!!) }
            .getOrElse { loadError = "本地规则读取失败，通用防护仍可使用"; emptyList() }
        val remaining = (preferences.getLong("pauseUntil", 0L) - System.currentTimeMillis()).coerceIn(0L, PAUSE_MS)
        if (remaining > 0) {
            pauseDeadline = SystemClock.elapsedRealtime() + remaining
            handler.postDelayed(expirePause, remaining)
        }
        history = runCatching {
            val items = JSONArray(preferences.getString("history", "[]"))
            (maxOf(0, items.length() - HISTORY_LIMIT) until items.length()).map { index ->
                val item = items.getJSONObject(index)
                Action(item.getLong("at"), item.getString("packageName"), StatsStore.Kind.valueOf(item.getString("kind")),
                    item.getString("rule"), item.optString("source"))
            }
        }.getOrDefault(emptyList())
    }

    fun pause() {
        pauseDeadline = SystemClock.elapsedRealtime() + PAUSE_MS
        preferences.edit().putLong("pauseUntil", System.currentTimeMillis() + PAUSE_MS).apply()
        handler.removeCallbacks(expirePause)
        handler.postDelayed(expirePause, PAUSE_MS)
        notifyPolicy()
    }

    fun resume() {
        pauseDeadline = 0L
        handler.removeCallbacks(expirePause)
        preferences.edit().remove("pauseUntil").apply()
        notifyPolicy()
    }

    fun setExclusions(packages: Set<String>) {
        exclusions = packages.filter(AdRuleCodec::validPackage).toSet()
        preferences.edit().putStringSet("exclusions", exclusions).apply()
        notifyPolicy()
    }

    fun setRules(value: List<LocalAdRule>) {
        val encoded = AdRuleCodec.encode(value)
        rules = value.toList()
        loadError = null
        preferences.edit().putString("rules", encoded).apply()
        notifyPolicy()
    }

    fun record(pkg: String, kind: StatsStore.Kind, rule: String, source: String = "") {
        if (!historyEnabled) return
        val action = Action(System.currentTimeMillis(), pkg, kind, rule.take(60), source)
        history = (history + action).takeLast(HISTORY_LIMIT)
        saveHistory()
    }

    fun clearHistory() { history = emptyList(); saveHistory() }

    private fun saveHistory() {
        val items = JSONArray()
        history.forEach { action -> items.put(JSONObject().put("at", action.at).put("packageName", action.packageName)
            .put("kind", action.kind.name).put("rule", action.rule).put("source", action.source)) }
        preferences.edit().putString("history", items.toString()).apply()
        historyListeners.forEach { it() }
    }

    fun addPolicyListener(listener: () -> Unit) { policyListeners.add(listener) }
    fun removePolicyListener(listener: () -> Unit) { policyListeners.remove(listener) }
    fun addHistoryListener(listener: () -> Unit) { historyListeners.add(listener) }
    fun removeHistoryListener(listener: () -> Unit) { historyListeners.remove(listener) }
    private fun notifyPolicy() {
        if (Looper.myLooper() == Looper.getMainLooper()) policyListeners.forEach { it() }
        else handler.post { policyListeners.forEach { it() } }
    }
}

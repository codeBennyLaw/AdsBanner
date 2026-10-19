package com.adsbanner.clean.core

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * 按应用维度统计拦截次数。
 *
 * 数据结构（整体序列化成一个 JSON 字符串）：
 * {
 *   "day":  "2026-10-20",
 *   "apps": { "<pkg>": {"s": 累计开屏, "j": 累计跳转, "ts": 今日开屏, "tj": 今日跳转, "last": 毫秒时间戳} }
 * }
 *
 * 数据量极小（几十个应用），因此每次写入整体覆盖，避免维护两套存储。
 */
object StatsStore {

    private const val FILE = "adsbanner_stats"
    private const val KEY_ROOT = "root"

    private const val F_S = "s"
    private const val F_J = "j"
    private const val F_TS = "ts"
    private const val F_TJ = "tj"
    private const val F_LAST = "last"

    private const val F_DAILY = "daily"
    private const val F_INSTALLED = "installed"

    enum class Kind { SPLASH, JUMP }

    data class AppStat(
        val pkg: String,
        val splash: Int,
        val jump: Int,
        val todaySplash: Int,
        val todayJump: Int,
        val lastAt: Long
    ) {
        val total: Int get() = splash + jump
        val todayTotal: Int get() = todaySplash + todayJump
    }

    data class Snapshot(
        val apps: List<AppStat>,
        val todayTotal: Int,
        val total: Int
    )

    private lateinit var sp: SharedPreferences
    private val lock = Any()
    private var root: JSONObject = JSONObject()
    private var loaded = false

    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun init(context: Context) {
        synchronized(lock) {
            if (loaded) return
            sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val raw = sp.getString(KEY_ROOT, null)
            root = if (raw.isNullOrBlank()) {
                JSONObject()
            } else {
                runCatching { JSONObject(raw) }.getOrElse { JSONObject() }
            }
            rolloverIfNeeded()
            // 热力图的起点：取系统记录的首次安装时间，升级上来的老用户也不会被算成今天
            if (!root.has(F_INSTALLED)) {
                root.put(F_INSTALLED, resolveInstallDate(context).toString())
                persist()
            }
            loaded = true
        }
    }

    private fun resolveInstallDate(context: Context): LocalDate = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        }
        Instant.ofEpochMilli(info.firstInstallTime).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrElse { LocalDate.now() }

    /** 跨天后清零"今日"计数 */
    private fun rolloverIfNeeded() {
        val today = dayFormat.format(Date())
        if (root.optString("day") != today) {
            root.put("day", today)
            val apps = root.optJSONObject("apps")
            if (apps != null) {
                for (pkg in apps.keys().asSequence().toList()) {
                    val item = apps.optJSONObject(pkg) ?: continue
                    item.put(F_TS, 0)
                    item.put(F_TJ, 0)
                }
            }
        }
    }

    fun record(pkg: String, kind: Kind) {
        if (pkg.isBlank()) return
        synchronized(lock) {
            if (!loaded) return
            rolloverIfNeeded()
            val apps = root.optJSONObject("apps") ?: JSONObject().also { root.put("apps", it) }
            val item = apps.optJSONObject(pkg) ?: JSONObject()
            if (kind == Kind.SPLASH) {
                item.put(F_S, item.optInt(F_S) + 1)
                item.put(F_TS, item.optInt(F_TS) + 1)
            } else {
                item.put(F_J, item.optInt(F_J) + 1)
                item.put(F_TJ, item.optInt(F_TJ) + 1)
            }
            item.put(F_LAST, System.currentTimeMillis())
            apps.put(pkg, item)
            root.put("apps", apps)

            // 额外记一份按天的总次数，供热力图使用
            val daily = root.optJSONObject(F_DAILY) ?: JSONObject().also { root.put(F_DAILY, it) }
            val today = LocalDate.now().toString()
            daily.put(today, daily.optInt(today) + 1)
            root.put(F_DAILY, daily)

            persist()
        }
    }

    private fun persist() {
        sp.edit().putString(KEY_ROOT, root.toString()).apply()
    }

    fun snapshot(): Snapshot {
        synchronized(lock) {
            rolloverIfNeeded()
            val apps = root.optJSONObject("apps")
            if (apps == null) return Snapshot(emptyList(), 0, 0)

            val list = ArrayList<AppStat>(apps.length())
            var total = 0
            var todayTotal = 0
            for (pkg in apps.keys().asSequence().toList()) {
                val item = apps.optJSONObject(pkg) ?: continue
                val stat = AppStat(
                    pkg = pkg,
                    splash = item.optInt(F_S),
                    jump = item.optInt(F_J),
                    todaySplash = item.optInt(F_TS),
                    todayJump = item.optInt(F_TJ),
                    lastAt = item.optLong(F_LAST)
                )
                if (stat.total == 0) continue
                total += stat.total
                todayTotal += stat.todayTotal
                list.add(stat)
            }
            list.sortWith(compareByDescending<AppStat> { it.total }.thenByDescending { it.lastAt })
            return Snapshot(list, todayTotal, total)
        }
    }

    /** 最近一次拦截距今毫秒数，没有记录时返回 -1 */
    fun lastInterceptAgeMs(): Long {
        synchronized(lock) {
            val apps = root.optJSONObject("apps") ?: return -1
            var last = 0L
            for (pkg in apps.keys().asSequence().toList()) {
                val item = apps.optJSONObject(pkg) ?: continue
                last = maxOf(last, item.optLong(F_LAST))
            }
            if (last == 0L) return -1
            return (System.currentTimeMillis() - last).coerceAtLeast(0)
        }
    }

    /** 每天的拦截总次数，供热力图使用 */
    fun dailyCounts(): Map<LocalDate, Int> {
        synchronized(lock) {
            val daily = root.optJSONObject(F_DAILY) ?: return emptyMap()
            val out = HashMap<LocalDate, Int>(daily.length())
            for (key in daily.keys().asSequence().toList()) {
                val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
                out[date] = daily.optInt(key)
            }
            return out
        }
    }

    /** 安装日期，热力图从这天开始画 */
    fun installedOn(): LocalDate {
        synchronized(lock) {
            val raw = root.optString(F_INSTALLED)
            return runCatching { LocalDate.parse(raw) }.getOrElse { LocalDate.now() }
        }
    }

    fun clear() {
        synchronized(lock) {
            val installed = root.optString(F_INSTALLED)
            root = JSONObject()
            root.put("day", dayFormat.format(Date()))
            if (installed.isNotBlank()) root.put(F_INSTALLED, installed)
            persist()
        }
    }
}

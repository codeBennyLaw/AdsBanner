package com.adsbanner.clean.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.provider.AlarmClock

/**
 * 应用名 / 图标 / 是否桌面，带内存缓存。
 * 无障碍服务回调在主线程，必须避免重复的 PackageManager 查询。
 */
object AppInfoHelper {

    private val labelCache = HashMap<String, String>()
    private val iconCache = HashMap<String, Drawable>()

    @Volatile
    private var homePackages: Set<String>? = null

    private data class AlarmHandlers(val packages: Set<String>, val loadedAt: Long)
    @Volatile private var alarmHandlers: AlarmHandlers? = null
    private val alarmLock = Any()

    /** Read handlers without launching an alarm action; all checks between refreshes are set lookups. */
    fun isProtectedFromAutomation(context: Context, pkg: String): Boolean {
        if (SystemAppSafety.isProtected(pkg)) return true
        val now = SystemClock.elapsedRealtime()
        val cached = alarmHandlers
        val handlers = if (cached != null && now - cached.loadedAt in 0 until 120_000L) cached else {
            synchronized(alarmLock) {
                alarmHandlers?.takeIf { now - it.loadedAt in 0 until 120_000L } ?: run {
                    val packages = listOf(AlarmClock.ACTION_SHOW_ALARMS, AlarmClock.ACTION_SET_ALARM,
                        AlarmClock.ACTION_SHOW_TIMERS, AlarmClock.ACTION_SET_TIMER).flatMap { action ->
                        runCatching {
                            context.packageManager.queryIntentActivities(Intent(action), 0)
                                .mapNotNull { it.activityInfo?.packageName }
                        }.getOrDefault(emptyList())
                    }.toSet()
                    AlarmHandlers(packages, now).also { alarmHandlers = it }
                }
            }
        }
        return SystemAppSafety.isProtected(pkg, handlers.packages)
    }

    fun appInfo(context: Context, pkg: String): ApplicationInfo? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(pkg, 0)
        }
    } catch (t: Throwable) {
        null
    }

    fun label(context: Context, pkg: String): String {
        synchronized(labelCache) { labelCache[pkg]?.let { return it } }
        val pm = context.packageManager
        val name = appInfo(context, pkg)?.let { info ->
            runCatching { pm.getApplicationLabel(info).toString() }.getOrNull()
        } ?: pkg
        synchronized(labelCache) { labelCache[pkg] = name }
        return name
    }

    fun icon(context: Context, pkg: String): Drawable? {
        synchronized(iconCache) { iconCache[pkg]?.let { return it } }
        val drawable = appInfo(context, pkg)?.let { info ->
            runCatching { context.packageManager.getApplicationIcon(info) }.getOrNull()
        } ?: return null
        synchronized(iconCache) { iconCache[pkg] = drawable }
        return drawable
    }

    /** 桌面 / 最近任务 / 系统界面 —— 从这些地方启动应用属于用户主动行为，不应拦截 */
    fun isHomeOrSystem(context: Context, pkg: String): Boolean {
        if (isProtectedFromAutomation(context, pkg)) return true
        val homes = homePackages ?: loadHomePackages(context).also { homePackages = it }
        return pkg in homes
    }

    private fun loadHomePackages(context: Context): Set<String> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    }.getOrDefault(emptySet())

    /** 有桌面入口的应用列表，用于拦截名单选择页 */
    fun launchableApps(context: Context): List<Pair<String, String>> = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        context.packageManager.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { it to label(context, it) }
            .sortedBy { it.second }
    }.getOrDefault(emptyList())
}

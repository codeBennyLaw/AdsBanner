package com.adsbanner.clean.core

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * 应用名 / 图标 / 是否桌面，带内存缓存。
 * 无障碍服务回调在主线程，必须避免重复的 PackageManager 查询。
 */
object AppInfoHelper {

    private val labelCache = HashMap<String, String>()
    private val iconCache = HashMap<String, Drawable>()

    @Volatile
    private var homePackages: Set<String>? = null

    private val systemPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"
    )

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
        if (pkg in systemPackages) return true
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

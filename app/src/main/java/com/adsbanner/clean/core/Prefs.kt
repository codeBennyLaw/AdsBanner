package com.adsbanner.clean.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 全局配置。使用 SharedPreferences 而不是 DataStore，避免引入协程依赖，
 * 同时让无障碍服务在任意时刻都能同步读取配置。
 */
object Prefs {

    private const val FILE = "adsbanner_prefs"

    private const val KEY_SKIP_SPLASH = "skip_splash"
    private const val KEY_JUMP_GUARD = "jump_guard"
    private const val KEY_KEEP_ALIVE = "keep_alive"
    private const val KEY_INTERCEPT_NOTIFY = "intercept_notify"
    private const val KEY_TARGETS = "jump_targets"
    private const val KEY_SPLASH_WINDOW = "splash_window_ms"
    private const val KEY_JUMP_WINDOW = "jump_window_ms"
    private const val KEY_REMINDER_SHOWN = "rom_reminder_shown"

    /** 默认的"误触跳转"目标：电商、短视频、浏览器，这些是广告落地页最常见的去处 */
    val DEFAULT_JUMP_TARGETS: Set<String> = linkedSetOf(
        "com.taobao.taobao",
        "com.tmall.wireless",
        "com.jingdong.app.mall",
        "com.jd.jdlite",
        "com.xunmeng.pinduoduo",
        "com.achievo.vipshop",
        "com.eg.android.AlipayGphone",
        "com.suning.mobile.ebuy",
        "com.taobao.idlefish",
        "com.taobao.litetao",
        "com.shizhuang.duapp",
        "com.xingin.xhs",
        "com.ss.android.ugc.aweme",
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
        "com.sankuai.meituan",
        "me.ele",
        "com.dianping.v1",
        "ctrip.android.view",
        "com.tencent.mtt",
        "com.UCMobile",
        "com.quark.browser",
        "com.android.chrome"
    )

    private lateinit var sp: SharedPreferences

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!sp.contains(KEY_TARGETS)) {
            sp.edit().putString(KEY_TARGETS, DEFAULT_JUMP_TARGETS.joinToString("|")).apply()
        }
    }

    private fun ensure() {
        check(::sp.isInitialized) { "Prefs.init() 未调用" }
    }

    /** 开屏广告自动跳过 */
    var skipSplash: Boolean
        get() = ensure().let { sp.getBoolean(KEY_SKIP_SPLASH, true) }
        set(value) {
            sp.edit().putBoolean(KEY_SKIP_SPLASH, value).apply()
        }

    /** 误触跳转拦截 */
    var jumpGuard: Boolean
        get() = ensure().let { sp.getBoolean(KEY_JUMP_GUARD, true) }
        set(value) {
            sp.edit().putBoolean(KEY_JUMP_GUARD, value).apply()
        }

    /** 前台常驻通知 */
    var keepAlive: Boolean
        get() = ensure().let { sp.getBoolean(KEY_KEEP_ALIVE, true) }
        set(value) {
            sp.edit().putBoolean(KEY_KEEP_ALIVE, value).apply()
        }

    /** 拦截时在状态栏 / 灵动岛弹出 10 秒提示 */
    var interceptNotify: Boolean
        get() = ensure().let { sp.getBoolean(KEY_INTERCEPT_NOTIFY, true) }
        set(value) {
            sp.edit().putBoolean(KEY_INTERCEPT_NOTIFY, value).apply()
        }

    /** 跳转拦截目标名单 */
    var jumpTargets: Set<String>
        get() {
            ensure()
            val raw = sp.getString(KEY_TARGETS, "") ?: ""
            if (raw.isBlank()) return emptySet()
            return raw.split('|').filter { it.isNotBlank() }.toSet()
        }
        set(value) {
            ensure()
            sp.edit().putString(KEY_TARGETS, value.filter { it.isNotBlank() }.joinToString("|")).apply()
        }

    /** 进入应用后多长时间内仍尝试点击跳过按钮 */
    var splashWindowMs: Long
        get() = ensure().let { sp.getLong(KEY_SPLASH_WINDOW, 10_000L) }
        set(value) {
            sp.edit().putLong(KEY_SPLASH_WINDOW, value).apply()
        }

    /** 应用切换间隔小于该值时，才认为是一次"被拉起"的跳转 */
    var jumpWindowMs: Long
        get() = ensure().let { sp.getLong(KEY_JUMP_WINDOW, 2_000L) }
        set(value) {
            sp.edit().putLong(KEY_JUMP_WINDOW, value).apply()
        }

    var romReminderShown: Boolean
        get() = ensure().let { sp.getBoolean(KEY_REMINDER_SHOWN, false) }
        set(value) {
            sp.edit().putBoolean(KEY_REMINDER_SHOWN, value).apply()
        }
}

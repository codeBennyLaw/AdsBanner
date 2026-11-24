package com.adsbanner.clean.core

/** App-owned splash controls, separate from ordinary text containing the word "skip". */
internal object KnownSplashControls {
    private val biliPackages = setOf("tv.danmaku.bili", "tv.danmaku.bilibilihd")
    private val timerOnly = Regex("^[0-9]{1,2}\\s*[sS秒]?$")

    fun queryIds(pkg: String): List<String> = if (pkg in biliPackages)
        listOf("$pkg:id/count_down", "$pkg:id/skip") else emptyList()

    fun isControlId(pkg: String, id: String?): Boolean = id != null && id in queryIds(pkg)

    fun hasControls(pkg: String): Boolean = pkg in biliPackages

    fun allowsControlActivity(pkg: String, activity: String): Boolean = pkg in biliPackages &&
        activity.isNotEmpty() && !activity.contains(".preferences.") && !activity.contains(".settings.") &&
        !activity.startsWith("com.bilibili.search")

    fun isMainActivity(pkg: String, activity: String): Boolean = pkg in biliPackages &&
        (activity == "$pkg.MainActivityV2" || activity == "tv.danmaku.bili.MainActivityV2")

    fun normalizeLabel(pkg: String, activity: String, id: String?, raw: String,
        bounds: SkipTargetPolicy.Bounds, width: Int, height: Int): String? {
        if (!isControlId(pkg, id) || !allowsControlActivity(pkg, activity)) return null
        val label = SkipRule.normalizedLabel(raw)
        if (SkipRule.match(label) == SkipRule.STRONG) return label
        if (SkipTargetPolicy.score(SkipRule.STRONG, bounds, width, height) !in setOf(90, 100)) return null
        if (label.isEmpty()) return "跳过"
        if (timerOnly.matches(label) && label.takeWhile { it.isDigit() }.toIntOrNull()?.let { it in 1..99 } == true) return "跳过 $label"
        return null
    }

    fun isDedicatedActivity(pkg: String, activity: String): Boolean = when {
        pkg == "com.chaoxing.mobile" -> activity == "com.chaoxing.mobile.activity.SplashActivity"
        pkg in biliPackages -> activity.startsWith("tv.danmaku.bili.ui.splash.ad.") ||
            activity.startsWith("tv.danmaku.bilibilihd.ui.splash.ad.")
        else -> false
    }

    fun matches(pkg: String, activity: String, id: String?, label: String,
        bounds: SkipTargetPolicy.Bounds, width: Int, height: Int): Boolean {
        if (SkipRule.match(label) != SkipRule.STRONG || JumpAdRule.skipCountdownSeconds(label) == 0 ||
            SkipTargetPolicy.score(SkipRule.STRONG, bounds, width, height) <= 0) return false
        if (isControlId(pkg, id)) return allowsControlActivity(pkg, activity)
        return isDedicatedActivity(pkg, activity) && bounds.width <= width * 0.32f &&
            bounds.height <= height * 0.08f && SkipTargetPolicy.score(SkipRule.STRONG, bounds, width, height) in setOf(90, 100)
    }
}

package com.adsbanner.clean.core

/**
 * "跳过按钮"文案识别。
 *
 * 只做文案判断，位置判断交给 AccessibilityService（需要结合屏幕尺寸）。
 * 强匹配覆盖全屏小型控件，启动兼容与恢复检测分别校验。普通关闭仅限启动初期右上角，
 * 叉号另需独立广告标记与角落位置校验，不能只凭图形关闭普通弹窗。
 */
object SkipRule {
    private val whitespace = Regex("\\s+")
    private val invisible = Regex("[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]")
    private val splitSkip = Regex("跳\\s+过")
    private val adPrefix = Regex("^(广告|ad(?:vertisement)?)\\s*[|｜丨/·•]?\\s*", RegexOption.IGNORE_CASE)
    private val closeGlyph = Regex("^[xX×✕✖╳✗❌Ｘｘ]$")
    private val camelBoundary = Regex("([a-z0-9])([A-Z])")
    private val idSeparator = Regex("[^a-z0-9]+")
    private val adIdWords = setOf("ad", "ads", "advert", "advertisement", "splash")
    private val closeIdWords = setOf("close", "dismiss", "cross")
    private val countdownPart = Regex("^[0-9]{1,2}\\s*[sS秒]?$")
    private val standaloneAdMarker = Regex("^(广告|ad|advertisement)(\\s*[|丨/·•]?\\s*[0-9]{1,2}\\s*[sS秒]?)?$", RegexOption.IGNORE_CASE)

    const val NONE = 0
    const val WEAK = 1
    const val STRONG = 2

    /** 强匹配：明确是开屏广告的跳过按钮 */
    private val strongPatterns = listOf(
        // 跳过 / 跳过广告 / 跳过5 / 跳过 5s / 跳过广告5秒
        Regex("^跳过\\s*(广告)?\\s*[0-9]{0,2}\\s*[sS秒]?$"),
        // 2跳过 / 2s｜跳过 / 5秒 · 跳过广告
        Regex("^[0-9]{1,2}\\s*[sS秒]?\\s*[|｜丨/·•]?\\s*跳过(广告)?$"),
        // 跳过丨5 / 跳过|5 / 跳过 · 5
        Regex("^跳过\\s*[|｜丨/·•]\\s*[0-9]{1,2}\\s*[sS秒]?$"),
        Regex("^跳过\\s*[（(]\\s*[0-9]{1,2}\\s*[sS秒]?\\s*[）)]$"),
        // 部分广告会把倒计时和按钮文案拆成「跳过 广告 3 秒」。
        Regex("^跳过\\s*(广告)?\\s*[0-9]{1,2}\\s*[sS秒]$"),
        // 点击跳过 / 点击跳过广告
        Regex("^(点击|轻点)\\s*跳过(广告)?\\s*([0-9]{1,2}\\s*[sS秒]?)?$"),
        // 关闭广告 / 关闭开屏广告（本质仍是广告专属按钮）
        Regex("^关闭(开屏)?广告$"),
        // skip / skip ad / 5s skip
        Regex("^skip(\\s*ad)?$", RegexOption.IGNORE_CASE),
        Regex("^[0-9]{1,2}\\s*s\\s*skip(\\s*ad)?$", RegexOption.IGNORE_CASE),
        Regex("^skip\\s*[0-9]{1,2}\\s*s$", RegexOption.IGNORE_CASE),
        Regex("^skip\\s*ad\\s*[0-9]{1,2}\\s*s?$", RegexOption.IGNORE_CASE)
    )

    /** 普通关闭限右上角，叉号由独立的广告与角落策略校验。 */
    private val weakPatterns = listOf(
        Regex("^关闭$"),
        closeGlyph
    )

    /** 明显不是开屏按钮的短语，包含这些词直接否决 */
    private val denyWords = listOf("片头", "片尾", "跳过此", "跳过下", "剧情", "会员", "付费", "订阅",
        "闹钟", "响铃", "来电", "通话", "alarm", "snooze")

    internal fun normalizedLabel(raw: String): String = raw.replace(invisible, "")
            .replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace('\n', ' ')
            .replace(whitespace, " ")
            .replace(splitSkip, "跳过")
            .trim()

    fun match(raw: String): Int {
        val text = normalizedLabel(raw)
        if (text.isEmpty() || text.length > 32) return NONE
        if (denyWords.any { text.contains(it, ignoreCase = true) }) return NONE

        val action = adPrefix.find(text)?.let { text.substring(it.range.last + 1) } ?: text
        if (action.length > 16) return NONE
        for (p in strongPatterns) {
            if (p.matches(action)) return STRONG
        }

        for (p in weakPatterns) {
            if (p.matches(text)) return WEAK
        }
        return NONE
    }

    fun isCloseIcon(raw: String): Boolean = closeGlyph.matches(normalizedLabel(raw))

    /** ID-only icons require both an ad/splash token and a close action; generic toolbar IDs do not qualify. */
    fun isAdCloseResourceId(viewId: String?): Boolean {
        if (viewId == null || !viewId.contains(":id/")) return false
        val entry = viewId.substringAfter(":id/")
        if (entry.length !in 1..128) return false
        val words = entry.replace(camelBoundary, "$1_$2").lowercase(java.util.Locale.ROOT).split(idSeparator).toSet()
        return words.any { it in adIdWords } &&
            words.any { it in closeIdWords || (it == "skip" && "splash" in words) }
    }

    /** Join only an exact countdown belonging to this same small skip control. */
    fun withCountdown(label: String, countdown: String): String? {
        val part = normalizedLabel(countdown)
        if (!countdownPart.matches(part) || match(label) != STRONG) return null
        return listOf("$label $part", "$part $label").firstOrNull {
            match(it) == STRONG && JumpAdRule.isAdSkip(it)
        }
    }

    /** Some SDKs put the timer in text and the skip action in contentDescription. */
    fun buttonLabel(text: String, description: String): String {
        val primary = text.trim()
        val alternate = description.trim()
        if (primary.isEmpty()) return alternate
        withCountdown(primary, alternate)?.let { return it }
        withCountdown(alternate, primary)?.let { return it }
        if (isStandaloneAdMarker(primary) && match(alternate) == STRONG) return alternate
        if (match(primary) != NONE && match(alternate) == STRONG && JumpAdRule.isAdSkip(alternate)) return alternate
        return primary
    }

    /** A plain countdown also belongs to alarms/timers; it cannot authorize a generic close. */
    fun hasAdEvidence(raw: String): Boolean = raw.contains("广告") || JumpAdRule.isAdSkip(raw)

    /** Outside the top region, only a standalone ad label may authorize a plain skip. */
    fun isStandaloneAdMarker(raw: String): Boolean = raw.length <= 20 && standaloneAdMarker.matches(raw.trim())

    /** Embedded pages are not application launch screens; never let the splash engine click their controls. */
    fun isEmbeddedPage(pkg: String, className: String): Boolean = when (pkg) {
        "com.tencent.mm" -> className.startsWith("com.tencent.mm.plugin.appbrand.")
        "com.tencent.mobileqq" -> className.startsWith("com.tencent.mobileqq.mini.") ||
            className == "com.tencent.mobileqq.activity.miniaio.MiniChatActivity" || className.startsWith("com.tencent.qqmini.")
        "com.eg.android.AlipayGphone" -> className.startsWith("com.alipay.mobile.nebulax.") ||
            className.startsWith("com.alipay.mobile.nebula.activity.H5")
        else -> false
    }
}

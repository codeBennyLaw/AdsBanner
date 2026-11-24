package com.adsbanner.clean.core

/** 普通「跳过」也可能是引导页，必须再有广告标记或明确倒计时。 */
internal object JumpAdRule {
    private val englishAd = Regex("^(skip\\s*ad(\\s*[0-9]{1,2}\\s*s?)?|(ad|advertisement)\\s*[|｜丨/·•]?\\s*skip)$", RegexOption.IGNORE_CASE)
    private val counterNumber = Regex("[0-9]{1,2}")

    fun isAdSkip(raw: String, hasAdMarker: Boolean = false): Boolean {
        if (SkipRule.match(raw) != SkipRule.STRONG) return false
        val label = SkipRule.normalizedLabel(raw)
        return hasAdMarker || label.contains("广告") || englishAd.matches(label) || counterNumber.containsMatchIn(label)
    }

    /** Ad-related words also occur in settings and articles; only an exact skip timer is self-contained proof. */
    fun hasSkipCountdown(raw: String): Boolean = SkipRule.match(raw) == SkipRule.STRONG &&
        counterNumber.containsMatchIn(SkipRule.normalizedLabel(raw))

    fun skipCountdownSeconds(raw: String): Int? = if (hasSkipCountdown(raw))
        counterNumber.find(SkipRule.normalizedLabel(raw))?.value?.toIntOrNull() else null
}

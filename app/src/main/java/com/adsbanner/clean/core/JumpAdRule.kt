package com.adsbanner.clean.core

/** 普通「跳过」也可能是引导页，必须再有广告标记或明确倒计时。 */
internal object JumpAdRule {
    private val countdown = Regex("\\d{1,2}\\s*[sS秒]|^[0-9]{1,2}\\s*跳过(广告)?$|^(点击|轻点)?\\s*跳过(广告)?\\s*\\d{1,2}$|^跳过\\s*[|｜丨/·•（(]\\s*\\d{1,2}\\s*[）)]?$")
    private val englishAd = Regex("^(skip\\s*ad(\\s*[0-9]{1,2}\\s*s?)?|(ad|advertisement)\\s*[|｜丨/·•]?\\s*skip)$", RegexOption.IGNORE_CASE)

    fun isAdSkip(raw: String, hasAdMarker: Boolean = false): Boolean {
        if (SkipRule.match(raw) != SkipRule.STRONG) return false
        val label = SkipRule.normalizedLabel(raw)
        return hasAdMarker || label.contains("广告") || englishAd.matches(label) || countdown.containsMatchIn(label)
    }
}

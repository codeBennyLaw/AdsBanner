package com.adsbanner.clean.core

/** 普通「跳过」也可能是引导页，必须再有广告标记或明确倒计时。 */
internal object JumpAdRule {
    private val countdown = Regex("\\d{1,2}\\s*[sS秒]|^跳过(广告)?\\s*\\d{1,2}$|^跳过\\s*[|丨/·•]\\s*\\d{1,2}$")
    private val englishAd = Regex("^skip\\s*ad$", RegexOption.IGNORE_CASE)

    fun isAdSkip(raw: String, hasAdMarker: Boolean = false): Boolean {
        if (SkipRule.match(raw) != SkipRule.STRONG) return false
        val label = raw.replace(Regex("\\s+"), " ").trim()
        return hasAdMarker || label.contains("广告") || englishAd.matches(label) || countdown.containsMatchIn(label)
    }
}

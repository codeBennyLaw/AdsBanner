package com.adsbanner.clean.core

/**
 * "跳过按钮"文案识别。
 *
 * 只做文案判断，位置判断交给 AccessibilityService（需要结合屏幕尺寸）。
 * 分级的目的：强匹配允许在屏幕任意位置点击，弱匹配（关闭 / ×）只在右上角才敢点，
 * 否则极易误关正常页面的弹窗。
 */
object SkipRule {

    const val NONE = 0
    const val WEAK = 1
    const val STRONG = 2

    /** 强匹配：明确是开屏广告的跳过按钮 */
    private val strongPatterns = listOf(
        // 跳过 / 跳过广告 / 跳过5 / 跳过 5s / 跳过广告5秒
        Regex("^跳过(广告)?\\s*[0-9]{0,2}\\s*[sS秒]?$"),
        // 5s 跳过 / 5秒跳过 / 3s跳过广告
        Regex("^[0-9]{1,2}\\s*[sS秒]\\s*(后)?\\s*跳过(广告)?$"),
        // 跳过丨5 / 跳过|5 / 跳过 · 5
        Regex("^跳过\\s*[|丨/·•]\\s*[0-9]{1,2}$"),
        // 部分广告会把倒计时和按钮文案拆成「跳过 广告 3 秒」。
        Regex("^跳过\\s*(广告)?\\s*[0-9]{1,2}\\s*[sS秒]$"),
        // 点击跳过 / 点击跳过广告
        Regex("^(点击|轻点)\\s*跳过(广告)?$"),
        // 关闭广告 / 关闭开屏广告（本质仍是广告专属按钮）
        Regex("^关闭(开屏)?广告$"),
        // skip / skip ad / 5s skip
        Regex("^skip(\\s*ad)?$", RegexOption.IGNORE_CASE),
        Regex("^[0-9]{1,2}\\s*s\\s*skip(\\s*ad)?$", RegexOption.IGNORE_CASE),
        Regex("^skip\\s*[0-9]{1,2}\\s*s$", RegexOption.IGNORE_CASE)
    )

    /** 弱匹配：只在右上角区域点击，避免误伤普通弹窗 */
    private val weakPatterns = listOf(
        Regex("^关闭$"),
        Regex("^跳\\s*过$"),
        Regex("^[xX×✕✖╳]$")
    )

    /** 明显不是开屏按钮的短语，包含这些词直接否决 */
    private val denyWords = listOf("片头", "片尾", "跳过此", "跳过下", "剧情", "会员", "付费", "订阅",
        "闹钟", "响铃", "来电", "通话", "alarm", "snooze")

    fun match(raw: String): Int {
        val text = raw.replace('\u00A0', ' ')
            .replace('\u3000', ' ')
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isEmpty() || text.length > 16) return NONE
        if (denyWords.any { text.contains(it, ignoreCase = true) }) return NONE

        for (p in strongPatterns) {
            if (p.matches(text)) return STRONG
        }

        // 兜底：短文案内含"跳过"，覆盖"跳过 广告"这类带空格的变体
        if (text.contains("跳过") && text.length <= 8) return STRONG

        for (p in weakPatterns) {
            if (p.matches(text)) return WEAK
        }
        return NONE
    }

    /** A plain countdown also belongs to alarms/timers; it cannot authorize a generic close. */
    fun hasAdEvidence(raw: String): Boolean = raw.contains("广告") || JumpAdRule.isAdSkip(raw)
}

package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class AdRuleCodecTest {
    private val rule = LocalAdRule("sample", "示例开屏", "example.app", "example.app:id/skip", "example.app.SplashActivity", "跳过")

    @Test fun roundTripRetainsEveryConstraintAndDisabledState() {
        val rules = listOf(rule, rule.copy(id = "second", enabled = false, region = LocalAdRule.Region.PAGE, maxAgeMs = 3000))
        assertEquals(rules, AdRuleCodec.decode(AdRuleCodec.encode(rules)))
    }

    @Test fun rejectsOtherFormatsUnknownFieldsAndTrailingDocuments() {
        listOf("{\"id\":1,\"apps\":[]}", "{\"schemaVersion\":2,\"rules\":[]}",
            "{\"schemaVersion\":1,\"rules\":[],\"script\":\"run\"}",
            "{\"schemaVersion\":1,\"rules\":[]}{}", "[]").forEach(::reject)
    }

    @Test fun rejectsOversizeDeepOrAmbiguousDocuments() {
        reject(" ".repeat(AdRuleCodec.MAX_BYTES + 1))
        reject("[".repeat(30) + "]".repeat(30))
        reject(AdRuleCodec.encode(listOf(rule)).replace("\"enabled\": true", "\"enabled\": \"true\""))
        reject(AdRuleCodec.encode(listOf(rule)).replace("10000", "1000.5"))
    }

    @Test fun duplicateRulesAndUnboundedPerAppWorkAreNotAccepted() {
        invalid(listOf(rule, rule))
        invalid((0..10).map { rule.copy(id = "rule$it") })
        invalid((0..100).map { rule.copy(id = "r$it", packageName = "app.p$it") })
    }

    @Test fun editingCannotRemoveRequiredTargetOrPageProtection() {
        listOf(rule.copy(viewId = "skip"), rule.copy(packageName = "com.android.systemui"),
            rule.copy(maxAgeMs = 0), rule.copy(region = LocalAdRule.Region.PAGE, activityPrefix = ""),
            rule.copy(packageName = "com.tencent.mm", activityPrefix = "com.tencent.mm.plugin.appbrand.ui.AppBrandUI"))
            .forEach { invalid(listOf(it)) }
    }

    @Test fun matchesOnlyItsOwnFreshSmallNodeAndPage() {
        val bounds = SkipTargetPolicy.Bounds(800, 90, 950, 150)
        fun matches(pkg: String = rule.packageName, activity: String = rule.activityPrefix, age: Long = 500,
            id: String = rule.viewId, text: String = rule.text, box: SkipTargetPolicy.Bounds = bounds) =
            rule.matches(pkg, activity, age, id, text, box, 1000, 2000)
        assertTrue(matches())
        assertFalse(matches(pkg = "another.app"))
        assertFalse(matches(activity = "example.app.HomeActivity"))
        assertFalse(matches(age = 10001))
        assertFalse(matches(id = "example.app:id/buy"))
        assertFalse(matches(text = "购买"))
        assertFalse(matches(box = bounds.copy(top = 1600, bottom = 1700)))
        assertFalse(matches(box = SkipTargetPolicy.Bounds(0, 0, 1000, 2000)))
        assertFalse(rule.copy(enabled = false).appliesTo(rule.packageName, rule.activityPrefix, 0))
    }

    private fun reject(raw: String) { assertTrue(raw.take(80), runCatching { AdRuleCodec.decode(raw) }.isFailure) }
    private fun invalid(rules: List<LocalAdRule>) { assertTrue(runCatching { AdRuleCodec.validate(rules) }.isFailure) }
}

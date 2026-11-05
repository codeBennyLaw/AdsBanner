package com.adsbanner.clean.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** A bounded, data-only format. An invalid document never replaces a valid ruleset. */
internal object AdRuleCodec {
    const val MAX_BYTES = 256 * 1024
    const val MAX_RULES = 100
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val idPattern = Regex("[A-Za-z0-9_.:-]{1,80}")
    private val resourcePattern = Regex("[A-Za-z][A-Za-z0-9_.]*:id/[A-Za-z0-9_]+")
    private val rootKeys = setOf("schemaVersion", "rules")
    private val ruleKeys = setOf("id", "name", "packageName", "viewId", "activityPrefix", "text", "region", "maxAgeMs", "enabled")

    fun validPackage(pkg: String) = pkg.length <= 200 && packagePattern.matches(pkg)

    fun validate(rules: List<LocalAdRule>) {
        require(rules.size <= MAX_RULES) { "最多导入 $MAX_RULES 条规则" }
        require(rules.map { it.id }.toSet().size == rules.size) { "规则标识不能重复" }
        require(rules.groupingBy { it.packageName }.eachCount().values.all { it <= 10 }) { "每个应用最多 10 条规则" }
        rules.forEach { rule ->
            require(idPattern.matches(rule.id)) { "规则标识格式不正确" }
            require(rule.name.isNotBlank() && rule.name.length <= 60 && rule.name.none { it.isISOControl() }) { "规则名称需为 1–60 个字符" }
            require(validPackage(rule.packageName) && !SystemAppSafety.isProtected(rule.packageName)) { "应用包名无效或属于系统保护范围" }
            require(rule.viewId.length <= 250 && resourcePattern.matches(rule.viewId)) { "节点 ID 需使用完整的 包名:id/名称 格式" }
            require(rule.activityPrefix.isEmpty() || rule.activityPrefix.length <= 250 &&
                rule.activityPrefix.contains('.') && rule.activityPrefix.all { it.isLetterOrDigit() || it in "_.$" }) { "页面前缀格式不正确" }
            require(rule.text.length <= 60 && rule.text.none { it == '\u0000' }) { "匹配文字最多 60 个字符" }
            require(rule.maxAgeMs in 1_000..10_000) { "匹配时间需为 1–10 秒" }
            require(rule.region != LocalAdRule.Region.PAGE || rule.activityPrefix.isNotEmpty()) { "页面内任意位置必须指定页面前缀" }
            require(!SkipRule.isEmbeddedPage(rule.packageName, rule.activityPrefix)) { "小程序页面不支持自动点击" }
        }
    }

    fun decode(raw: String): List<LocalAdRule> {
        require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "规则文件不能超过 256 KB" }
        checkDepth(raw)
        val parser = JSONTokener(raw)
        val root = parser.nextValue()
        require(root is JSONObject && parser.nextClean() == '\u0000') { "规则文件必须是一个完整对象" }
        require(root.keys().asSequence().all { it in rootKeys }) { "文件不是 AdsBanner 规则格式" }
        require(root.opt("schemaVersion") is Number && root.getDouble("schemaVersion") == 1.0) { "不支持此规则格式版本" }
        val items = root.getJSONArray("rules")
        require(items.length() <= MAX_RULES) { "最多导入 $MAX_RULES 条规则" }
        val rules = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            require(item.keys().asSequence().all { it in ruleKeys }) { "第 ${index + 1} 条规则含不支持的字段" }
            fun string(key: String, fallback: String? = null): String {
                val value = item.opt(key)
                if (value == null && fallback != null) return fallback
                require(value is String) { "$key 必须是文字" }
                return value.trim()
            }
            val age = item.opt("maxAgeMs") ?: 10_000L
            require(age is Number && age.toDouble() == age.toLong().toDouble()) { "匹配时间必须是整数" }
            val enabled = item.opt("enabled") ?: true
            require(enabled is Boolean) { "enabled 必须是布尔值" }
            LocalAdRule(string("id"), string("name"), string("packageName"), string("viewId"),
                string("activityPrefix", ""), string("text", ""), LocalAdRule.Region.valueOf(string("region", "TOP")), age.toLong(), enabled)
        }
        validate(rules)
        return rules
    }

    fun encode(rules: List<LocalAdRule>): String {
        validate(rules)
        val items = JSONArray()
        rules.forEach { rule -> items.put(JSONObject().put("id", rule.id).put("name", rule.name)
            .put("packageName", rule.packageName).put("viewId", rule.viewId).put("activityPrefix", rule.activityPrefix)
            .put("text", rule.text).put("region", rule.region.name).put("maxAgeMs", rule.maxAgeMs).put("enabled", rule.enabled)) }
        return JSONObject().put("schemaVersion", 1).put("rules", items).toString(2)
    }

    private fun checkDepth(raw: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        raw.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) { "规则嵌套层级过多" } }
                '}', ']' -> { depth--; require(depth >= 0) { "规则格式不完整" } }
            }
        }
        require(!quoted && depth == 0) { "规则格式不完整" }
    }
}

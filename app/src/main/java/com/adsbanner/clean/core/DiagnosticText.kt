package com.adsbanner.clean.core

import org.json.JSONArray
import org.json.JSONObject

/** Bounds every dialog/clipboard report, including records written by older diagnostic builds. */
internal object DiagnosticText {
    const val MAX_CHARS = 1600
    const val MAX_UTF8_BYTES = 4096
    private const val SUFFIX = "\n…内容已精简。"
    private val identifier = Regex("[A-Za-z0-9_.$-]{1,48}")
    private val bounds = Regex("-?\\d{1,5}(,-?\\d{1,5}){3}")
    private val screen = Regex("\\d{1,5}x\\d{1,5}")

    fun cainiaoSample(samples: JSONArray): String? {
        val sample = (0 until minOf(samples.length(), 3)).mapNotNull(samples::optJSONObject)
            .maxWithOrNull(compareBy<JSONObject> { it.optInt("rank") }.thenBy { it.optLong("sampledAtMs") }) ?: return null
        val nodes = sample.optJSONArray("targets")
        val target = (0 until minOf(nodes?.length() ?: 0, 3)).mapNotNull { nodes?.optJSONObject(it) }
            .maxByOrNull { it.optInt("priority") }
        val markers = sample.optJSONArray("lowerMarkers")
        val marker = (0 until minOf(markers?.length() ?: 0, 4)).mapNotNull { markers?.optJSONObject(it) }
            .firstOrNull { it.optString("label") == "ad" || it.optString("id") == CainiaoSplashPolicy.MS_AD_TAG_ID }
        val decisions = sample.optJSONArray("decisions")
        val checks = (0 until minOf(decisions?.length() ?: 0, 4)).mapNotNull { index ->
            when (decisions?.optString(index)) {
                "candidate-ad-proof-passed" -> "广告证据通过"
                "candidate-ad-proof-rejected" -> "广告证据拒绝"
                "restricted-target-passed" -> "专用位置通过"
                "restricted-target-rejected" -> "专用位置拒绝"
                else -> null
            }
        }
        return buildString {
            append(sample.optString("screen").takeIf(screen::matches) ?: "尺寸未知")
            append(" 遮挡=${flag(sample, "covered")} 受限=${flag(sample, "restricted")}")
            append(" 容器=${sample.optInt("exactContainers")} 节点=${sample.optInt("visited")}")
            append(" 缺失=${sample.optInt("missingChildren")} 读取异常=${sample.optJSONArray("readErrors")?.length() ?: 0}")
            if (checks.isNotEmpty()) append("\n${checks.joinToString("、")}")
            append("\n候选：${target?.let(::nodeSummary) ?: "无"}")
            target?.optJSONArray("ancestors")?.optJSONObject(0)?.let { parent ->
                append(" 父=${nodeId(parent)}")
            }
            append("\n广告标记：${marker?.let(::nodeSummary) ?: "样本未见"}")
        }
    }

    private fun nodeSummary(node: JSONObject): String {
        val type = node.optString("class").substringAfterLast('.').takeIf(identifier::matches) ?: "?"
        val box = node.optString("bounds").takeIf(bounds::matches) ?: "?"
        val label = when (node.optString("label")) {
            "skip" -> "跳过"
            "ad" -> "广告"
            "empty" -> "空"
            else -> "其他"
        }
        val normalized = if (node.optString("normalized") == "skip" && label != "跳过") "→跳过" else ""
        return "$type/${nodeId(node)} [$box] $label$normalized 可见=${flag(node, "visible")} 启用=${flag(node, "enabled")} 点击=${flag(node, "click")}"
    }

    private fun nodeId(node: JSONObject): String = node.optString("id").substringAfterLast('/')
        .takeIf(identifier::matches) ?: "?"

    private fun flag(info: JSONObject, key: String): String = when (info.opt(key)) {
        true -> "是"
        false -> "否"
        else -> "?"
    }

    fun limit(text: String): String {
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            val chars = Character.charCount(point)
            val size = utf8Size(point)
            if (result.length + chars > MAX_CHARS || bytes + size > MAX_UTF8_BYTES) break
            result.appendCodePoint(point)
            bytes += size
            index += chars
        }
        if (index == text.length) return result.toString()
        val suffixBytes = SUFFIX.toByteArray(Charsets.UTF_8).size
        while (result.length + SUFFIX.length > MAX_CHARS || bytes + suffixBytes > MAX_UTF8_BYTES) {
            val point = Character.codePointBefore(result, result.length)
            bytes -= utf8Size(point)
            result.setLength(result.length - Character.charCount(point))
        }
        return result.append(SUFFIX).toString()
    }

    private fun utf8Size(point: Int): Int = when {
        point <= 0x7f -> 1
        point <= 0x7ff -> 2
        point <= 0xffff -> 3
        else -> 4
    }
}

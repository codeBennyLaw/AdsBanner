package com.adsbanner.clean.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiagnosticTextTest {
    @Test fun ordinaryShortReportIsUnchanged() {
        val report = "当前版本：3.1.6（26）\n菜鸟：动作 0 次\n通知总开关：开启"
        assertEquals(report, DiagnosticText.limit(report))
        assertEquals("", DiagnosticText.limit(""))
    }

    @Test fun repeatedStructuralHistoryCannotOverflowClipboardBounds() {
        for (text in listOf("节点".repeat(100_000), "A".repeat(100_000), "🙂\n诊断".repeat(100_000))) {
            val result = DiagnosticText.limit(text)
            assertTrue(result.length <= DiagnosticText.MAX_CHARS)
            assertTrue(result.toByteArray(Charsets.UTF_8).size <= DiagnosticText.MAX_UTF8_BYTES)
            assertTrue(result.endsWith("…内容已精简。"))
            assertEquals(result, DiagnosticText.limit(result))
            assertEquals(result, result.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
        }
    }

    @Test fun contentAtTheByteBoundaryPreservesCompleteCodePoints() {
        val result = DiagnosticText.limit("中".repeat(1300) + "🙂".repeat(120))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 4096)
        assertFalse(result.contains('\uFFFD'))
        result.forEachIndexed { index, char ->
            if (Character.isHighSurrogate(char)) assertTrue(index + 1 < result.length && Character.isLowSurrogate(result[index + 1]))
            if (Character.isLowSurrogate(char)) assertTrue(index > 0 && Character.isHighSurrogate(result[index - 1]))
        }
    }

    @Test fun shortCainiaoSampleKeepsDecisionAndNodeMetadataWithoutRawHistory() {
        val target = JSONObject().put("id", CainiaoSplashPolicy.MS_SKIP_ID).put("class", "android.view.View")
            .put("bounds", "1112,126,1219,233").put("priority", 100).put("label", "empty").put("normalized", "skip")
            .put("visible", true).put("enabled", true).put("click", false).put("text", "私有页面文本".repeat(50_000))
            .put("ancestors", JSONArray().put(JSONObject().put("id", CainiaoSplashPolicy.MS_SKIP_CONTAINER_ID)))
        val best = JSONObject().put("rank", 3000).put("screen", "1264x2736").put("covered", true).put("restricted", false)
            .put("exactContainers", 1).put("targets", JSONArray().put(target))
            .put("decisions", JSONArray().put("restricted-target-rejected").put("私有页面文本"))
            .put("lowerMarkers", JSONArray().put(JSONObject().put("id", CainiaoSplashPolicy.MS_AD_TAG_ID)))
        val lowerRank = JSONObject().put("rank", 2000).put("sampledAtMs", 3000).put("screen", "800x600")
        val summary = DiagnosticText.cainiaoSample(JSONArray().put(lowerRank).put(best))!!
        assertTrue(summary.contains("1264x2736 遮挡=是 受限=否 容器=1"))
        assertTrue(summary.contains("专用位置拒绝"))
        assertTrue(summary.contains("View/ms_skipView [1112,126,1219,233] 空→跳过 可见=是 启用=是 点击=否 父=ms_skipView_container"))
        assertTrue(summary.contains("广告标记：?/ms_ad_tag_layout"))
        assertFalse(summary.contains("私有页面文本"))
        assertFalse(summary.contains("800x600"))
        assertTrue(summary.length < 300)
        assertNull(DiagnosticText.cainiaoSample(JSONArray()))
    }
}

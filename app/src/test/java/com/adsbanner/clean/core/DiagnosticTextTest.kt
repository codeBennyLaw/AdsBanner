package com.adsbanner.clean.core

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
            assertTrue(result.endsWith("详细记录请点“导出”。"))
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
}

package com.adsbanner.clean.core

/** Bounds every dialog/clipboard report, including records written by older diagnostic builds. */
internal object DiagnosticText {
    const val MAX_CHARS = 1600
    const val MAX_UTF8_BYTES = 4096
    private const val SUFFIX = "\n…内容已精简，详细记录请点“导出”。"

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

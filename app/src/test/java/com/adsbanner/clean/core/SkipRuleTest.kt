package com.adsbanner.clean.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SkipRuleTest {
    @Test
    fun recognizesCountdownAndEnglishSkipVariants() {
        listOf(
            "\u8DF3\u8FC7 \u5E7F\u544A 3 \u79D2",
            "\u8DF3\u8FC7\n5s",
            "3s skip ad",
            "skip 5 s"
        ).forEach { assertEquals(it, SkipRule.STRONG, SkipRule.match(it)) }
    }

    @Test
    fun keepsGenericCloseAsWeakMatchAndRejectsUnrelatedSkipText() {
        assertEquals(SkipRule.WEAK, SkipRule.match("\u5173\u95ED"))
        assertEquals(SkipRule.NONE, SkipRule.match("\u8DF3\u8FC7\u6B64\u5267\u60C5"))
    }
}

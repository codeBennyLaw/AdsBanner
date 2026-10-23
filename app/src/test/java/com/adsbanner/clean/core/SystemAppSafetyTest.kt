package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class SystemAppSafetyTest {
    @Test fun protectsVivoAlarmAndSystemUiIncludingPlugin() {
        listOf("com.android.BBKClock", "com.android.systemui", "com.vivo.systemui",
            "com.vivo.systemuiplugin").forEach { assertTrue(it, SystemAppSafety.isProtected(it)) }
    }

    @Test fun protectsAlarmHandlersWithoutAssumingTheirPackageName() {
        assertTrue(SystemAppSafety.isProtected("example.clock", setOf("example.clock")))
        assertFalse(SystemAppSafety.isProtected("example.clock"))
    }

    @Test fun doesNotExemptEveryPreinstalledOrVivoApp() {
        listOf("com.vivo.browser", "com.bbk.appstore", "com.vivo.systemuiplugin.fake",
            "com.taobao.taobao", "com.mihoyo.hyperion").forEach {
            assertFalse(it, SystemAppSafety.isProtected(it))
        }
    }
}

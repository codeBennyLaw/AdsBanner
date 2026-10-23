package com.adsbanner.clean.core

/** Essential system surfaces must never become automatic click/back targets. */
internal object SystemAppSafety {
    private val systemSurfaces = setOf(
        "android", "com.android.systemui", "com.android.settings",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.vivo.systemui", "com.vivo.systemuiplugin"
    )
    private val clocks = setOf(
        "com.android.BBKClock", "com.android.deskclock", "com.android.alarmclock",
        "com.google.android.deskclock"
    )

    fun isProtected(pkg: String, alarmHandlers: Set<String> = emptySet()): Boolean =
        pkg in systemSurfaces || pkg in clocks || pkg in alarmHandlers
}

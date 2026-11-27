package com.adsbanner.clean.core

import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject

internal enum class SplashScanEndReason { USER_INTERACTION, TIMEOUT }

internal interface SplashScanTrace {
    fun root(node: AccessibilityNodeInfo)
    fun missingChild()
    fun decision(allowed: Boolean)
    fun restrictedDecision(allowed: Boolean)
    fun node(node: AccessibilityNodeInfo, depth: Int, normalized: String = "")
    fun finish(): JSONObject
}

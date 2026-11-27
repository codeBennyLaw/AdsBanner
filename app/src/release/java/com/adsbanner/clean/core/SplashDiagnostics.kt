@file:Suppress("UNUSED_PARAMETER")

package com.adsbanner.clean.core

import android.content.Context
import android.graphics.Point
import com.adsbanner.clean.service.InterceptNotifier
import org.json.JSONObject

internal object SplashDiagnostics {
    fun start(context: Context, pkg: String) = Unit
    fun found(pkg: String) = Unit
    fun submitted(pkg: String, scanMs: Long) = Unit
    fun unconfirmed(pkg: String) = Unit
    fun end(pkg: String, reason: SplashScanEndReason?) = Unit
    fun completed(pkg: String, notification: InterceptNotifier.Delivery, confirmMs: Long, notifyMs: Long?) = Unit
    fun sample(pkg: String, report: JSONObject) = Unit
    fun scanState(pkg: String, state: String) = Unit
    fun allowsTrace(pkg: String) = false
    fun newTrace(screen: Point, now: Long, age: Long, activity: String, covered: Boolean, restricted: Boolean): SplashScanTrace? = null
}

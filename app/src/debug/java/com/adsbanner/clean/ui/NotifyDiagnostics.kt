package com.adsbanner.clean.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.adsbanner.clean.BuildConfig
import com.adsbanner.clean.R
import com.adsbanner.clean.core.DiagnosticText
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.SplashDiagnostics
import com.adsbanner.clean.core.StatsStore
import com.adsbanner.clean.service.InterceptNotifier
import com.adsbanner.clean.service.describeInterceptNotifications

internal fun MainActivity.bindNotifyDiagnostics() {
    val row = findViewById<View>(R.id.rowNotify)
    row.setOnClickListener { showNotifyDiagnostics(row) }
}

private fun MainActivity.showNotifyDiagnostics(row: View) {
    val canOpenSettings = InterceptNotifier.canOpenPromotionSettings(this)
    val version = "当前版本：${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）" +
        BuildConfig.DIAGNOSTIC_BUILD.takeIf(String::isNotEmpty)?.let { "\n诊断构建：$it" }.orEmpty()
    val report = DiagnosticText.limit(version + "\n\n" + describeInterceptNotifications(this) + "\n\n" + SplashDiagnostics.describe(this))
    val message = TextView(this).apply {
        id = android.R.id.message
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        text = report + "\n\n点击“复制”即可反馈诊断摘要。测试提示不计入统计。"
        setTextIsSelectable(true)
        setPadding(diagnosticDp(24), 0, diagnosticDp(24), diagnosticDp(12))
    }
    val reportView = object : ScrollView(this) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // Long reports must leave room for native dialog actions at large font sizes.
            val limit = minOf(diagnosticDp(320), (resources.displayMetrics.heightPixels * 0.38f).toInt())
            val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) limit
                else minOf(limit, View.MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        }
    }.apply { addView(message) }
    OriginDialog.Builder(this, row)
        .setTitle("实时通知诊断")
        .setView(reportView)
        .setPositiveButton("测试") { _, _ -> sendTestNotifications() }
        .setNeutralButton("设置") { _, _ ->
            if (canOpenSettings) {
                InterceptNotifier.openPromotionSettings(this)
            } else {
                diagnosticToast("系统没有提供实时通知设置页")
            }
        }
        .setNegativeButton("复制", null)
        .show().apply {
            getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.apply {
                contentDescription = "复制简短诊断摘要"
                setOnClickListener {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    if (clipboard == null) diagnosticToast("无法访问剪贴板") else {
                        clipboard.setPrimaryClip(ClipData.newPlainText("AdsBanner 诊断摘要", DiagnosticText.limit(report)))
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) diagnosticToast("诊断已复制")
                    }
                }
            }
            getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.contentDescription = "发送 2 条独立拦截提示"
            getButton(android.content.DialogInterface.BUTTON_NEUTRAL)?.contentDescription = "打开系统实时通知设置"
        }
}

private fun MainActivity.sendTestNotifications() {
    if (!Prefs.interceptNotify) {
        diagnosticToast("先打开右侧开关")
        return
    }
    listOf(StatsStore.Kind.SPLASH, StatsStore.Kind.JUMP).forEachIndexed { index, kind ->
        InterceptNotifier.notify(this, "示例应用 ${index + 1}", kind)
    }
    diagnosticToast("已发送 2 条独立的 10 秒测试提示")
}

private fun MainActivity.diagnosticDp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()

private fun MainActivity.diagnosticToast(text: String) =
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

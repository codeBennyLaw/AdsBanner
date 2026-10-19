package com.adsbanner.clean.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import com.adsbanner.clean.service.AdSkipAccessibilityService

object AccessibilityHelper {

    private fun component(context: Context) =
        ComponentName(context.applicationContext, AdSkipAccessibilityService::class.java)

    /**
     * 直接读 Settings.Secure，比 AccessibilityManager 更可靠：
     * 后者在服务刚被系统重启、尚未连接时可能返回空列表。
     */
    fun isServiceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        if (TextUtils.isEmpty(enabled)) return false

        val target = component(context)
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabled)
        for (item in splitter) {
            val parsed = ComponentName.unflattenFromString(item) ?: continue
            if (parsed == target) return true
        }
        return false
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}

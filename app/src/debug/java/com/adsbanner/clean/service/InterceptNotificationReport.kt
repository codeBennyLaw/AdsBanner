package com.adsbanner.clean.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * 逐环节列出当前状态，用来确认到底卡在哪一步：
 * 系统版本 -> 接口 -> 授权 -> 通知总开关 -> 实际是否被提升。
 */
internal fun describeInterceptNotifications(context: Context): String {
    val manager = context.getSystemService(NotificationManager::class.java)

    val live = manager?.activeNotifications
        ?.filter {
            it.packageName == context.packageName && it.notification.channelId == InterceptNotifier.CHANNEL_ID
        }
        .orEmpty()

    val promotedFlag = runCatching {
        Notification::class.java.getField("FLAG_PROMOTED_ONGOING").getInt(null)
    }.getOrDefault(0)

    val promoted = live.count { promotedFlag != 0 && (it.notification.flags and promotedFlag) != 0 }
    val sessions = live.sortedBy { it.id }.map { posted ->
        val notification = posted.notification
        val appLabel = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val state = if (promotedFlag != 0 && (notification.flags and promotedFlag) != 0) "已提升" else
            when (InterceptNotifier.LiveUpdate.isEligible(notification)) {
                true -> "符合条件，未提升"
                false -> "不符合提升条件"
                null -> "系统未返回资格判定"
            }
        "$appLabel · $title：$state"
    }

    return listOf(
        "系统：Android ${Build.VERSION.SDK_INT}（API ${Build.VERSION.SDK_INT}）",
        "实时通知接口：" + if (InterceptNotifier.supportsLiveUpdates()) "可用" else "不可用",
        "实时通知授权：" + when {
            !InterceptNotifier.supportsLiveUpdates() -> "不适用"
            InterceptNotifier.canPostPromoted(context) -> "已授权"
            else -> "未授权"
        },
        "通知总开关：" + if (manager?.areNotificationsEnabled() == true) "开启" else "关闭",
        "当前拦截提示：${live.size} 条，已提升 $promoted 条",
        if (sessions.isEmpty()) "当前无正在显示的提示" else sessions.joinToString("\n")
    ).joinToString("\n")
}

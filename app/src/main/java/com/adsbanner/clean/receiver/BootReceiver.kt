package com.adsbanner.clean.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.StatsStore
import com.adsbanner.clean.service.KeepAliveService

/**
 * 开机 / 应用更新后恢复守护。
 *
 * 无障碍服务本身不需要我们拉起：系统开机后会自行重新绑定已启用的无障碍服务。
 * 这里只负责把常驻通知恢复起来。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val matched = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action.contains("QUICKBOOT_POWERON")
        if (!matched) return

        Prefs.init(context)
        StatsStore.init(context)
        Log.i(TAG, "收到 $action，恢复守护")
        KeepAliveService.start(context)
    }

    private companion object {
        const val TAG = "AdsBanner"
    }
}

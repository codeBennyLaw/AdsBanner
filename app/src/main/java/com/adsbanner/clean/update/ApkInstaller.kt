package com.adsbanner.clean.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 把下载好的 APK 交给系统安装器，并处理「安装未知应用」授权。 */
object ApkInstaller {

    /** Android 8 起安装未知来源应用需要用户在系统设置里单独允许 */
    fun hasInstallPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 拉起系统安装器。
     * 返回 false 表示没能启动，通常是没有安装权限或被系统拦下。
     */
    fun launch(context: Context, file: File): Boolean = runCatching {
        if (!file.exists() || file.length() <= 0L) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

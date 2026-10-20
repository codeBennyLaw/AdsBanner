package com.adsbanner.clean.core

/**
 * 记录主界面是否处于前台。
 *
 * Android 10 起后台应用不能直接启动 Activity，所以下载完成后能不能马上弹出安装器，
 * 要先看应用是不是还在前台；不在前台就只能发通知，等用户点通知回来再装。
 */
object AppForeground {

    @Volatile
    var visible: Boolean = false
}

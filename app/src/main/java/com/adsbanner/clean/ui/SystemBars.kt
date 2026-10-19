package com.adsbanner.clean.ui

import android.app.Activity
import android.content.res.Configuration
import androidx.core.view.WindowCompat

/**
 * 让系统栏图标跟随深浅色。
 *
 * targetSdk 35 起状态栏/导航栏强制 edge-to-edge，`android:statusBarColor` 已经失效，
 * 真正决定观感的是状态栏图标的明暗，必须运行时按当前 uiMode 设置。
 */
fun Activity.applySystemBarAppearance() {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
}

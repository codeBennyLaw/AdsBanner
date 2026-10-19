package com.adsbanner.clean.ui

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import androidx.core.view.WindowCompat

/**
 * 让系统栏沉浸：状态栏与导航栏全透明，内容边缘由各自页面的 insets padding 让开。
 *
 * 两件事必须运行时做，光改主题不够：
 *
 * 1. 状态栏 / 导航栏图标的明暗。targetSdk 35 起强制 edge-to-edge，
 *    `android:statusBarColor` 已不生效，真正决定观感的是图标明暗。
 * 2. Android 10+ 会给透明系统栏自动叠一层对比度遮罩（尤其在导航栏上），
 *    不关掉的话底部依然是一条灰黑色，沉浸效果出不来。
 */
fun Activity.applySystemBarAppearance() {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !night
        isAppearanceLightNavigationBars = !night
    }
}

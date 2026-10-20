package com.adsbanner.clean.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * 系统栏沉浸：状态栏与导航栏全透明，内容边缘由各页面的 insets padding 让开。
 *
 * 只改主题里的 statusBarColor 是不够的 —— Material3 主题会在 attach 之后重写窗口颜色，
 * 部分 ROM 也会自己插一层。所以这里按窗口本身设置，并在 post 里再补一遍。
 *
 * 四件事缺一不可：
 * 1. FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS，否则 statusBarColor 不生效
 * 2. statusBarColor / navigationBarColor 设成透明
 * 3. Android 10+ 关闭系统给透明栏自动叠的对比度遮罩，否则导航栏仍是灰条
 * 4. 刘海屏声明 layoutInDisplayCutoutMode，否则状态栏区域会被裁掉一块
 */
fun Activity.applySystemBarAppearance() {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION")
    fun paint() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    paint()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
    }

    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.isAppearanceLightStatusBars = !night
    controller.isAppearanceLightNavigationBars = !night

    // 主题与部分 ROM 会在 view 挂载后重新刷一次系统栏颜色，挂载完成再补一遍
    window.decorView.post {
        paint()
        controller.isAppearanceLightStatusBars = !night
        controller.isAppearanceLightNavigationBars = !night
    }
}

package com.adsbanner.clean.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration

object TabletLayout {
    private const val TABLET_MIN_WIDTH_DP = 600

    fun isTablet(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP

    fun isLandscapeTablet(context: Context): Boolean =
        isTablet(context) &&
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun lockPhoneToPortrait(activity: Activity) {
        if (!isTablet(activity) && activity.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }
}

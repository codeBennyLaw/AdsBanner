package com.adsbanner.clean.ui

import android.content.Context
import android.util.DisplayMetrics
import kotlin.math.min

object TabletLayout {
    private const val TABLET_MIN_WIDTH_DP = 600

    fun isTablet(context: Context): Boolean {
        if (context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP) {
            return true
        }
        val metrics = DisplayMetrics()
        context.display?.getRealMetrics(metrics)
        if (metrics.widthPixels > 0 && metrics.heightPixels > 0 && metrics.density > 0f) {
            return min(metrics.widthPixels, metrics.heightPixels) / metrics.density >= TABLET_MIN_WIDTH_DP
        }
        return context.resources.configuration.smallestScreenWidthDp >= TABLET_MIN_WIDTH_DP
    }

    fun isLandscapeTablet(context: Context): Boolean =
        isTablet(context) && context.display?.let { display ->
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            metrics.widthPixels > metrics.heightPixels
        } == true

}

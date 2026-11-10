package com.adsbanner.clean.core

/** Only application-owned nodes obscured by a thin top system bar qualify for native actions. */
internal object SystemBarTargetPolicy {
    fun overlapsStatusBar(target: SkipTargetPolicy.Bounds, app: SkipTargetPolicy.Bounds,
        bar: SkipTargetPolicy.Bounds): Boolean = app.width > 0 && app.height > 0 && app.contains(target) &&
        target.width > 0 && target.height > 0 && bar.width >= app.width * 0.75f &&
        bar.height > 0 && bar.height <= app.height * 0.12f && bar.top <= app.top && bar.bottom > app.top &&
        bar.left < target.right && target.left < bar.right && bar.top < target.bottom && target.top < bar.bottom
}

package com.adsbanner.clean

import android.app.Application
import com.adsbanner.clean.core.Prefs
import com.adsbanner.clean.core.StatsStore
import com.adsbanner.clean.update.ReleaseUpdates

class AdsBannerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        StatsStore.init(this)
        ReleaseUpdates.init(this)
    }
}

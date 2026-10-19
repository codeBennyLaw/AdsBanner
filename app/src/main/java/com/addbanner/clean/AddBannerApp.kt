package com.addbanner.clean

import android.app.Application
import com.addbanner.clean.core.Prefs
import com.addbanner.clean.core.StatsStore

class AddBannerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        StatsStore.init(this)
    }
}

package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class VerifiedActivityWindowsTest {
    @Test fun aLateSplashEventCannotReplaceTheVerifiedDestinationWindow() {
        val windows = VerifiedActivityWindows()
        windows.remember("app", 20, "app.Splash")
        windows.remember("app", 21, "app.Main")
        windows.remember("app", 20, "app.Splash")
        assertEquals("app.Main", windows.activity("app", 21))
        assertEquals("app.Splash", windows.activity("app", 20))
    }

    @Test fun unverifiedDialogsAndOtherPackagesCannotBorrowAnActivity() {
        val windows = VerifiedActivityWindows()
        windows.remember("app", 20, "app.Splash")
        assertNull(windows.activity("app", 21))
        assertNull(windows.activity("other", 20))
        windows.remember("app", -1, "app.Main")
        assertNull(windows.activity("app", -1))
        for (id in 21..52) windows.remember("app", id, "app.Main")
        assertNull(windows.activity("app", 20))
    }
}

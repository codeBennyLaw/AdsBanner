package com.adsbanner.clean.core

/** Window ownership survives late events from another already verified window. */
internal class VerifiedActivityWindows {
    private data class Owner(val pkg: String, val activity: String)
    private val owners = linkedMapOf<Int, Owner>()

    fun remember(pkg: String, windowId: Int, activity: String) {
        if (windowId < 0 || pkg.isEmpty() || activity.isEmpty()) return
        owners[windowId] = Owner(pkg, activity)
        if (owners.size > 32) owners.remove(owners.keys.first())
    }

    fun activity(pkg: String, windowId: Int): String? = owners[windowId]?.takeIf { it.pkg == pkg }?.activity
}

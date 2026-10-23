package com.adsbanner.clean.core

/** Count a close only after the same mini app stays visible and its ad evidence disappears. */
internal class MiniAppAdSession {
    data class Action(val target: MiniAppAdRule.Target, val gesture: Boolean)
    data class Step(val action: Action? = null, val record: MiniAppSource? = null, val urgent: Boolean = false)
    private var source: MiniAppSource? = null
    var panelKey: String? = null
        private set
    private var tries = 0
    private var accepted = false
    private var acceptedAt: Long? = null
    private var lastActionAt = 0L
    private var absentAt: Long? = null
    private var inFlight: Action? = null

    fun reset() {
        source = null
        panelKey = null
        tries = 0
        accepted = false
        acceptedAt = null
        lastActionAt = 0
        absentAt = null
        inFlight = null
    }

    fun update(match: MiniAppAdRule.Match?, now: Long): Step {
        if (match == null) { reset(); return Step() }
        if (source?.key != match.source.key) {
            // Full-page ads may hide the native title. Resolve it when the accepted close reveals the same host's page.
            val resolvingName = source?.hostPackage == match.source.hostPackage && source?.name == null &&
                match.source.name != null && panelKey != null && match.panelKey == null && accepted && inFlight == null &&
                acceptedAt?.let { now - it in 0..2_500L } == true
            if (!resolvingName) reset()
            source = match.source
        }
        if (panelKey == null && match.panelKey != null) panelKey = match.panelKey
        if (panelKey == null) return Step()
        if (match.panelKey == null) {
            val since = absentAt ?: now.also { absentAt = it }
            if (now - since >= 350L && now - lastActionAt >= 350L && inFlight == null) {
                val result = if (accepted && acceptedAt?.let { since - it in 0..2_500L } == true) source else null
                reset()
                return Step(record = result)
            }
            return Step(urgent = true)
        }
        absentAt = null
        if (match.panelKey != panelKey) {
            reset()
            source = match.source
            panelKey = match.panelKey
        }
        val target = match.target
        // Some SDKs expose an enabled skip before they accept input; leave more time for the last retry.
        val retryAfter = if (tries <= 1) 400L else 800L
        if (target != null && tries < 3 && inFlight == null && (tries == 0 || now - lastActionAt >= retryAfter)) {
            val action = Action(target, target.action == null || tries > 0)
            tries++
            lastActionAt = now
            inFlight = action
            return Step(action = action, urgent = true)
        }
        return Step(urgent = inFlight != null || (target != null && tries < 3))
    }

    fun complete(action: Action, successful: Boolean) {
        if (inFlight !== action) return
        inFlight = null
        accepted = accepted || successful
        if (successful) acceptedAt = lastActionAt
    }
}

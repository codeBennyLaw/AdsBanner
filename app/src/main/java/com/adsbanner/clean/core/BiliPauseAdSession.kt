package com.adsbanner.clean.core

/** 一次暂停广告的重试、结果确认和计数；使用单调时钟，不按按钮坐标冷却。 */
internal class BiliPauseAdSession {
    data class Action(val target: BiliPauseAdRule.Target, val gesture: Boolean)
    data class Step(val action: Action? = null, val record: Boolean = false, val urgent: Boolean = false)

    private class Episode {
        var hintAt: Long? = null
        var panelSeen = false
        var cancelAccepted = false
        var closeAccepted = false
        var absentAt: Long? = null
        var lastActionAt = 0L
        val tries = mutableMapOf<BiliPauseAdRule.Kind, Int>()
    }

    private var playerKey: String? = null
    private var episode: Episode? = null
    private var inFlight: Action? = null
    var panelKey: String? = null
        private set

    fun reset() {
        playerKey = null
        episode = null
        inFlight = null
        panelKey = null
    }

    fun update(match: BiliPauseAdRule.Match?, now: Long): Step {
        if (match == null) {
            reset()
            return Step()
        }
        if (playerKey != match.playerKey) {
            reset()
            playerKey = match.playerKey
        }
        val present = match.hintKey != null || match.panelKey != null
        if (episode == null && present) episode = Episode()
        val current = episode ?: return Step()
        if (match.hintKey != null && current.hintAt == null) current.hintAt = now
        if (match.panelKey != null) {
            current.panelSeen = true
            panelKey = match.panelKey
        }
        if (present) {
            current.absentAt = null
        } else {
            val absentAt = current.absentAt ?: now.also { current.absentAt = it }
            // 连续两次以上观察到消失；取消还要等原来的广告展示窗口过去。
            val settled = now - absentAt >= 350L && now - current.lastActionAt >= 350L
            val verified = if (current.panelSeen) current.closeAccepted else
                current.cancelAccepted && now - (current.hintAt ?: now) >= 3_500L
            if (settled && verified && inFlight == null) {
                episode = null
                panelKey = null
                return Step(record = true)
            }
            if (settled && !verified && (current.panelSeen || !current.cancelAccepted) && inFlight == null) {
                episode = null
                panelKey = null
                return Step()
            }
        }

        // 广告已经弹出时，关闭优先；没有点过取消也能独立进入这一分支。
        val target = match.close ?: match.cancel
        val tries = target?.let { current.tries[it.kind] ?: 0 } ?: 0
        if (target != null && inFlight == null && tries < 3 &&
            (current.tries.isEmpty() || now - current.lastActionAt >= 350L)) {
            current.tries[target.kind] = tries + 1
            current.lastActionAt = now
            val action = Action(target, gesture = target.action == null || tries > 0)
            inFlight = action
            return Step(action = action, urgent = true)
        }
        val urgent = inFlight != null || (target != null && tries < 3) ||
            (current.hintAt?.let { now - it < 4_500L } == true) ||
            (!present && (current.cancelAccepted || current.closeAccepted))
        return Step(urgent = urgent)
    }

    fun complete(action: Action, accepted: Boolean) {
        if (inFlight !== action) return
        inFlight = null
        val current = episode ?: return
        if (accepted) {
            when (action.target.kind) {
                BiliPauseAdRule.Kind.CANCEL -> current.cancelAccepted = true
                BiliPauseAdRule.Kind.CLOSE -> current.closeAccepted = true
            }
        }
    }
}

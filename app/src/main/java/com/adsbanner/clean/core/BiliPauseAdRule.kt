package com.adsbanner.clean.core

/** B 站半屏播放页规则。只在播放器和广告面板各自的子树内关联控件。 */
internal object BiliPauseAdRule {
    const val PACKAGE = "tv.danmaku.bili"

    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val centerX get() = (left + right) / 2
        val centerY get() = (top + bottom) / 2
        fun contains(other: Bounds) = other.width > 0 && other.height > 0 &&
            other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom
    }

    data class Node(
        val parent: Int,
        val key: String,
        val id: String,
        val text: String,
        val desc: String,
        val visible: Boolean,
        val enabled: Boolean,
        val clickable: Boolean,
        val bounds: Bounds
    ) {
        val shortId get() = id.substringAfterLast('/')
        fun hasLabel(test: (String) -> Boolean) = test(text) || test(desc)
    }

    enum class Kind { CANCEL, CLOSE }
    data class Target(val kind: Kind, val key: String, val icon: Int, val action: Int?)
    data class Match(
        val playerKey: String,
        val hintKey: String?,
        val panelKey: String?,
        val cancel: Target?,
        val close: Target?
    )

    fun match(nodes: List<Node>, screen: Bounds, knownPanelKey: String? = null): Match? {
        if (screen.width <= 0 || screen.height <= screen.width) return null
        fun shown(i: Int) = nodes[i].visible && screen.contains(nodes[i].bounds)
        fun inside(i: Int, ancestor: Int): Boolean {
            var p = i
            while (p >= 0) {
                if (p == ancestor) return true
                p = nodes[p].parent
            }
            return false
        }
        fun ancestors(i: Int): Sequence<Int> = generateSequence(nodes[i].parent.takeIf { it >= 0 }) {
            nodes[it].parent.takeIf { parent -> parent >= 0 }
        }

        // 不依赖 Activity 名：同一播放页有多个版本，且全屏也可能沿用同一个 Activity。
        val player = nodes.indices.firstOrNull { i ->
            val n = nodes[i]
            n.shortId in PLAYER_IDS && n.bounds.width >= screen.width * 0.55 &&
                n.bounds.height in (screen.height / 12)..(screen.height * 0.65).toInt() &&
                n.bounds.top < screen.top + screen.height * 0.3 &&
                screen.contains(n.bounds) && (n.visible || nodes.indices.any { shown(it) && inside(it, i) })
        } ?: return null
        val playerBounds = nodes[player].bounds

        fun small(i: Int) = shown(i) && nodes[i].enabled && nodes[i].bounds.width <= screen.width * 0.22 &&
            nodes[i].bounds.height <= screen.height * 0.07

        // 只接受目标自身或单独包裹图标的点击区域，不能借用同时包含「更多」的父容器。
        fun actionFor(icon: Int, scope: Int): Int? = (sequenceOf(icon) + ancestors(icon).take(4))
            .takeWhile { it != scope }
            .firstOrNull { i ->
                val n = nodes[i]
                small(i) && n.clickable && n.bounds.contains(nodes[icon].bounds) &&
                    nodes.indices.none { other ->
                        other != i && other != icon && shown(other) && inside(other, i) &&
                            !inside(icon, other) && (isMenu(nodes[other]) ||
                            (nodes[other].clickable && !inside(other, icon)))
                    }
            }

        val hints = nodes.indices.filter { i ->
            shown(i) && inside(i, player) && playerBounds.contains(nodes[i].bounds) &&
                nodes[i].hasLabel { it.length <= 40 && HINT.containsMatchIn(it) }
        }
        var cancel: Target? = null
        for (hint in hints) {
            val hb = nodes[hint].bounds
            val scopes = ancestors(hint).takeWhile { it != player }.take(5)
            for (scope in scopes) {
                val sb = nodes[scope].bounds
                if (sb.width > playerBounds.width * 0.85 || sb.height > playerBounds.height * 0.5) break
                val target = nodes.indices.firstOrNull { i ->
                    val b = nodes[i].bounds
                    small(i) && inside(i, scope) && playerBounds.contains(b) &&
                        nodes[i].hasLabel { it in CANCEL_LABELS } &&
                        b.left >= hb.right - screen.width / 100 &&
                        b.top < hb.bottom && b.bottom > hb.top &&
                        b.left - hb.right < playerBounds.width * 0.3
                } ?: continue
                cancel = Target(Kind.CANCEL, nodes[hint].key, target, actionFor(target, scope))
                break
            }
            if (cancel != null) break
        }

        // 点击后关闭图标可能先消失，广告内容仍在；不能仅凭图标消失就统计成功。
        var panelKey: String? = nodes.indices.firstOrNull { p ->
            nodes[p].key == knownPanelKey && nodes.indices.any { ad ->
                shown(ad) && inside(ad, p) && nodes[p].bounds.contains(nodes[ad].bounds) &&
                    nodes[ad].hasLabel { it in AD_LABELS || it in AD_ACTIONS }
            }
        }?.let { nodes[it].key }
        var close: Target? = null
        for (icon in nodes.indices) {
            if (!small(icon) || isMenu(nodes[icon]) || !nodes[icon].hasLabel { it.lowercase() in CLOSE_LABELS }) continue
            val chain = ancestors(icon).toList()
            val pager = chain.firstOrNull { nodes[it].shortId == "fl_tab_pager_container" } ?: continue
            // 普通弹窗/分集/评论页也可能有 close，必须有同一面板内的广告证据。
            val panel = chain.takeWhile { it != pager }.firstOrNull { p ->
                val b = nodes[p].bounds
                val cb = nodes[icon].bounds
                b.width >= screen.width * 0.55 && b.height >= screen.height * 0.12 &&
                    b.top >= playerBounds.bottom - screen.height * 0.03 &&
                    b.top <= playerBounds.bottom + screen.height * 0.15 &&
                    cb.centerX > b.left + b.width * 0.82 &&
                    cb.top >= b.top && cb.bottom <= b.top + screen.height * 0.12 &&
                    nodes.indices.any { ad ->
                        shown(ad) && inside(ad, p) && b.contains(nodes[ad].bounds) &&
                            nodes[ad].bounds.top >= cb.top &&
                            nodes[ad].hasLabel { it in AD_LABELS || it in AD_ACTIONS }
                    }
            } ?: continue
            panelKey = nodes[panel].key
            close = Target(Kind.CLOSE, panelKey, icon, actionFor(icon, panel))
            break
        }
        return Match(nodes[player].key, hints.firstOrNull()?.let { nodes[it].key }, panelKey, cancel, close)
    }

    private fun isMenu(n: Node) = n.shortId in MENU_IDS || n.hasLabel { it.lowercase() in MENU_LABELS }

    private val PLAYER_IDS = setOf("video_container", "video_area")
    private val MENU_IDS = setOf("more", "more_layout", "menu", "feedback")
    private val MENU_LABELS = setOf("更多", "反馈", "不感兴趣", "more", "menu", "⋮", "…", "...")
    private val HINT = Regex("后将展示广告|即将展示广告|后将播放广告|即将播放广告")
    private val CANCEL_LABELS = setOf("取消", "取消广告")
    private val CLOSE_LABELS = setOf("关闭", "关闭广告", "×", "✕", "✖", "╳", "x", "close", "dismiss")
    private val AD_LABELS = setOf("广告", "廣告", "赞助", "推广")
    private val AD_ACTIONS = setOf("前往观看", "立即了解", "立即下载", "查看详情")
}

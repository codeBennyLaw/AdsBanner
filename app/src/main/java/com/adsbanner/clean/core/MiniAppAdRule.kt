package com.adsbanner.clean.core

/** Host chrome identifies the mini app; ad evidence and its close control must share a local panel. */
internal object MiniAppAdRule {
    data class Bounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        val x get() = (left + right) / 2
        val y get() = (top + bottom) / 2
        fun contains(b: Bounds) = b.width > 0 && b.height > 0 && b.left >= left && b.top >= top && b.right <= right && b.bottom <= bottom
    }
    data class Node(val parent: Int, val key: String, val id: String, val text: String, val desc: String,
        val className: String, val visible: Boolean, val enabled: Boolean, val clickable: Boolean, val bounds: Bounds) {
        val shortId get() = id.substringAfterLast('/').lowercase()
        fun labels(test: (String) -> Boolean) = test(text.trim()) || test(desc.trim())
    }
    data class Target(val icon: Int, val action: Int?, val bounds: Bounds, val label: String)
    data class Match(val source: MiniAppSource, val panelKey: String?, val target: Target?, val anyAd: Boolean)

    fun isContainerActivity(pkg: String, activity: String): Boolean = when (pkg) {
        "com.tencent.mm" -> activity.startsWith("com.tencent.mm.plugin.appbrand.ui.AppBrandUI") ||
            activity == "com.tencent.mm.plugin.appbrand.launching.AppBrandLaunchProxyUI"
        "com.tencent.mobileqq" -> activity.startsWith("com.tencent.mobileqq.mini.appbrand.ui.AppBrandUI") ||
            activity == "com.tencent.mobileqq.activity.miniaio.MiniChatActivity" ||
            activity.startsWith("com.tencent.qqmini.sdk.ui.MiniActivity")
        "com.eg.android.AlipayGphone" -> activity.startsWith("com.alipay.mobile.nebulax.") ||
            activity.startsWith("com.alipay.mobile.nebula.activity.H5")
        else -> false
    }

    fun match(pkg: String, activity: String, nodes: List<Node>, screen: Bounds, knownPanelKey: String? = null,
        retainedSource: MiniAppSource? = null): Match? {
        if (!MiniAppSource.isHost(pkg) || screen.width <= 0 || screen.height <= 0 || nodes.isEmpty()) return null
        fun inside(index: Int, ancestor: Int): Boolean {
            var p = index
            repeat(48) {
                if (p == ancestor) return true
                if (p !in nodes.indices) return false
                p = nodes[p].parent
            }
            return false
        }
        fun ancestors(index: Int) = generateSequence(nodes[index].parent.takeIf { it in nodes.indices }) {
            nodes[it].parent.takeIf { p -> p in nodes.indices && p < it }
        }.take(40)
        fun shown(index: Int) = nodes[index].visible && screen.contains(nodes[index].bounds)
        fun native(index: Int) = !nodes[index].className.contains("WebView") &&
            ancestors(index).none { nodes[it].className.contains("WebView") }
        fun atTop(index: Int) = shown(index) && nodes[index].bounds.y < screen.top + screen.height * .19
        fun small(index: Int) = shown(index) && nodes[index].bounds.width <= screen.width * .6 &&
            nodes[index].bounds.height <= screen.height * .15

        val menu = nodes.indices.filter { i -> atTop(i) && native(i) && nodes[i].bounds.x > screen.left + screen.width * .5 &&
            nodes[i].labels { it in MENU_LABELS } }
        val exits = nodes.indices.filter { i -> atTop(i) && native(i) && small(i) &&
            (nodes[i].labels { it in MINI_CLOSE_LABELS } ||
                nodes[i].shortId in SHELL_CLOSE_IDS ||
                (nodes[i].labels { it in PLAIN_CLOSE_LABELS } && menu.any {
                    kotlin.math.abs(nodes[it].bounds.y - nodes[i].bounds.y) < screen.height * .04 &&
                        kotlin.math.abs(nodes[it].bounds.x - nodes[i].bounds.x) < screen.width * .3
                })) }
        val headers = exits.flatMap { exit -> ancestors(exit).filter {
            val b = nodes[it].bounds
            native(it) && b.width > screen.width * .5 && b.height < screen.height * .2 && b.top < screen.top + screen.height * .15
        }.take(1).toList() }.toSet()
        val namedHeaders = nodes.indices.filter { i -> atTop(i) && native(i) &&
            (nodes[i].shortId in TITLE_IDS || nodes[i].shortId.matches(TITLE_ID)) }
        val explicitShell = exits.any { i -> nodes[i].labels { it.contains("小程序") } }
        val knownActivity = isContainerActivity(pkg, activity)
        val strongActivity = knownActivity && pkg != "com.eg.android.AlipayGphone"
        if (!strongActivity && !(exits.isNotEmpty() && ((explicitShell && menu.isNotEmpty()) || (knownActivity && namedHeaders.isNotEmpty()))) &&
            !(knownActivity && retainedSource?.hostPackage == pkg)) return null
        fun shell(index: Int) = index in exits || index in menu || headers.any { inside(index, it) } ||
            nodes[index].shortId in SHELL_CLOSE_IDS || nodes[index].shortId.contains("appbrand_actionbar")
        val nameNodes = namedHeaders + nodes.indices.filter { i ->
            shown(i) && native(i) && !shell(i) && nodes[i].className.endsWith("TextView") &&
                exits.any { exit ->
                    kotlin.math.abs(nodes[i].bounds.y - nodes[exit].bounds.y) < screen.height * .035 &&
                        nodes[i].bounds.right <= nodes[exit].bounds.left
                }
        } + headers.flatMap { h -> nodes.indices.filter { i ->
            i != h && inside(i, h) && native(i) && nodes[i].className.endsWith("TextView") && i !in menu && i !in exits
        } }
        val name = nameNodes.asSequence().mapNotNull { i ->
            nodes[i].text.trim().ifEmpty { nodes[i].desc.trim() }.takeIf {
                it.isNotBlank() && MiniAppSource.create(pkg, it)?.name != null
            }
        }.firstOrNull()
        val source = MiniAppSource.create(pkg, name ?: retainedSource?.name) ?: return null
        val markers = nodes.indices.filter { i -> shown(i) && !shell(i) && nodes[i].labels(::adLabel) }.take(24)
        val adButtons = nodes.indices.filter { i -> small(i) && !shell(i) && nodes[i].labels(::explicitClose) }
        val anyAd = markers.isNotEmpty() || adButtons.isNotEmpty()
        val known = nodes.indices.firstOrNull { nodes[it].key == knownPanelKey && shown(it) }
        var panelKey = known?.takeIf { scope -> markers.any { inside(it, scope) } || adButtons.any { inside(it, scope) } }?.let { nodes[it].key }
        // If a panel's path changed during a render, remaining ad evidence prevents a false success.
        if (knownPanelKey != null && known == null && anyAd) panelKey = knownPanelKey

        for (icon in nodes.indices.filter { i -> small(i) && nodes[i].enabled && !shell(i) &&
            (nodes[i].labels(::closeLabel) || nodes[i].shortId.matches(CLOSE_ID)) }.take(24)) {
            val button = nodes[icon]
            val explicit = button.labels(::explicitClose)
            val skip = button.labels { it.contains("跳过") || it.startsWith("skip", true) }
            val scope = ancestors(icon).take(12).firstOrNull { p ->
                val parent = nodes[p]
                val b = parent.bounds
                parent.parent >= 0 && !parent.className.contains("WebView") && parent.id != "android:id/content" &&
                    headers.none { inside(it, p) } && b.contains(button.bounds) &&
                    (explicit || markers.any { inside(it, p) && b.contains(nodes[it].bounds) }) &&
                    (skip || explicit || b.height <= screen.height * .85 || parent.shortId.contains("ad_container")) &&
                    (skip || explicit || (button.bounds.x > b.left + b.width * .65 && button.bounds.y < b.top + b.height * .3) ||
                        button.bounds.y > b.top + b.height * .8) &&
                    nodes.indices.none { i -> shown(i) && inside(i, p) &&
                        (nodes[i].className.endsWith("EditText") || nodes[i].labels {
                            it.contains(PROTECTED_DIALOG)
                        }) }
            } ?: continue
            val selfArea = button.bounds.width.toLong() * button.bounds.height
            val action = (sequenceOf(icon) + ancestors(icon).takeWhile { it != scope }.take(3)).firstOrNull { i ->
                val n = nodes[i]
                n.enabled && n.clickable && shown(i) && !shell(i) && n.bounds.contains(button.bounds) &&
                    n.bounds.width.toLong() * n.bounds.height <= selfArea * 2
            }
            return Match(source, nodes[scope].key, Target(icon, action, button.bounds,
                button.text.ifBlank { button.desc }.ifBlank { "关闭广告" }), anyAd)
        }
        return Match(source, panelKey, null, anyAd)
    }

    private fun adLabel(text: String) = text in AD_LABELS
    private fun explicitClose(text: String) = EXPLICIT_CLOSE.matches(text)
    private fun closeLabel(text: String) = explicitClose(text) || CLOSE_LABEL.matches(text)

    private val AD_LABELS = setOf("广告", "廣告", "AD", "Ad", "ad", "全屏广告", "插屏广告", "推荐广告")
    private val MENU_LABELS = setOf("更多", "更多选项", "菜单", "more", "…", "⋮")
    private val MINI_CLOSE_LABELS = setOf("关闭小程序", "关闭当前小程序", "退出小程序")
    private val PLAIN_CLOSE_LABELS = setOf("关闭", "×", "x", "close")
    private val TITLE_ID = Regex("(?:appbrand|miniapp|mini_app).*title(?:_text)?")
    private val CLOSE_ID = Regex("(?:iv_|btn_)?(?:ad_)?(?:close|skip)(?:_btn|_ad|_button)?")
    private val PROTECTED_DIALOG = Regex("支付密码|验证码|确认付款|授权登录|是否退出小程序|放弃奖励")
    private val EXPLICIT_CLOSE = Regex("^(?:关闭(?:开屏)?广告|跳过\\s*广告(?:\\s*\\d{1,2}\\s*[sS秒]?)?|skip\\s+ad|close\\s+ad)$", RegexOption.IGNORE_CASE)
    private val CLOSE_LABEL = Regex("^(?:关闭|[xX×✕✖╳]|close|跳过(?:\\s*[|丨/·•]?\\s*\\d{1,2}\\s*[sS秒]?)?|\\d{1,2}\\s*[sS秒]\\s*跳过|skip(?:\\s*\\d{1,2}\\s*s)?)$", RegexOption.IGNORE_CASE)

    private val TITLE_IDS = setOf("h5_tv_title", "h5_nav_title", "mini_app_title", "miniapp_title", "appbrand_title", "app_brand_title", "mini_title")
    private val SHELL_CLOSE_IDS = setOf("h5_nav_close", "h5_nav_back", "h5_nav_back_to_home", "appbrand_actionbar_close", "miniapp_close", "mini_app_close", "mini_close")
}

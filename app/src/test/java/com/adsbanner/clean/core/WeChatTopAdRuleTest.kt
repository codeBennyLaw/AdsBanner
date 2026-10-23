package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

/** Variants of the reported left ad/skip bar, separate from the right mini-app capsule. */
class WeChatTopAdRuleTest {
    private val pkg = "com.tencent.mm"
    private val activity = "$pkg.plugin.appbrand.ui.AppBrandUI01"
    private val screen = MiniAppAdRule.Bounds(0, 0, 1080, 2400)
    private fun node(parent: Int, key: String, text: String = "", desc: String = "", id: String = "",
        bounds: MiniAppAdRule.Bounds = screen, clickable: Boolean = false, cls: String = "android.widget.FrameLayout") =
        MiniAppAdRule.Node(parent, key, id, text, desc, cls, true, true, clickable, bounds)
    private fun fixture(): MutableList<MiniAppAdRule.Node> = mutableListOf(
        node(-1, "root"),
        node(0, "header", bounds = MiniAppAdRule.Bounds(0, 80, 1080, 220)),
        node(1, "title", id = "$pkg:id/h5_tv_title", bounds = MiniAppAdRule.Bounds(30, 90, 600, 150), cls = "android.widget.TextView"),
        node(1, "menu", desc = "更多", bounds = MiniAppAdRule.Bounds(820, 100, 900, 180), clickable = true),
        node(1, "exit", desc = "关闭小程序", bounds = MiniAppAdRule.Bounds(930, 100, 1010, 180), clickable = true),
        node(1, "ad-bar", bounds = MiniAppAdRule.Bounds(20, 100, 310, 180)),
        node(5, "ad-label", text = "广告", bounds = MiniAppAdRule.Bounds(30, 110, 140, 170), cls = "android.widget.TextView"),
        node(5, "skip-parent", bounds = MiniAppAdRule.Bounds(150, 100, 300, 180), clickable = true),
        node(7, "skip", text = "跳过", bounds = MiniAppAdRule.Bounds(170, 110, 280, 170), cls = "android.widget.TextView"),
        node(1, "countdown", text = "4 秒", bounds = MiniAppAdRule.Bounds(330, 110, 430, 170), cls = "android.widget.TextView"),
        node(0, "creative", bounds = MiniAppAdRule.Bounds(0, 220, 1080, 2200), cls = "android.webkit.WebView")
    )
    private fun match(nodes: List<MiniAppAdRule.Node>, known: String? = null) =
        MiniAppAdRule.match(pkg, activity, nodes, screen, known)

    @Test fun leftAdBarIsNotPartOfTheProtectedRightNavigationCapsule() {
        val result = match(fixture())!!
        assertEquals(8, result.target!!.icon)
        assertNull(result.source.name)
        assertTrue(result.target!!.icon !in listOf(3, 4))
    }
    @Test fun matchesSeparateControlsWhoseOnlyCommonParentIsTheFullHeader() {
        val nodes = fixture()
        nodes[6] = nodes[6].copy(parent = 1)
        nodes[8] = nodes[8].copy(parent = 1, clickable = true)
        assertEquals(8, match(nodes)!!.target!!.action)
    }
    @Test fun virtualWebViewNodesStillNeedAnAdjacentAdLabelAndAnExactSkipButton() {
        val nodes = fixture()
        nodes[10] = nodes[10].copy(bounds = screen)
        nodes[6] = nodes[6].copy(parent = 10)
        nodes[8] = nodes[8].copy(parent = 10, clickable = true)
        assertEquals(8, match(nodes)!!.target!!.icon)
        nodes[6] = nodes[6].copy(text = "广告设置")
        assertNull(match(nodes)!!.target)
    }
    @Test fun aMissingSkipButtonNeverFallsBackToExitingTheMiniApp() {
        val nodes = fixture()
        val key = match(nodes)!!.panelKey!!
        nodes[8] = nodes[8].copy(visible = false)
        nodes[7] = nodes[7].copy(visible = false)
        val result = match(nodes, key)!!
        assertNull(result.target)
        assertEquals(key, result.panelKey)
    }
    @Test fun unrelatedBannerOrCountdownDoesNotAuthorizeSkippingANormalPage() {
        val nodes = fixture()
        nodes[6] = nodes[6].copy(bounds = MiniAppAdRule.Bounds(30, 1900, 140, 1970), parent = 10)
        assertNull(match(nodes)!!.target)
        nodes[6] = nodes[6].copy(text = "4 秒")
        assertNull(match(nodes)!!.target)
    }
    @Test fun ordinaryChatsCannotAcquireTheTopAdRuleWithoutMiniAppChrome() {
        val nodes = fixture()
        nodes[3] = nodes[3].copy(visible = false)
        nodes[4] = nodes[4].copy(visible = false)
        assertNull(MiniAppAdRule.match(pkg, "$pkg.ui.LauncherUI", nodes, screen))
    }
    @Test fun adCountdownAndCampaignTextAreNotMiniAppNames() {
        val nodes = fixture()
        nodes.add(node(1, "campaign", text = "瑞幸即享", bounds = MiniAppAdRule.Bounds(430, 110, 700, 170), cls = "android.widget.TextView"))
        assertNull(match(nodes)!!.source.name)
        assertNull(MiniAppSource.create(pkg, "4 秒")!!.name)
    }
    @Test fun countdownChangesDoNotCreateDifferentMiniAppIdentities() {
        val nodes = fixture()
        val first = match(nodes)!!
        nodes[9] = nodes[9].copy(text = "3 秒")
        assertEquals(first.source.key, match(nodes)!!.source.key)
        nodes[6] = nodes[6].copy(text = "广告⌄")
        assertNotNull(match(nodes)!!.target)
        nodes[8] = nodes[8].copy(text = "不跳过")
        assertNull(match(nodes)!!.target)
    }
    @Test fun formControlsInsideTheAdBarRemainProtected() {
        val nodes = fixture()
        nodes.add(node(5, "verification", text = "验证码", bounds = MiniAppAdRule.Bounds(20, 110, 300, 170), cls = "android.widget.EditText"))
        assertNull(match(nodes)!!.target)
    }
    @Test fun unrelatedAdsOnTheReturnedPageDoNotKeepTheOldTopBarAlive() {
        val nodes = fixture()
        val key = match(nodes)!!.panelKey!!
        for (i in 5..9) nodes[i] = nodes[i].copy(visible = false)
        nodes.add(node(10, "other-banner", text = "广告", bounds = MiniAppAdRule.Bounds(30, 1800, 200, 1880)))
        assertNull(match(nodes, key)!!.panelKey)
    }
}

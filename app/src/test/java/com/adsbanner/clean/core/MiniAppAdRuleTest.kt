package com.adsbanner.clean.core

import org.junit.Assert.*
import org.junit.Test

class MiniAppAdRuleTest {
    private val screen = MiniAppAdRule.Bounds(0, 0, 1080, 2400)
    private val wx = "com.tencent.mm"
    private fun activity(pkg: String) = when (pkg) {
        wx -> "com.tencent.mm.plugin.appbrand.ui.AppBrandUI01"
        "com.tencent.mobileqq" -> "com.tencent.mobileqq.mini.appbrand.ui.AppBrandUI"
        else -> "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity"
    }
    private fun node(parent: Int, key: String, id: String = "", text: String = "", desc: String = "",
        bounds: MiniAppAdRule.Bounds = screen, clickable: Boolean = false, cls: String = "android.widget.FrameLayout") =
        MiniAppAdRule.Node(parent, key, id, text, desc, cls, true, true, clickable, bounds)
    private fun fixture(pkg: String = wx): List<MiniAppAdRule.Node> = listOf(
        node(-1, "root"),
        node(0, "header", bounds = MiniAppAdRule.Bounds(0, 80, 1080, 200)),
        node(1, "title", "$pkg:id/h5_tv_title", "铁路出行", bounds = MiniAppAdRule.Bounds(60, 100, 700, 180), cls = "android.widget.TextView"),
        node(1, "menu", text = "更多", bounds = MiniAppAdRule.Bounds(820, 100, 900, 180), clickable = true),
        node(1, "exit", "$pkg:id/miniapp_close", text = "关闭", desc = "关闭小程序", bounds = MiniAppAdRule.Bounds(930, 100, 1010, 180), clickable = true),
        node(0, "page", bounds = MiniAppAdRule.Bounds(0, 200, 1080, 2300), cls = "android.webkit.WebView"),
        node(5, "ad", "$pkg:id/ad_container", bounds = MiniAppAdRule.Bounds(80, 600, 1000, 1600)),
        node(6, "marker", text = "广告", bounds = MiniAppAdRule.Bounds(100, 640, 180, 700), cls = "android.widget.TextView"),
        node(6, "close", text = "×", bounds = MiniAppAdRule.Bounds(900, 640, 970, 710), clickable = true)
    )
    private fun match(nodes: List<MiniAppAdRule.Node>, pkg: String = wx, known: String? = null) =
        MiniAppAdRule.match(pkg, activity(pkg), nodes, screen, known)

    @Test fun identifiesAllThreeHostsAndKeepsTheMiniAppName() {
        listOf(wx, "com.tencent.mobileqq", "com.eg.android.AlipayGphone").forEach { pkg ->
            val result = match(fixture(pkg), pkg)!!
            assertEquals("铁路出行", result.source.name)
            assertEquals(pkg, result.source.hostPackage)
            assertEquals(8, result.target!!.action)
            assertEquals("ad", result.panelKey)
        }
    }
    @Test fun neverUsesTheMiniAppCapsuleAsAnAdCloseButton() {
        val nodes = fixture().toMutableList()
        nodes[8] = nodes[8].copy(text = "", id = "", clickable = false)
        assertNull(match(nodes)!!.target)
    }
    @Test fun genericCloseAndCountdownNeedAdEvidenceInTheSamePanel() {
        val nodes = fixture().toMutableList()
        nodes[7] = nodes[7].copy(text = "5秒")
        assertNull(match(nodes)!!.target)
        nodes[8] = nodes[8].copy(text = "跳过 5")
        assertNull(match(nodes)!!.target)
    }
    @Test fun doesNotTreatANegativeSkipButtonAsAnAdAction() {
        val nodes = fixture().toMutableList()
        nodes[8] = nodes[8].copy(text = "不跳过")
        assertNull(match(nodes)!!.target)
    }
    @Test fun supportsExplicitAdLabelsAndBottomSkipControls() {
        val nodes = fixture().toMutableList()
        nodes[7] = nodes[7].copy(text = "")
        nodes[8] = nodes[8].copy(text = "关闭广告")
        assertNotNull(match(nodes)!!.target)
        nodes[7] = nodes[7].copy(text = "广告")
        nodes[8] = nodes[8].copy(text = "跳过", bounds = MiniAppAdRule.Bounds(820, 1450, 970, 1530))
        assertNotNull(match(nodes)!!.target)
    }
    @Test fun aBannerElsewhereCannotAuthorizeClosingAFormOrPaymentDialog() {
        val nodes = fixture().toMutableList()
        nodes[8] = nodes[8].copy(parent = 5)
        assertNull(match(nodes)!!.target)
        nodes[8] = nodes[8].copy(parent = 6)
        nodes.add(node(6, "verification", text = "验证码", bounds = MiniAppAdRule.Bounds(200, 900, 700, 1000)))
        assertNull(match(nodes)!!.target)
    }
    @Test fun disappearanceOfOnlyTheCloseButtonDoesNotHideRemainingAdEvidence() {
        val nodes = fixture().toMutableList()
        nodes[8] = nodes[8].copy(visible = false)
        val result = match(nodes, known = "ad")!!
        assertEquals("ad", result.panelKey)
        assertNull(result.target)
    }
    @Test fun doesNotGuessAnUnnamedMiniAppFromTheAdCreative() {
        val nodes = fixture().toMutableList()
        nodes[2] = nodes[2].copy(text = "")
        nodes.add(node(6, "creative", text = "低价购物", bounds = MiniAppAdRule.Bounds(200, 900, 800, 1000), cls = "android.widget.TextView"))
        assertNull(match(nodes)!!.source.name)
    }
    @Test fun rejectsHostChatsAndUnidentifiedAlipayWebPages() {
        val nodes = fixture().mapIndexed { i, n -> if (i in 1..4) n.copy(visible = false) else n }
        assertNull(MiniAppAdRule.match(wx, "com.tencent.mm.ui.LauncherUI", nodes, screen))
        val alipay = "com.eg.android.AlipayGphone"
        assertNull(MiniAppAdRule.match(alipay, activity(alipay), nodes, screen))
        assertNull(MiniAppAdRule.match("example.browser", activity(wx), fixture(), screen))
    }
    @Test fun retainedNamesApplyOnlyWithinTheSameRecognizedMiniAppContainer() {
        val nodes = fixture().mapIndexed { i, n -> if (i in 1..4) n.copy(visible = false) else n }
        val source = MiniAppSource.create(wx, "铁路出行")!!
        assertEquals(source.name, MiniAppAdRule.match(wx, activity(wx), nodes, screen, retainedSource = source)!!.source.name)
        assertNull(MiniAppAdRule.match(wx, "com.tencent.mm.ui.LauncherUI", nodes, screen, retainedSource = source))
    }
    @Test fun largeClickParentsUseThePreciseIconInsteadOfTheContainerCenter() {
        val nodes = fixture().toMutableList()
        nodes[6] = nodes[6].copy(clickable = true)
        nodes[8] = nodes[8].copy(clickable = false)
        assertNull(match(nodes)!!.target!!.action)
    }
    @Test fun aChatMessageMentioningCloseMiniAppIsNotContainerEvidence() {
        val nodes = fixture().toMutableList()
        nodes[3] = nodes[3].copy(text = "", visible = false)
        assertNull(MiniAppAdRule.match(wx, "com.tencent.mm.ui.LauncherUI", nodes, screen))
    }
}

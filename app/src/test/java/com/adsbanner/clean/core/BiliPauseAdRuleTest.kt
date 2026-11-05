package com.adsbanner.clean.core

import com.adsbanner.clean.core.BiliPauseAdRule.Bounds
import com.adsbanner.clean.core.BiliPauseAdRule.Node
import org.junit.Assert.*
import org.junit.Test

class BiliPauseAdRuleTest {
    private val screen = Bounds(0, 0, 1080, 2400)

    private class Page {
        val nodes = mutableListOf<Node>()
        fun add(key: String, parent: Int = -1, id: String = "", text: String = "", desc: String = "",
                visible: Boolean = true, clickable: Boolean = false,
                bounds: Bounds = Bounds(0, 0, 1080, 2400), className: String = ""): Int {
            nodes.add(Node(parent, key, id, text, desc, visible, true, clickable, bounds, className))
            return nodes.lastIndex
        }
        val root = add("root")
        val player = add("player", root, "tv.danmaku.bili:id/video_container", bounds = Bounds(0, 100, 1080, 780))
        val pager = add("pager", root, "tv.danmaku.bili:id/fl_tab_pager_container", bounds = Bounds(0, 780, 1080, 2400))

        fun hint(withCancel: Boolean = true, visibleWrapper: Boolean = true): Int? {
            val bar = add("hintBar", player, visible = visibleWrapper, bounds = Bounds(20, 650, 500, 750))
            add("countdown", bar, text = "3秒", bounds = Bounds(30, 670, 90, 720))
            add("hint", bar, text = "后将展示广告", bounds = Bounds(90, 670, 350, 720))
            return if (withCancel) add("cancel", bar, text = "取消", clickable = true,
                bounds = Bounds(360, 670, 470, 720)) else null
        }

        fun adPanel(): Pair<Int, Int> {
            val panel = add("panel", pager, bounds = Bounds(0, 780, 1080, 2400))
            add("more", panel, id = "tv.danmaku.bili:id/more", desc = "更多", clickable = true,
                bounds = Bounds(880, 810, 950, 890))
            val button = add("closeButton", panel, clickable = true, bounds = Bounds(980, 810, 1060, 890))
            val icon = add("closeIcon", button, desc = "close", bounds = Bounds(1000, 830, 1040, 870))
            add("adMark", panel, text = "广告", bounds = Bounds(30, 1700, 90, 1740))
            return icon to button
        }
    }

    @Test fun splitCountdownUsesCancelInTheSamePlayerHint() {
        val page = Page()
        val unrelated = page.add("otherCancel", page.pager, text = "取消", clickable = true,
            bounds = Bounds(360, 1000, 470, 1050))
        val expected = page.hint()
        val match = BiliPauseAdRule.match(page.nodes, screen)!!
        assertEquals(expected, match.cancel!!.icon)
        assertNotEquals(unrelated, match.cancel.icon)
    }

    @Test fun hintWithoutRelatedCancelDoesNotClickAnotherPageControl() {
        val page = Page()
        page.hint(withCancel = false)
        page.add("otherCancel", page.pager, text = "取消", clickable = true,
            bounds = Bounds(360, 670, 470, 720))
        val match = BiliPauseAdRule.match(page.nodes, screen)!!
        assertNotNull(match.hintKey)
        assertNull(match.cancel)
    }

    @Test fun invisibleComposeWrapperDoesNotHideVisibleChildren() {
        val page = Page()
        val cancel = page.hint(visibleWrapper = false)
        assertEquals(cancel, BiliPauseAdRule.match(page.nodes, screen)!!.cancel!!.icon)
    }

    @Test fun englishCloseSelectsItsOwnButtonInsteadOfMore() {
        val page = Page()
        val (icon, button) = page.adPanel()
        val match = BiliPauseAdRule.match(page.nodes, screen)!!
        assertEquals(icon, match.close!!.icon)
        assertEquals(button, match.close.action)
        // text 非空时也必须检查 contentDescription。
        page.nodes[icon] = page.nodes[icon].copy(text = "图标")
        assertEquals(icon, BiliPauseAdRule.match(page.nodes, screen)!!.close!!.icon)
    }

    @Test fun parentContainingMoreIsNeverUsedAsTheClickAction() {
        val page = Page()
        val (icon, button) = page.adPanel()
        page.nodes[button] = page.nodes[button].copy(bounds = Bounds(900, 810, 1060, 890))
        page.add("nestedMenu", button, desc = "更多", clickable = true, bounds = Bounds(910, 830, 950, 870))
        val target = BiliPauseAdRule.match(page.nodes, screen)!!.close!!
        assertEquals(icon, target.icon)
        assertNull(target.action)
    }

    @Test fun recommendationAdCannotValidateAnUnrelatedCloseButton() {
        val page = Page()
        page.adPanel()
        val marker = page.nodes.indexOfFirst { it.key == "adMark" }
        val recommendation = page.add("recommendation", page.pager, bounds = Bounds(0, 1800, 1080, 2400))
        page.nodes[marker] = page.nodes[marker].copy(parent = recommendation, bounds = Bounds(30, 1900, 90, 1940))
        assertNull(BiliPauseAdRule.match(page.nodes, screen)!!.close)
    }

    @Test fun hiddenCloseDoesNotMeanTheKnownAdPanelDisappeared() {
        val page = Page()
        val (icon, _) = page.adPanel()
        val panelKey = BiliPauseAdRule.match(page.nodes, screen)!!.panelKey
        page.nodes[icon] = page.nodes[icon].copy(visible = false)
        val after = BiliPauseAdRule.match(page.nodes, screen, panelKey)!!
        assertNull(after.close)
        assertEquals(panelKey, after.panelKey)
    }

    @Test fun fullScreenAndHiddenTargetsAreIgnored() {
        val page = Page()
        val cancel = page.hint()!!
        page.nodes[cancel] = page.nodes[cancel].copy(visible = false)
        assertNull(BiliPauseAdRule.match(page.nodes, screen)!!.cancel)
        page.nodes[page.player] = page.nodes[page.player].copy(bounds = screen)
        assertNull(BiliPauseAdRule.match(page.nodes, screen))
        assertNull(BiliPauseAdRule.match(page.nodes, Bounds(0, 0, 2400, 1080)))
    }

    @Test fun fullWidthComposeHintAndDirectPlayerSiblingsStillCancel() {
        for (direct in listOf(false, true)) {
            val page = Page()
            val scope = if (direct) page.player else page.add("compose", page.player, visible = false,
                bounds = Bounds(0, 100, 1080, 780))
            page.add("hint", scope, text = "3秒后将展示广告", bounds = Bounds(30, 670, 350, 720))
            val cancel = page.add("cancel", scope, text = "取消", clickable = true,
                bounds = Bounds(360, 670, 470, 720))
            assertEquals(cancel, BiliPauseAdRule.match(page.nodes, screen)!!.cancel!!.icon)
        }
    }

    @Test fun genericCloseWithLocalAdEvidenceDoesNotRequirePagerId() {
        val page = Page()
        val (icon, button) = page.adPanel()
        page.nodes[page.pager] = page.nodes[page.pager].copy(id = "new_details_layout")
        val match = BiliPauseAdRule.match(page.nodes, screen)!!
        assertEquals(icon, match.close!!.icon)
        assertEquals(button, match.close.action)
    }

    @Test fun specificPauseCloseWithoutBadgeRequiresRelatedButton() {
        val page = Page()
        val (icon, _) = page.adPanel()
        page.nodes[page.pager] = page.nodes[page.pager].copy(id = "new_details_layout")
        page.nodes[icon] = page.nodes[icon].copy(desc = "关闭暂停页")
        val mark = page.nodes.indexOfFirst { it.key == "adMark" }
        page.nodes[mark] = page.nodes[mark].copy(visible = false)
        assertNull(BiliPauseAdRule.match(page.nodes, screen)!!.close)
        val panel = page.nodes.indexOfFirst { it.key == "panel" }
        page.nodes[icon] = page.nodes[icon].copy(parent = panel)
        page.add("action", panel, text = "继续浏览", clickable = true, className = "android.widget.Button",
            bounds = Bounds(200, 1800, 850, 1920))
        val match = BiliPauseAdRule.match(page.nodes, screen)!!
        assertEquals(icon, match.close!!.icon)
        assertNull(match.close.action)
        // The CTA still being present must prevent false success when only the close icon vanishes.
        page.nodes[icon] = page.nodes[icon].copy(visible = false)
        assertEquals(match.panelKey, BiliPauseAdRule.match(page.nodes, screen, match.panelKey)!!.panelKey)
        val button = page.nodes.indexOfFirst { it.key == "action" }
        page.nodes[button] = page.nodes[button].copy(visible = false)
        val image = page.add("adImage", panel, className = "android.widget.ImageView", bounds = Bounds(0, 950, 1080, 1700))
        assertEquals(match.panelKey, BiliPauseAdRule.match(page.nodes, screen, match.panelKey)!!.panelKey)
        page.nodes[image] = page.nodes[image].copy(visible = false)
        assertNull(BiliPauseAdRule.match(page.nodes, screen, match.panelKey)!!.panelKey)
    }

    @Test fun ordinaryCloseAndButtonWithoutAdEvidenceAreNotEnough() {
        val page = Page()
        val (icon, _) = page.adPanel()
        page.nodes[page.pager] = page.nodes[page.pager].copy(id = "new_details_layout")
        page.nodes[page.nodes.indexOfFirst { it.key == "adMark" }] = page.nodes.first { it.key == "adMark" }.copy(visible = false)
        val panel = page.nodes.indexOfFirst { it.key == "panel" }
        page.nodes[icon] = page.nodes[icon].copy(parent = panel)
        page.add("button", panel, text = "确定", clickable = true, className = "android.widget.Button",
            bounds = Bounds(200, 1800, 850, 1920))
        assertNull(BiliPauseAdRule.match(page.nodes, screen)!!.close)
    }

    @Test fun renamedDetailsContainerCannotBorrowEvidenceFromIndependentRecommendation() {
        val page = Page()
        val (icon, _) = page.adPanel()
        page.nodes[page.pager] = page.nodes[page.pager].copy(id = "new_details_layout")
        val panel = page.nodes.indexOfFirst { it.key == "panel" }
        page.nodes[icon] = page.nodes[icon].copy(parent = page.pager)
        page.nodes[panel] = page.nodes[panel].copy(bounds = Bounds(0, 1800, 1080, 2400))
        page.nodes[page.nodes.indexOfFirst { it.key == "adMark" }] = page.nodes.first { it.key == "adMark" }.copy(bounds = Bounds(30, 1900, 90, 1940))
        assertNull(BiliPauseAdRule.match(page.nodes, screen)!!.close)
    }

    @Test fun nearestNonAdPanelPreventsClimbingToAnotherAdInThePage() {
        val page = Page()
        page.adPanel()
        page.nodes[page.pager] = page.nodes[page.pager].copy(id = "new_details_layout")
        val mark = page.nodes.indexOfFirst { it.key == "adMark" }
        val recommendation = page.add("recommendation", page.pager, bounds = Bounds(0, 1800, 1080, 2400))
        page.nodes[mark] = page.nodes[mark].copy(parent = recommendation, bounds = Bounds(30, 1900, 90, 1940))
        assertNull(BiliPauseAdRule.match(page.nodes, screen)!!.close)
    }

    @Test fun ordinaryDetailsActionCannotAuthorizeGenericCloseInANewContainer() {
        val page = Page()
        val (icon, _) = page.adPanel()
        page.nodes[page.pager] = page.nodes[page.pager].copy(id = "new_details_layout")
        val mark = page.nodes.indexOfFirst { it.key == "adMark" }
        page.nodes[mark] = page.nodes[mark].copy(text = "查看详情")
        assertNull(BiliPauseAdRule.match(page.nodes, screen)!!.close)
        page.nodes[icon] = page.nodes[icon].copy(desc = "关闭暂停页")
        assertNotNull(BiliPauseAdRule.match(page.nodes, screen)!!.close)
    }
}

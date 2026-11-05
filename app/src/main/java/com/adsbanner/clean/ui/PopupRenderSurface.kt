package com.adsbanner.clean.ui

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.RenderNode
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.annotation.RequiresApi

/** Replay a popup's settled display list while its clip/position changes, with no bitmap copy. */
@RequiresApi(29)
internal class PopupRenderSurface private constructor(
    val view: View,
    private val panel: View,
    private val parent: ViewGroup,
    private val backdrop: RenderNode,
    private val node: RenderNode,
    private val visibility: Int
) {
    fun setContentAlpha(alpha: Float) {
        node.alpha = alpha
        view.invalidate()
    }

    fun release(restore: Boolean) {
        parent.overlay.remove(view)
        backdrop.discardDisplayList()
        node.discardDisplayList()
        if (restore) panel.visibility = visibility
    }

    companion object {
        fun capture(panel: View): PopupRenderSurface? {
            if (!panel.isHardwareAccelerated || panel.width <= 0 || panel.height <= 0) return null
            val parent = panel.parent as? ViewGroup ?: return null
            val node = RenderNode("AdsBanner popup return").apply { setPosition(0, 0, panel.width, panel.height) }
            val backdrop = RenderNode("AdsBanner popup background").apply { setPosition(0, 0, panel.width, panel.height) }
            val background = panel.background
            val backgroundRecording = backdrop.beginRecording()
            try { background?.draw(backgroundRecording) } finally { backdrop.endRecording() }
            val recording = node.beginRecording()
            val backgroundAlpha = background?.alpha
            try {
                // View.draw may reference the view's mutable background RenderNode, which is
                // cleared when the real panel is hidden. Record that drawable independently.
                background?.alpha = 0
                panel.draw(recording)
            } finally {
                if (backgroundAlpha != null) background?.alpha = backgroundAlpha
                node.endRecording()
            }
            val shape = Outline().also { panel.outlineProvider?.getOutline(panel, it) }
            val surface = object : View(panel.context) {
                override fun onDraw(canvas: Canvas) {
                    if (canvas.isHardwareAccelerated) {
                        if (backdrop.hasDisplayList()) canvas.drawRenderNode(backdrop)
                        if (node.hasDisplayList()) canvas.drawRenderNode(node)
                    }
                }
            }.apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                alpha = panel.alpha
                translationX = panel.translationX
                translationY = panel.translationY
                pivotX = panel.pivotX
                pivotY = panel.pivotY
                scaleX = panel.scaleX
                scaleY = panel.scaleY
                clipBounds = panel.clipBounds
                clipToOutline = panel.clipToOutline
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) { outline.set(shape) }
                }
                measure(View.MeasureSpec.makeMeasureSpec(panel.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(panel.height, View.MeasureSpec.EXACTLY))
                layout(panel.left, panel.top, panel.right, panel.bottom)
            }
            val visibility = panel.visibility
            parent.overlay.add(surface)
            panel.visibility = View.INVISIBLE
            return PopupRenderSurface(surface, panel, parent, backdrop, node, visibility)
        }
    }
}

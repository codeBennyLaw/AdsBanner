package com.adsbanner.clean.ui

import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.RenderNode
import android.os.Build
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
    private val node: RenderNode,
    private val visibility: Int
) {
    fun release(restore: Boolean) {
        parent.overlay.remove(view)
        node.discardDisplayList()
        if (restore) panel.visibility = visibility
    }

    companion object {
        fun capture(panel: View): PopupRenderSurface? {
            if (Build.VERSION.SDK_INT < 29 || !panel.isHardwareAccelerated || panel.width <= 0 || panel.height <= 0) return null
            val parent = panel.parent as? ViewGroup ?: return null
            val node = RenderNode("AdsBanner popup return").apply { setPosition(0, 0, panel.width, panel.height) }
            val recording = node.beginRecording()
            try { panel.draw(recording) } finally { node.endRecording() }
            val shape = Outline().also { panel.outlineProvider?.getOutline(panel, it) }
            val surface = object : View(panel.context) {
                override fun onDraw(canvas: Canvas) {
                    if (canvas.isHardwareAccelerated && node.hasDisplayList()) canvas.drawRenderNode(node)
                }
            }.apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                alpha = panel.alpha
                translationX = panel.translationX
                translationY = panel.translationY
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
            return PopupRenderSurface(surface, panel, parent, node, visibility)
        }
    }
}

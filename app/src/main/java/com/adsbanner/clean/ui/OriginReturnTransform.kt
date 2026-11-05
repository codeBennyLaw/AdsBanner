package com.adsbanner.clean.ui

import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import kotlin.math.roundToInt

/** Geometry changes only render properties; the popup/page layout stays settled. */
internal class OriginReturnTransform(private val view: View, origin: Rect, private val endRadius: Float) {
    private val location = IntArray(2).also(view::getLocationOnScreen)
    private val initialX = view.translationX
    private val initialY = view.translationY
    private val initialOutline = view.outlineProvider
    private val initialClipToOutline = view.clipToOutline
    private val initialClip = view.clipBounds
    private val initialPivotX = view.pivotX
    private val initialPivotY = view.pivotY
    private val startRadius = Outline().also { initialOutline?.getOutline(view, it) }.radius.coerceAtLeast(0f)
    private val geometry = ReturnGeometry(view.width.toFloat(), view.height.toFloat(),
        origin.left - location[0].toFloat(), origin.top - location[1].toFloat(),
        origin.width().toFloat(), origin.height().toFloat())
    private val clip = Rect(0, 0, view.width, view.height)
    private var radius = startRadius

    init {
        view.pivotX = 0f
        view.pivotY = 0f
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) = outline.setRoundRect(clip, radius)
        }
    }

    fun apply(progress: Float) {
        geometry.update(progress)
        clip.set(geometry.clipLeft.roundToInt(), geometry.clipTop.roundToInt(),
            (geometry.clipLeft + geometry.clipWidth).roundToInt(), (geometry.clipTop + geometry.clipHeight).roundToInt())
        view.scaleX = geometry.scale
        view.scaleY = geometry.scale
        view.translationX = initialX + geometry.x
        view.translationY = initialY + geometry.y
        radius = (startRadius + (endRadius - startRadius) * progress.coerceIn(0f, 1f)) / geometry.scale
        view.clipBounds = clip
        view.invalidateOutline()
    }

    fun restore() {
        view.scaleX = 1f; view.scaleY = 1f
        view.translationX = initialX; view.translationY = initialY
        view.pivotX = initialPivotX; view.pivotY = initialPivotY
        view.outlineProvider = initialOutline; view.clipToOutline = initialClipToOutline
        view.clipBounds = initialClip
    }
}

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
    private val initialScaleX = view.scaleX
    private val initialScaleY = view.scaleY
    private val initialOutline = view.outlineProvider
    private val initialClipToOutline = view.clipToOutline
    private val initialClip = view.clipBounds
    private val initialPivotX = view.pivotX
    private val initialPivotY = view.pivotY
    private val startRadius = Outline().also { initialOutline?.getOutline(view, it) }.radius.coerceAtLeast(0f) * initialScaleX
    private val geometry = ReturnGeometry(view.width.toFloat(), view.height.toFloat(),
        (origin.left - location[0].toFloat()) / initialScaleX, (origin.top - location[1].toFloat()) / initialScaleY,
        origin.width() / initialScaleX, origin.height() / initialScaleY)
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
        view.scaleX = initialScaleX * geometry.scale
        view.scaleY = initialScaleY * geometry.scale
        view.translationX = initialX + initialPivotX * (1f - initialScaleX) + geometry.x * initialScaleX
        view.translationY = initialY + initialPivotY * (1f - initialScaleY) + geometry.y * initialScaleY
        radius = (startRadius + (endRadius - startRadius) * progress.coerceIn(0f, 1f)) / view.scaleX
        view.clipBounds = clip
        view.invalidateOutline()
    }

    fun restore() {
        view.scaleX = initialScaleX; view.scaleY = initialScaleY
        view.translationX = initialX; view.translationY = initialY
        view.pivotX = initialPivotX; view.pivotY = initialPivotY
        view.outlineProvider = initialOutline; view.clipToOutline = initialClipToOutline
        view.clipBounds = initialClip
    }
}

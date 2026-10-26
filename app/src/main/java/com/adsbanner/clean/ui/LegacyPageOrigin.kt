package com.adsbanner.clean.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import android.os.Parcelable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import com.adsbanner.clean.R
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.shape.Shapeable
import com.google.android.material.transition.platform.MaterialContainerTransformSharedElementCallback

/** Let the platform hide its shared element without hiding the actual home card. */
internal class LegacyPageOrigin private constructor(private val source: View) : View(source.context), Shapeable {
    private var shape = (source as? Shapeable)?.shapeAppearanceModel ?: ShapeAppearanceModel.builder().build()
    private var drawSource = true

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
    }

    // A proxy is drawn explicitly by snapshot capture / the container transform, never on
    // top of the stationary card. The platform may otherwise restore this alpha to one.
    override fun setAlpha(alpha: Float) = super.setAlpha(0f)
    override fun onDraw(canvas: Canvas) { if (drawSource) source.draw(canvas) }
    override fun getShapeAppearanceModel(): ShapeAppearanceModel = shape
    override fun setShapeAppearanceModel(shapeAppearanceModel: ShapeAppearanceModel) { shape = shapeAppearanceModel }

    @SuppressLint("RtlHardcoded") // Screen positions are physical coordinates.
    private fun place(activity: Activity) {
        val stage = activity.findViewById<FrameLayout>(android.R.id.content)
        val sourceAt = IntArray(2)
        val stageAt = IntArray(2)
        source.getLocationOnScreen(sourceAt)
        stage.getLocationOnScreen(stageAt)
        val left = sourceAt[0] - stageAt[0]
        val top = sourceAt[1] - stageAt[1]
        layoutParams = FrameLayout.LayoutParams(source.width, source.height).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            leftMargin = left
            topMargin = top
        }
        if (parent == null) stage.addView(this)
        measure(MeasureSpec.makeMeasureSpec(source.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(source.height, MeasureSpec.EXACTLY))
        layout(left, top, left + source.width, top + source.height)
    }

    companion object {
        fun begin(activity: Activity, source: View): View = anchorFor(activity, source).also {
            (it as LegacyPageOrigin).drawSource = true
        }

        fun anchorFor(activity: Activity, source: View): View {
            val anchor = source as? LegacyPageOrigin
                ?: source.getTag(R.id.motion_origin_anchor) as? LegacyPageOrigin
                ?: LegacyPageOrigin(source).also { source.setTag(R.id.motion_origin_anchor, it) }
            anchor.place(activity)
            return anchor
        }

        fun callback(activity: Activity) = object : MaterialContainerTransformSharedElementCallback() {
            override fun onMapSharedElements(names: MutableList<String>, sharedElements: MutableMap<String, View>) {
                names.forEach { name ->
                    val source = sharedElements[name] ?: return@forEach
                    if (source is LegacyPageOrigin || source.id == R.id.cardStats || source.id == R.id.cardTargets) {
                        sharedElements[name] = anchorFor(activity, source)
                    }
                }
                super.onMapSharedElements(names, sharedElements)
            }

            override fun onCaptureSharedElementSnapshot(sharedElement: View, matrix: Matrix,
                screenBounds: RectF): Parcelable? {
                val snapshot = super.onCaptureSharedElementSnapshot(sharedElement, matrix, screenBounds)
                // The opening image is now independent. The next (return) capture must be empty.
                (sharedElement as? LegacyPageOrigin)?.drawSource = false
                return snapshot
            }
        }
    }
}

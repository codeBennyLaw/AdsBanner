package com.adsbanner.clean.ui

import kotlin.math.max
import kotlin.math.min

/** Uniformly scale and crop settled contents into the real entry bounds. No per-frame allocation. */
internal class ReturnGeometry(
    private val width: Float,
    private val height: Float,
    private val endX: Float,
    private val endY: Float,
    private val endWidth: Float,
    private val endHeight: Float
) {
    var scale = 1f; private set
    var x = 0f; private set
    var y = 0f; private set
    var clipLeft = 0f; private set
    var clipTop = 0f; private set
    var clipWidth = width; private set
    var clipHeight = height; private set

    init { require(width > 0 && height > 0 && endWidth > 0 && endHeight > 0) }

    fun update(progress: Float) {
        val p = progress.coerceIn(0f, 1f)
        val endScale = max(endWidth / width, endHeight / height)
        scale = 1f + (endScale - 1f) * p
        clipWidth = min(width, (width + (endWidth - width) * p) / scale)
        clipHeight = min(height, (height + (endHeight - height) * p) / scale)
        clipLeft = (width - clipWidth) / 2f
        clipTop = (height - clipHeight) / 2f
        x = endX * p - clipLeft * scale
        y = endY * p - clipTop * scale
    }
}

internal fun motionFraction(start: Float, end: Float, value: Float): Float {
    val p = ((value - start) / (end - start)).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

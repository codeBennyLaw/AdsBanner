package com.adsbanner.clean.ui

/** Keep the closing surface recognizable until it is nearly inside its entry. */
internal object RetractionVisuals {
    const val DURATION_MS = 300L
    const val CONTENT_FADE_START = 0.72f
    const val CONTENT_FADE_END = 0.98f

    fun contentAlpha(progress: Float) = 1f - motionFraction(CONTENT_FADE_START, CONTENT_FADE_END, progress)
    fun panelAlpha(progress: Float) = 1f - motionFraction(0.9f, 1f, progress)
}

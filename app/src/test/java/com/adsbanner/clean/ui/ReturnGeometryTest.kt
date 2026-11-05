package com.adsbanner.clean.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnGeometryTest {
    @Test fun returnsToEntryAtEveryEdgeAndAspectRatio() {
        for ((x, y, w, h) in listOf(
            listOf(10f, 800f, 96f, 48f), listOf(-30f, -70f, 300f, 100f),
            listOf(440f, 20f, 48f, 400f), listOf(5f, 2f, 1100f, 1400f)
        )) {
            val geometry = ReturnGeometry(600f, 900f, x, y, w, h)
            geometry.update(1f)
            assertEquals(x, geometry.x + geometry.clipLeft * geometry.scale, 0.001f)
            assertEquals(y, geometry.y + geometry.clipTop * geometry.scale, 0.001f)
            assertEquals(w, geometry.clipWidth * geometry.scale, 0.001f)
            assertEquals(h, geometry.clipHeight * geometry.scale, 0.001f)
        }
    }

    @Test fun startsWithUnchangedContentsAndClampsOutOfRangeProgress() {
        val geometry = ReturnGeometry(600f, 900f, 10f, 800f, 96f, 48f)
        geometry.update(-1f)
        assertEquals(1f, geometry.scale, 0f)
        assertEquals(0f, geometry.x, 0f)
        assertEquals(0f, geometry.y, 0f)
        assertEquals(600f, geometry.clipWidth, 0f)
        assertEquals(900f, geometry.clipHeight, 0f)
        geometry.update(2f)
        assertEquals(96f, geometry.clipWidth * geometry.scale, 0.001f)
    }

    @Test fun boundsStayContinuousAndInsideTheSettledContents() {
        val geometry = ReturnGeometry(600f, 900f, -10f, 700f, 240f, 64f)
        for (step in 0..100) {
            val p = step / 100f
            geometry.update(p)
            assertTrue(geometry.scale > 0f)
            assertTrue(geometry.clipLeft >= 0f && geometry.clipTop >= 0f)
            assertTrue(geometry.clipLeft + geometry.clipWidth <= 600.001f)
            assertTrue(geometry.clipTop + geometry.clipHeight <= 900.001f)
            assertEquals(600f + (240f - 600f) * p, geometry.clipWidth * geometry.scale, 0.001f)
            assertEquals(900f + (64f - 900f) * p, geometry.clipHeight * geometry.scale, 0.001f)
        }
    }

    @Test fun shrinkingCanBeCancelledAndResumedAtTheSamePose() {
        val geometry = ReturnGeometry(600f, 900f, 20f, 700f, 240f, 64f)
        geometry.update(0.43f)
        val x = geometry.x
        val scale = geometry.scale
        geometry.update(0.2f)
        geometry.update(0.43f)
        assertEquals(x, geometry.x, 0f)
        assertEquals(scale, geometry.scale, 0f)
        assertEquals(0f, motionFraction(0.65f, 1f, 0.5f), 0f)
        assertEquals(1f, motionFraction(0.65f, 1f, 1f), 0f)
    }
}

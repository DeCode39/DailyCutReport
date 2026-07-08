package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrImageModelsTest {
    @Test fun cropRegionEnforcesBoundsAndMinimumSpan() {
        val crop = CropRegion(-1f, 0.95f, 0.02f, 2f).normalized()
        assertEquals(0f, crop.left, 0f)
        assertEquals(0.9f, crop.top, 0.001f)
        assertEquals(0.1f, crop.right, 0.001f)
        assertEquals(1f, crop.bottom, 0f)
    }

    @Test fun qualityWarningsAreAdvisoryAndSpecific() {
        val quality = ImageQuality(width = 500, height = 400, contrast = 10.0, sharpness = 3.0, glareFraction = 0.2)
        assertEquals(4, quality.warnings.size)
        assertTrue(quality.warnings.any { it.contains("resolution") })
        assertTrue(quality.warnings.any { it.contains("glare") })
    }
}

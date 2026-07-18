package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HealthAnalyticsTest {
    @Test fun formattingRoundsFloatingNoiseAndNormalizesNegativeZero() {
        assertEquals("98", formatDecimal(97.999999999996))
        assertEquals("12.35", formatDecimal(12.345))
        assertEquals("0", formatDecimal(-0.0001))
    }

    @Test fun dailyMedianUsesAllSameDateReadings() {
        val date = LocalDate.of(2026, 7, 17)
        val values = listOf(
            WeightEntry("hc", date, 2L, 81.0, WeightSource.HEALTH_CONNECT),
            WeightEntry("manual", date, 1L, 79.5, WeightSource.MANUAL)
        )
        assertEquals(80.25, HealthAnalyticsEngine().representativeWeights(values).single().weightKg, 0.0)
    }

    @Test fun walkingUsesWeightFallbackAndCapsAtNinetyMinutes() {
        val estimate = HealthAnalyticsEngine().walkingEstimate(1000.0, 80.0, emptyList())
        assertNotNull(estimate)
        assertEquals(90.0, estimate!!.minutes, 0.001)
        assertTrue(estimate.capped)
        assertTrue(estimate.remainingGap > 0.0)
        assertEquals(WalkingEstimateSource.WEIGHT_FALLBACK, estimate.source)
    }

    @Test fun walkingRequiresPersonalCaloriesOrWeight() {
        assertNull(HealthAnalyticsEngine().walkingEstimate(300.0, null, emptyList()))
    }

    @Test fun ocrMergePreservesIdentityPricingAndMissingManualNutrients() {
        val draft = ProductEditorDraft(
            barcode = "123", name = "Base&U", brand = "Brand", servingLabel = "300 ml",
            calories = "200", protein = "22.2", sodium = "168", purchasePrice = "45",
            includeInPlanner = false, extras = "Potassium=12 mg"
        )
        val merged = draft.mergeOcr(OcrNutritionDraft(
            basis = OcrBasis.PER_SERVING,
            values = mapOf(OcrField.CALORIES to 232.5, OcrField.FAT to 4.5),
            servingLabel = ""
        ))
        assertEquals("Base&U", merged.name)
        assertEquals("Brand", merged.brand)
        assertEquals("45", merged.purchasePrice)
        assertEquals("22.2", merged.protein)
        assertEquals("232.5", merged.calories)
        assertEquals("4.5", merged.fat)
        assertEquals("300 ml", merged.servingLabel)
        assertEquals(false, merged.includeInPlanner)
    }
}

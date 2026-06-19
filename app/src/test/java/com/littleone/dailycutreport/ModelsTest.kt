package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ModelsTest {
    @Test fun explicitZeroOverrideWinsOverFoodLog() {
        val report = DailyReport(
            date = LocalDate.of(2026, 1, 2),
            nutrition = NutritionSummary(calories = 1_800.0, proteinG = 120.0, entries = 2),
            manual = ManualOverrides(foodCalories = 0.0, proteinG = 0.0)
        )
        assertEquals(0.0, report.finalFoodCalories, 0.0)
        assertEquals(0.0, report.finalProteinG, 0.0)
        assertEquals("Manual override", report.nutritionSource)
    }

    @Test fun reportUsesStandardVerdictThresholds() {
        fun report(deficit: Double) = DailyReport(
            date = LocalDate.of(2026, 1, 2),
            health = HealthSummary(totalCalories = 2_000.0),
            manual = ManualOverrides(foodCalories = 2_000.0 - deficit)
        )
        assertEquals(DayVerdict.CUT, report(300.0).verdict)
        assertEquals(DayVerdict.SURPLUS, report(-200.0).verdict)
        assertEquals(DayVerdict.MAINTENANCE, report(299.0).verdict)
        assertEquals(DayVerdict.MAINTENANCE, report(-199.0).verdict)
    }

    @Test fun foodLogSnapshotCalculatesTotalsFromQuantity() {
        val log = FoodLogSnapshot(
            date = LocalDate.of(2026, 1, 2), barcode = "123", productName = "Meal",
            quantity = 2.5, caloriesPerServing = 400.0, proteinGPerServing = 20.0
        )
        assertEquals(1_000.0, log.calories, 0.0)
        assertEquals(50.0, log.proteinG, 0.0)
    }
}


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
            quantity = 2.5,
            caloriesPerServing = 400.0,
            proteinGPerServing = 20.0,
            sodiumMgPerServing = 100.0,
            carbsGPerServing = 30.0,
            fatGPerServing = 10.0,
            sugarGPerServing = 4.0,
            fiberGPerServing = 2.0,
            saturatedFatGPerServing = 1.0,
            actualPaidTotalMicros = 15_000_000L
        )
        assertEquals(1_000.0, log.calories, 0.0)
        assertEquals(50.0, log.proteinG, 0.0)
        assertEquals(250.0, log.sodiumMg, 0.0)
        assertEquals(75.0, log.carbsG, 0.0)
        assertEquals(25.0, log.fatG, 0.0)
        assertEquals(10.0, log.sugarG, 0.0)
        assertEquals(5.0, log.fiberG, 0.0)
        assertEquals(2.5, log.saturatedFatG, 0.0)
    }

    @Test fun quantityEditPreservesNutrientSnapshot() {
        val log = FoodLogSnapshot(
            id = 42,
            date = LocalDate.of(2026, 1, 2),
            productName = "Meal",
            servingLabel = "300 ml",
            quantity = 1.0,
            caloriesPerServing = 250.0,
            proteinGPerServing = 12.0,
            sodiumMgPerServing = 300.0,
            carbsGPerServing = 30.0,
            fatGPerServing = 8.0,
            sugarGPerServing = 4.0,
            fiberGPerServing = 2.0,
            saturatedFatGPerServing = 1.0,
            actualPaidTotalMicros = 15_000_000L
        )

        val edit = log.quantityEdit(2.5)

        assertEquals(42, edit.id)
        assertEquals(2.5, edit.quantity, 0.0)
        assertEquals(15_000_000L, edit.actualPaidTotalMicros)
        assertEquals("300 ml", log.servingLabel)
        assertEquals(250.0, log.caloriesPerServing, 0.0)
        assertEquals(12.0, log.proteinGPerServing, 0.0)
        assertEquals(300.0, log.sodiumMgPerServing, 0.0)
        assertEquals(30.0, log.carbsGPerServing, 0.0)
        assertEquals(8.0, log.fatGPerServing, 0.0)
        assertEquals(4.0, log.sugarGPerServing, 0.0)
        assertEquals(2.0, log.fiberGPerServing, 0.0)
        assertEquals(1.0, log.saturatedFatGPerServing, 0.0)
    }

    @Test fun ignoredLoggedPriceIsRetainedButExcludedFromEffectiveCost() {
        val log = FoodLogSnapshot(
            date = LocalDate.of(2026, 1, 2), productName = "Discounted meal", quantity = 2.0,
            catalogCostPerServingMicros = 10_000_000L, actualPaidTotalMicros = 15_000_000L,
            excludeCostFromBudget = true
        )

        assertEquals(15_000_000L, log.recordedCostMicros)
        assertEquals(0L, log.effectiveCostMicros)
        assertEquals(true, log.quantityEdit(3.0).excludeCostFromBudget)
    }

    @Test fun missingBurnProducesUnavailableBalance() {
        val report = DailyReport(
            date = LocalDate.of(2026, 1, 2),
            nutrition = NutritionSummary(calories = 2_000.0)
        )

        assertEquals(EnergyBalance.Unavailable, report.energyBalance)
        assertEquals(DayVerdict.UNAVAILABLE, report.verdict)
    }

    @Test fun defaultNutritionTargetsMatchDailyPlan() {
        val targets = DailyNutritionTargets()
        assertEquals(1850.0, targets.calories, 0.0)
        assertEquals(120.0, targets.proteinG, 0.0)
        assertEquals(2000.0, targets.sodiumMg, 0.0)
        assertEquals(150.0, targets.carbsG, 0.0)
        assertEquals(60.0, targets.fatG, 0.0)
        assertEquals(50.0, targets.sugarG, 0.0)
        assertEquals(15.0, targets.fiberG, 0.0)
        assertEquals(15.0, targets.saturatedFatG, 0.0)
    }

    @Test fun macroThresholdNotifierOnlyReportsCrossingsOnce() {
        val notifier = MacroThresholdNotifier()
        val date = LocalDate.of(2026, 1, 2)
        val before = NutritionSummary(calories = 1_800.0)
        val after = NutritionSummary(calories = 1_900.0)

        assertEquals("Calories target reached (1900 / 1850)", notifier.crossingMessage(date, before, after))
        assertEquals(null, notifier.crossingMessage(date, before, after))
        assertEquals(null, notifier.crossingMessage(date, after, after))
    }

    @Test fun macroThresholdNotifierUsesProteinAndFiberTargets() {
        val notifier = MacroThresholdNotifier()
        val date = LocalDate.of(2026, 1, 2)

        assertEquals(
            "Protein target reached (125 / 120)",
            notifier.crossingMessage(date, NutritionSummary(proteinG = 110.0), NutritionSummary(proteinG = 125.0))
        )
        assertEquals(
            "Fiber target reached (16 / 15)",
            notifier.crossingMessage(date, NutritionSummary(fiberG = 10.0), NutritionSummary(fiberG = 16.0))
        )
    }
}

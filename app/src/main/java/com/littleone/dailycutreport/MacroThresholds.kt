package com.littleone.dailycutreport

import java.time.LocalDate
import kotlin.math.roundToInt

class MacroThresholdNotifier(
    private val targets: DailyNutritionTargets = DailyNutritionTargets()
) {
    private val notified = mutableSetOf<String>()

    fun crossingMessage(date: LocalDate, before: NutritionSummary, after: NutritionSummary): String? =
        listOfNotNull(
            threshold(date, "calories", "Calories", before.calories, after.calories, targets.calories),
            threshold(date, "protein", "Protein", before.proteinG, after.proteinG, targets.proteinG),
            threshold(date, "sodium", "Sodium", before.sodiumMg, after.sodiumMg, targets.sodiumMg),
            threshold(date, "carbs", "Carbs", before.carbsG, after.carbsG, targets.carbsG),
            threshold(date, "fat", "Fat", before.fatG, after.fatG, targets.fatG),
            threshold(date, "sugar", "Sugar", before.sugarG, after.sugarG, targets.sugarG),
            threshold(date, "fiber", "Fiber", before.fiberG, after.fiberG, targets.fiberG),
            threshold(date, "saturatedFat", "Saturated fat", before.saturatedFatG, after.saturatedFatG, targets.saturatedFatG)
        ).firstOrNull()

    private fun threshold(
        date: LocalDate,
        key: String,
        label: String,
        before: Double,
        after: Double,
        target: Double
    ): String? {
        if (before >= target || after < target) return null
        if (!notified.add("$date:$key")) return null
        return "$label target reached (${after.roundToInt()} / ${target.roundToInt()})"
    }
}

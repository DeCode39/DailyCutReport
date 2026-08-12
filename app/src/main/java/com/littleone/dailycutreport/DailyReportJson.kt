package com.littleone.dailycutreport

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import kotlin.math.roundToLong

/** A compact, stable and barcode-free representation intended for clipboard export. */
object DailyReportJson {
    const val SCHEMA_VERSION = 1

    fun encode(state: TodayUiState): String {
        val report = state.report
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("app", "DailyCutReport")
            .put("date", report.date.toString())
            .put("generatedAt", Instant.now().toString())
            .put("goalMode", state.goals.mode.name.lowercase())
            .put("energy", JSONObject()
                .put("projectedFinalBurnKcal", report.projectedBurnCalories?.let(::calories) ?: JSONObject.NULL)
                .put("loggedIntakeKcal", calories(report.finalFoodCalories))
                .put("burnMinusIntakeKcal", report.energyBalance.signedCaloriesOrNull()?.let(::calories) ?: JSONObject.NULL)
                .put("verdict", report.verdict.name.lowercase())
                .put("desiredDeficitKcal", calories(state.goals.desiredDeficitCalories))
                .put("effectiveAllowanceKcal", state.calorieAllowance?.let(::calories) ?: JSONObject.NULL))
            .put("activity", JSONObject()
                .put("steps", report.health.steps)
                .put("distanceKm", decimal(report.health.distanceKm))
                .put("activeCaloriesKcal", calories(report.health.activeCalories))
                .put("exerciseSessions", report.health.exerciseSessions)
                .put("exerciseMinutes", report.health.exerciseMinutes)
                .put("source", report.health.healthConnectStatus)
                .put("updatedAt", Instant.ofEpochMilli(report.savedAtEpochMs).toString()))
            .put("nutrition", nutrition(report.nutrition))
            .put("targets", targets(state.targets))
            .put("spending", spending(state.spending, state.goals))
            .put("foodGroups", foodGroups(state.logs))
        return root.toString(2)
    }

    private fun nutrition(value: NutritionSummary) = JSONObject()
        .put("caloriesKcal", calories(value.calories))
        .put("proteinG", decimal(value.proteinG))
        .put("sodiumMg", decimal(value.sodiumMg))
        .put("carbohydratesG", decimal(value.carbsG))
        .put("fatG", decimal(value.fatG))
        .put("sugarG", decimal(value.sugarG))
        .put("fiberG", decimal(value.fiberG))
        .put("saturatedFatG", decimal(value.saturatedFatG))
        .put("entries", value.entries)

    private fun targets(value: DailyNutritionTargets) = JSONObject()
        .put("caloriesKcal", calories(value.calories))
        .put("proteinG", decimal(value.proteinG))
        .put("sodiumMg", decimal(value.sodiumMg))
        .put("carbohydratesG", decimal(value.carbsG))
        .put("fatG", decimal(value.fatG))
        .put("sugarG", decimal(value.sugarG))
        .put("fiberG", decimal(value.fiberG))
        .put("saturatedFatG", decimal(value.saturatedFatG))

    private fun spending(value: DailySpending, goals: UserGoals) = JSONObject()
        .put("currency", goals.currencyCode)
        .put("knownTotalMicros", value.knownTotalMicros)
        .put("budgetMicros", goals.dailyBudgetMicros)
        .put("unknownEntries", value.unknownEntries)
        .put("complete", value.isComplete)

    private fun foodGroups(logs: List<FoodLogSnapshot>): JSONArray {
        val output = JSONArray()
        logs.groupForDisplay().forEach { group ->
            when (group) {
                is FoodLogGroup.Single -> output.put(JSONObject()
                    .put("type", "entry")
                    .put("items", JSONArray().put(food(group.log))))
                is FoodLogGroup.Bulk -> output.put(JSONObject()
                    .put("type", "order")
                    .put("label", group.label)
                    .put("mealId", group.mealId)
                    .put("items", JSONArray().apply { group.logs.forEach { put(food(it)) } }))
            }
        }
        return output
    }

    private fun food(log: FoodLogSnapshot) = JSONObject()
        .put("name", log.productName)
        .put("brand", log.brand.takeIf(String::isNotBlank) ?: JSONObject.NULL)
        .put("quantity", JSONObject()
            .put("enteredAmount", decimal(log.enteredAmount))
            .put("enteredUnit", log.enteredUnit.lowercase())
            .put("servings", decimal(log.quantity))
            .put("servingLabel", log.servingLabel))
        .put("nutrition", JSONObject()
            .put("caloriesKcal", calories(log.calories))
            .put("proteinG", decimal(log.proteinG))
            .put("sodiumMg", decimal(log.sodiumMg))
            .put("carbohydratesG", decimal(log.carbsG))
            .put("fatG", decimal(log.fatG))
            .put("sugarG", decimal(log.sugarG))
            .put("fiberG", decimal(log.fiberG))
            .put("saturatedFatG", decimal(log.saturatedFatG)))
        .put("cost", JSONObject()
            .put("totalMicros", log.recordedCostMicros ?: JSONObject.NULL)
            .put("source", when {
                log.actualPaidTotalMicros != null -> "actual_paid"
                log.recordedCostMicros != null -> "catalog_estimate"
                else -> "unknown"
            })
            .put("excludedFromBudget", log.excludeCostFromBudget))

    private fun EnergyBalance.signedCaloriesOrNull(): Double? = when (this) {
        EnergyBalance.Unavailable -> null
        is EnergyBalance.Cut -> calories
        is EnergyBalance.Surplus -> -calories
        is EnergyBalance.Maintenance -> calories
    }

    private fun calories(value: Double): Long = value.takeIf(Double::isFinite)?.roundToLong() ?: 0L

    private fun decimal(value: Double): Any = value.takeIf(Double::isFinite)
        ?.let { BigDecimal.valueOf(it).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros() }
        ?: JSONObject.NULL
}

package com.littleone.dailycutreport

import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

data class HealthSummary(
    val steps: Long = 0L,
    val distanceKm: Double = 0.0,
    val activeCalories: Double = 0.0,
    val totalCalories: Double = 0.0,
    val exerciseSessions: Int = 0,
    val exerciseMinutes: Long = 0L,
    val nutritionCalories: Double = 0.0,
    val nutritionProteinG: Double = 0.0,
    val nutritionSodiumMg: Double = 0.0,
    val nutritionRecords: Int = 0,
    val healthConnectStatus: String = "Not loaded"
)

data class LocalNutritionSummary(
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val sugarG: Double = 0.0,
    val fiberG: Double = 0.0,
    val saturatedFatG: Double = 0.0,
    val entries: Int = 0,
    val extras: Map<String, String> = emptyMap()
)

data class ManualEntry(
    val foodCalories: Double = 0.0,
    val proteinG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val manualBurnCalories: Double? = null,
    val notes: String = ""
)

data class DailyReport(
    val date: LocalDate,
    val health: HealthSummary = HealthSummary(),
    val localNutrition: LocalNutritionSummary = LocalNutritionSummary(),
    val manual: ManualEntry = ManualEntry(),
    val savedAtEpochMs: Long = System.currentTimeMillis()
) {
    val finalBurnCalories: Double
        get() = manual.manualBurnCalories ?: when {
            health.totalCalories > 0.0 -> health.totalCalories
            health.activeCalories > 0.0 -> health.activeCalories
            else -> 0.0
        }

    val finalFoodCalories: Double
        get() = when {
            manual.foodCalories > 0.0 -> manual.foodCalories
            localNutrition.calories > 0.0 -> localNutrition.calories
            health.nutritionCalories > 0.0 -> health.nutritionCalories
            else -> 0.0
        }

    val finalProteinG: Double
        get() = when {
            manual.proteinG > 0.0 -> manual.proteinG
            localNutrition.proteinG > 0.0 -> localNutrition.proteinG
            health.nutritionProteinG > 0.0 -> health.nutritionProteinG
            else -> 0.0
        }

    val finalSodiumMg: Double
        get() = when {
            manual.sodiumMg > 0.0 -> manual.sodiumMg
            localNutrition.sodiumMg > 0.0 -> localNutrition.sodiumMg
            health.nutritionSodiumMg > 0.0 -> health.nutritionSodiumMg
            else -> 0.0
        }

    val nutritionSource: String
        get() = when {
            manual.foodCalories > 0.0 || manual.proteinG > 0.0 || manual.sodiumMg > 0.0 -> "Manual override"
            localNutrition.entries > 0 -> "Offline barcode database"
            health.nutritionRecords > 0 -> "Health Connect nutrition"
            else -> "Missing"
        }

    val deficitCalories: Double
        get() = finalBurnCalories - finalFoodCalories

    val burnSource: String
        get() = when {
            manual.manualBurnCalories != null -> "Manual override"
            health.totalCalories > 0.0 -> "Health Connect total calories"
            health.activeCalories > 0.0 -> "Health Connect active calories only"
            else -> "Missing"
        }

    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("date", DATE_FORMAT.format(date))
        put("steps", health.steps)
        put("distanceKm", health.distanceKm)
        put("activeCalories", health.activeCalories)
        put("totalCalories", health.totalCalories)
        put("exerciseSessions", health.exerciseSessions)
        put("exerciseMinutes", health.exerciseMinutes)
        put("nutritionCalories", health.nutritionCalories)
        put("nutritionProteinG", health.nutritionProteinG)
        put("nutritionSodiumMg", health.nutritionSodiumMg)
        put("nutritionRecords", health.nutritionRecords)
        put("healthConnectStatus", health.healthConnectStatus)
        put("localCalories", localNutrition.calories)
        put("localProteinG", localNutrition.proteinG)
        put("localSodiumMg", localNutrition.sodiumMg)
        put("localCarbsG", localNutrition.carbsG)
        put("localFatG", localNutrition.fatG)
        put("localSugarG", localNutrition.sugarG)
        put("localFiberG", localNutrition.fiberG)
        put("localSaturatedFatG", localNutrition.saturatedFatG)
        put("localEntries", localNutrition.entries)
        put("localExtras", org.json.JSONObject(localNutrition.extras))
        put("foodCalories", manual.foodCalories)
        put("proteinG", manual.proteinG)
        put("sodiumMg", manual.sodiumMg)
        put("manualBurnCalories", manual.manualBurnCalories ?: org.json.JSONObject.NULL)
        put("notes", manual.notes)
        put("savedAtEpochMs", savedAtEpochMs)
    }

    companion object {
        fun fromJson(json: org.json.JSONObject): DailyReport {
            val manualBurn = if (json.isNull("manualBurnCalories")) null else json.optDouble("manualBurnCalories")
            val extrasJson = json.optJSONObject("localExtras")
            val extras = if (extrasJson == null) emptyMap() else extrasJson.keys().asSequence().associateWith { extrasJson.optString(it) }
            return DailyReport(
                date = LocalDate.parse(json.getString("date"), DATE_FORMAT),
                health = HealthSummary(
                    steps = json.optLong("steps", 0L),
                    distanceKm = json.optDouble("distanceKm", 0.0),
                    activeCalories = json.optDouble("activeCalories", 0.0),
                    totalCalories = json.optDouble("totalCalories", 0.0),
                    exerciseSessions = json.optInt("exerciseSessions", 0),
                    exerciseMinutes = json.optLong("exerciseMinutes", 0L),
                    nutritionCalories = json.optDouble("nutritionCalories", 0.0),
                    nutritionProteinG = json.optDouble("nutritionProteinG", 0.0),
                    nutritionSodiumMg = json.optDouble("nutritionSodiumMg", 0.0),
                    nutritionRecords = json.optInt("nutritionRecords", 0),
                    healthConnectStatus = json.optString("healthConnectStatus", "Loaded from local storage")
                ),
                localNutrition = LocalNutritionSummary(
                    calories = json.optDouble("localCalories", 0.0),
                    proteinG = json.optDouble("localProteinG", 0.0),
                    sodiumMg = json.optDouble("localSodiumMg", 0.0),
                    carbsG = json.optDouble("localCarbsG", 0.0),
                    fatG = json.optDouble("localFatG", 0.0),
                    sugarG = json.optDouble("localSugarG", 0.0),
                    fiberG = json.optDouble("localFiberG", 0.0),
                    saturatedFatG = json.optDouble("localSaturatedFatG", 0.0),
                    entries = json.optInt("localEntries", 0),
                    extras = extras
                ),
                manual = ManualEntry(
                    foodCalories = json.optDouble("foodCalories", 0.0),
                    proteinG = json.optDouble("proteinG", 0.0),
                    sodiumMg = json.optDouble("sodiumMg", 0.0),
                    manualBurnCalories = manualBurn,
                    notes = json.optString("notes", "")
                ),
                savedAtEpochMs = json.optLong("savedAtEpochMs", System.currentTimeMillis())
            )
        }
    }
}

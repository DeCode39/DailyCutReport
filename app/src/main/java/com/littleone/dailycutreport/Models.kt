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
            health.nutritionCalories > 0.0 -> health.nutritionCalories
            else -> 0.0
        }

    val finalProteinG: Double
        get() = when {
            manual.proteinG > 0.0 -> manual.proteinG
            health.nutritionProteinG > 0.0 -> health.nutritionProteinG
            else -> 0.0
        }

    val finalSodiumMg: Double
        get() = when {
            manual.sodiumMg > 0.0 -> manual.sodiumMg
            health.nutritionSodiumMg > 0.0 -> health.nutritionSodiumMg
            else -> 0.0
        }

    val nutritionSource: String
        get() = when {
            manual.foodCalories > 0.0 || manual.proteinG > 0.0 || manual.sodiumMg > 0.0 -> "Manual food entries"
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

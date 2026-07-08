package com.littleone.dailycutreport

import android.content.Context
import org.json.JSONObject

class LegacyReportImporter(
    context: Context,
    private val dao: NutritionDao
) {
    private val prefs = context.getSharedPreferences("daily_reports", Context.MODE_PRIVATE)

    suspend fun importIfNeeded() {
        if (dao.metadata(IMPORT_KEY) == "complete") return
        val reports = prefs.all.mapNotNull { (date, value) ->
            val raw = value as? String ?: return@mapNotNull null
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@mapNotNull null
            DailyReportEntity(
                date = date,
                steps = json.optLong("steps"),
                distanceKm = json.optDouble("distanceKm", 0.0),
                activeCalories = json.optDouble("activeCalories", 0.0),
                totalCalories = json.optDouble("totalCalories", 0.0),
                exerciseSessions = json.optInt("exerciseSessions"),
                exerciseMinutes = json.optLong("exerciseMinutes"),
                nutritionCalories = json.optDouble("nutritionCalories", 0.0),
                nutritionProteinG = json.optDouble("nutritionProteinG", 0.0),
                nutritionSodiumMg = json.optDouble("nutritionSodiumMg", 0.0),
                nutritionRecords = json.optInt("nutritionRecords"),
                healthConnectStatus = json.optString("healthConnectStatus", "Imported from legacy storage"),
                manualFoodCalories = json.optDouble("foodCalories").takeIf { it > 0.0 },
                manualProteinG = json.optDouble("proteinG").takeIf { it > 0.0 },
                manualSodiumMg = json.optDouble("sodiumMg").takeIf { it > 0.0 },
                manualBurnCalories = if (json.isNull("manualBurnCalories")) null else json.optDouble("manualBurnCalories"),
                notes = json.optString("notes"),
                savedAtEpochMs = json.optLong("savedAtEpochMs", System.currentTimeMillis())
            )
        }
        dao.importLegacyReports(reports)
    }

    companion object {
        const val IMPORT_KEY = "legacy_shared_preferences_import_v1"
    }
}

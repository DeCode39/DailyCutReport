package com.littleone.dailycutreport

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class DailyReportJsonTest {
    @Test fun emitsStructuredRoundedBarcodeFreeReport() {
        val date = LocalDate.of(2026, 8, 12)
        val state = TodayUiState(
            report = DailyReport(
                date = date,
                health = HealthSummary(steps = 1234, totalCalories = 2400.4, activeCalories = 500.2),
                nutrition = NutritionSummary(calories = 97.999999999996, proteinG = 12.345, entries = 1)
            ),
            logs = listOf(FoodLogSnapshot(
                date = date,
                barcode = "secret-barcode",
                productName = "Sample",
                quantity = 1.5,
                caloriesPerServing = 65.333333,
                proteinGPerServing = 8.23
            ))
        )

        val text = DailyReportJson.encode(state)
        val json = JSONObject(text)
        assertEquals(2, json.getInt("schemaVersion"))
        assertEquals(98L, json.getJSONObject("nutrition").getLong("caloriesKcal"))
        assertEquals(2302L, json.getJSONObject("energy").getLong("burnMinusIntakeKcal"))
        assertFalse(text.contains("secret-barcode"))
        assertFalse(text.contains("finalBurn"))
        assertTrue(text.contains("foodGroups"))
    }
}

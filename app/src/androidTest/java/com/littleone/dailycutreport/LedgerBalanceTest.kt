package com.littleone.dailycutreport

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class LedgerBalanceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingReadingsAreNotPresentedAsZeroAndRecordedZeroIsValid() {
        val report = mutableStateOf(DailyReport(LocalDate.of(2026, 1, 1)))
        compose.setContent { DailyCutTheme { Box(Modifier.width(320.dp)) { LedgerBalance(TodayUiState(report = report.value)) } } }
        compose.onNodeWithText("Burn unavailable for this date").assertIsDisplayed()
        compose.runOnIdle { report.value = report.value.copy(health = HealthSummary(totalCalories = 1800.0)) }
        compose.onNodeWithText("No intake recorded for this date").assertIsDisplayed()
        compose.runOnIdle { report.value = report.value.copy(nutrition = NutritionSummary(entries = 1, calories = 0.0)) }
        compose.onNodeWithText("${formatCalories(1800.0)}  kcal").assertIsDisplayed()
        compose.runOnIdle { report.value = report.value.copy(nutrition = NutritionSummary(entries = 1, calories = 2000.0)) }
        compose.onNodeWithText("PROJECTED SURPLUS").assertIsDisplayed()
        compose.onNodeWithText("${formatCalories(200.0)}  kcal").assertIsDisplayed()
    }
}

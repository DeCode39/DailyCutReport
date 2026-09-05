package com.littleone.dailycutreport

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class GoalAssistantTest {
    private val profile = GoalAssistantProfile(30, 175.0, 80.0, GoalEquationSex.MALE, GoalActivity.LIGHT)
    @Test fun suggestionsPreserveUserLimitsAndMode() {
        val current = UserGoals(mode = GoalMode.DEFICIT, sodiumMg = 1700.0, sugarG = 35.0)
        val result = NutritionGoalEngine.suggest(profile, current, historicalBurn = 2600.0).goals
        assertEquals(2150.0, result.calories, 0.001)
        assertEquals(128.0, result.proteinG, 0.001)
        assertEquals(1700.0, result.sodiumMg, 0.0)
        assertEquals(35.0, result.sugarG, 0.0)
        assertEquals(GoalMode.DEFICIT, result.mode)
        assertEquals(2150.0, result.proteinG * 4 + result.carbsG * 4 + result.fatG * 9, 0.001)
    }
    @Test fun locksAndRecentWeightAreRespected() {
        val result = NutritionGoalEngine.suggest(profile.copy(locks = setOf(SuggestedTarget.FIBER, SuggestedTarget.PROTEIN)),
            UserGoals(proteinG = 100.0, fiberG = 19.0), recentWeightKg = 75.0).goals
        assertEquals(100.0, result.proteinG, 0.0)
        assertEquals(19.0, result.fiberG, 0.0)
        assertEquals(120.0, NutritionGoalEngine.suggest(profile, UserGoals(), 75.0).goals.proteinG, 0.0)
    }
    @Test fun historyPreservesOldTargetsAndRoundTrips() {
        val today = LocalDate.of(2026, 9, 5)
        val baseline = UserGoals(proteinG = 100.0)
        val state = GoalAssistantState(profile, baseline, mapOf(today to baseline.copy(proteinG = 130.0)), baseline, today)
        val decoded = GoalAssistantCodec.decode(GoalAssistantCodec.encode(state))
        assertEquals(state, decoded)
        assertEquals(100.0, decoded.goalsFor(today.minusDays(1), UserGoals()).proteinG, 0.0)
        assertEquals(130.0, decoded.goalsFor(today, UserGoals()).proteinG, 0.0)
    }
    @Test fun backupIncludesAssistantButOlderPayloadsRemainAccepted() {
        val payload = BackupPayload(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            goalAssistant = GoalAssistantState(profile = profile))
        assertEquals(payload, BackupJson.decode(BackupJson.encode(payload)))
        val old = org.json.JSONObject(BackupJson.encode(payload)).put("schemaVersion", 6).apply { remove("goalAssistant") }
        assertNull(BackupJson.decode(old.toString()).goalAssistant)
    }
    @Test fun rejectsUnsafeOrNonFiniteProfiles() {
        listOf(profile.copy(age = 17), profile.copy(weightKg = Double.NaN), profile.copy(weightKg = 40.0)).forEach {
            assertTrue(runCatching { NutritionGoalEngine.suggest(it, UserGoals()) }.isFailure)
        }
        assertTrue(runCatching { NutritionGoalEngine.suggest(profile, UserGoals(desiredDeficitCalories = 1400.0)) }.isFailure)
        assertTrue(runCatching { UserGoals(proteinG = Double.POSITIVE_INFINITY).requireValid() }.isFailure)
    }
}

package com.littleone.dailycutreport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupCryptoTest {
    @Test fun encryptedBackupRoundTripsAndRejectsWrongPassword() {
        val plain = "private local report".toByteArray()
        val encrypted = BackupCrypto.encrypt(plain, "correct horse".toCharArray())
        assertArrayEquals(plain, BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()))
        assertThrows(Throwable::class.java) { BackupCrypto.decrypt(encrypted, "wrong password".toCharArray()) }
    }

    @Test fun authenticatedBackupRejectsTampering() {
        val encrypted = BackupCrypto.encrypt("payload".toByteArray(), "correct horse".toCharArray())
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Throwable::class.java) { BackupCrypto.decrypt(encrypted, "correct horse".toCharArray()) }
    }

    @Test fun currentSchemaRoundTripIncludesGoalsPricingAndPlannerSettings() {
        val payload = BackupPayload(
            products = listOf(ProductEntity(
                "meal", name = "Meal", purchasePriceMicros = 12_000_000L, purchaseUnitServings = 2.0,
                quantityMode = QuantityMode.SERVING_AND_VOLUME.name, measurePerServing = 300.0,
                preferredLogUnit = QuantityUnit.MILLILITERS.name,
                plannerItemType = PlannerItemType.DRINK.name, alwaysIncludeInPlanner = true,
                fixedPurchaseUnits = 4
            )),
            productExtras = emptyList(), reports = emptyList(), foodLogs = listOf(DailyFoodLogEntity(
                id = 1, date = "2026-01-02", productId = "meal", productName = "Meal",
                actualPaidTotalMicros = 10_000_000L, excludeCostFromBudget = true,
                quantityMode = QuantityMode.SERVING_AND_VOLUME.name, measurePerServing = 300.0,
                enteredUnit = QuantityUnit.MILLILITERS.name, enteredAmount = 600.0,
                catalogEstimatedTotalMicros = 12_000_000L,
                mealId = "meal-group", mealName = "Lunch"
            )), dailyExtras = emptyList(),
            goals = UserGoals(currencyCode = "JPY", dailyBudgetMicros = 2_000_000_000L).toEntity()
        )
        val decoded = BackupJson.decode(BackupJson.encode(payload))
        assertEquals(12_000_000L, decoded.products.single().purchasePriceMicros)
        assertEquals(PlannerItemType.DRINK.name, decoded.products.single().plannerItemType)
        assertEquals(true, decoded.products.single().alwaysIncludeInPlanner)
        assertEquals(4, decoded.products.single().fixedPurchaseUnits)
        assertEquals(QuantityMode.SERVING_AND_VOLUME.name, decoded.products.single().quantityMode)
        assertEquals(300.0, decoded.products.single().measurePerServing!!, 0.0)
        assertEquals(QuantityUnit.MILLILITERS.name, decoded.foodLogs.single().enteredUnit)
        assertEquals(600.0, decoded.foodLogs.single().enteredAmount, 0.0)
        assertEquals(true, decoded.foodLogs.single().excludeCostFromBudget)
        assertEquals("meal-group", decoded.foodLogs.single().mealId)
        assertEquals("Lunch", decoded.foodLogs.single().mealName)
        assertEquals("JPY", decoded.goals.currencyCode)
    }

    @Test fun schemaThreeRoundTripIncludesHealthProfileWeightsAndWalking() {
        val payload = BackupPayload(
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            healthProfile = HealthProfile(WeightUnit.LB, 70.0).toEntity(),
            weights = listOf(WeightEntry("manual-2026-07-17", java.time.LocalDate.of(2026, 7, 17), 1L, 75.0, WeightSource.MANUAL).toEntity()),
            walkingSessions = listOf(WalkingSessionSample("walk", java.time.LocalDate.of(2026, 7, 17), 2L, 30.0, 3500, 2.5, 140.0).toEntity())
        )
        val decoded = BackupJson.decode(BackupJson.encode(payload))
        assertEquals("LB", decoded.healthProfile.weightUnit)
        assertEquals(75.0, decoded.weights.single().weightKg, 0.0)
        assertEquals(3500L, decoded.walkingSessions.single().steps)
    }

    @Test fun schemaOneBackupUsesSafeGoalAndPriceDefaults() {
        val decoded = BackupJson.decode("""{"schemaVersion":1,"products":[],"productExtras":[],"dailyReports":[],"foodLogs":[],"dailyExtras":[],"settings":{}}""")
        assertEquals(UserGoalsEntity(), decoded.goals)
    }
}

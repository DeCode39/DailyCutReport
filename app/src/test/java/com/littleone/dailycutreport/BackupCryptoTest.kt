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

    @Test fun schemaTwoRoundTripIncludesGoalsAndPricing() {
        val payload = BackupPayload(
            products = listOf(ProductEntity(
                "meal", name = "Meal", purchasePriceMicros = 12_000_000L, purchaseUnitServings = 2.0,
                plannerItemType = PlannerItemType.DRINK.name, alwaysIncludeInPlanner = true
            )),
            productExtras = emptyList(), reports = emptyList(), foodLogs = listOf(DailyFoodLogEntity(
                id = 1, date = "2026-01-02", productId = "meal", productName = "Meal",
                actualPaidTotalMicros = 10_000_000L, excludeCostFromBudget = true
            )), dailyExtras = emptyList(),
            goals = UserGoals(currencyCode = "JPY", dailyBudgetMicros = 2_000_000_000L).toEntity()
        )
        val decoded = BackupJson.decode(BackupJson.encode(payload))
        assertEquals(12_000_000L, decoded.products.single().purchasePriceMicros)
        assertEquals(PlannerItemType.DRINK.name, decoded.products.single().plannerItemType)
        assertEquals(true, decoded.products.single().alwaysIncludeInPlanner)
        assertEquals(true, decoded.foodLogs.single().excludeCostFromBudget)
        assertEquals("JPY", decoded.goals.currencyCode)
    }

    @Test fun schemaOneBackupUsesSafeGoalAndPriceDefaults() {
        val decoded = BackupJson.decode("""{"schemaVersion":1,"products":[],"productExtras":[],"dailyReports":[],"foodLogs":[],"dailyExtras":[],"settings":{}}""")
        assertEquals(UserGoalsEntity(), decoded.goals)
    }
}

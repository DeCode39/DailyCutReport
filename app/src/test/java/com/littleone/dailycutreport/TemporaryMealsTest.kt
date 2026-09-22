package com.littleone.dailycutreport

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class TemporaryMealsTest {
    @Test fun duplicateDetachesIdentityAndExpiresExactlyWithoutModifyingOriginal() {
        val original = ProductEntity("original", barcode = "12345", name = "Rice", calories = 123.4,
            favorite = true, alwaysIncludeInPlanner = true)
        val copy = original.asOneTimeCopy(1000)
        assertNotEquals(original.productId, copy.productId)
        assertNull(copy.barcode)
        assertEquals(original.calories, copy.calories, 0.0)
        assertFalse(copy.includeInPlanner)
        assertTrue(copy.isAvailableAt(999 + TEMPORARY_MEAL_LIFETIME_MS))
        assertFalse(copy.isAvailableAt(1000 + TEMPORARY_MEAL_LIFETIME_MS))
        assertTrue(original.isAvailableAt(Long.MAX_VALUE))
        assertTrue(copy.copy(expiresAtEpochMs = null).isAvailableAt(Long.MAX_VALUE))
    }
    @Test fun backupRetainsExpiryAndLogsAndOlderSchemasDefaultPermanent() {
        val p = ProductEntity("temporary", name = "Lunch").asOneTimeCopy(1000)
        val log = DailyFoodLogEntity(id = 1, date = "2026-09-23", productId = p.productId, productName = p.name)
        val payload = BackupPayload(listOf(p), emptyList(), emptyList(), listOf(log), emptyList())
        val encoded = BackupJson.encode(payload)
        val decoded = BackupJson.decode(encoded)
        assertEquals(payload, decoded)
        assertNull(BackupJson.decode(encoded.replace("\"schemaVersion\":8", "\"schemaVersion\":7")).products.single().expiresAtEpochMs)
    }
    @Test fun temporaryDraftSurvivesRecoveryWithoutOcrData() = runBlocking {
        val pending = PendingProductDraft(ProductEditorDraft(name = "Lunch", brand = "Cafe", oneTimeMeal = true), LocalDate.now())
        assertEquals(pending, ProductDraftCodec.decode(ProductDraftCodec.encode(pending)) { null })
    }
}

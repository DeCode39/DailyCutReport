package com.littleone.dailycutreport

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class CheckoutTransactionTest {
    private lateinit var database: NutritionDatabase
    private val date = LocalDate.of(2026, 10, 4)
    private val first = ProductEntity("first", name = "Rice", calories = 200.0)
    private val second = ProductEntity("second", name = "Beans", calories = 300.0)
    private val cart = BulkDraft(date, listOf(BulkDraftItem(first), BulkDraftItem(second)), "Shop", "15.000001", true)
    @Before fun setup() { database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), NutritionDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = database.close()
    private suspend fun seed(): NutritionDao = database.nutritionDao().also {
        it.saveProductWithExtras(first, listOf(ProductExtraNutrientEntity("first", "Potassium", 12.0, "mg")))
        it.saveProductWithExtras(second, emptyList())
        it.upsertMetadata(AppMetadataEntity(CartDraftCodec.METADATA_KEY, CartDraftCodec.encode(cart)))
    }
    private fun selections() = listOf(BulkLogSelection("first", 1.0), BulkLogSelection("second", 1.0))

    @Test fun checkoutAndUndoPersistCartAtomicallyAndPreserveOtherHistory() = runBlocking {
        val dao = seed()
        val unrelated = dao.addProductToDate(date.toString(), first, 3.0, emptyList()).addedLogs.single()
        val committed = dao.checkoutCart(date.toString(), cart.label, selections(), 15_000_001, true)
        assertEquals(2, committed.addedLogs.size)
        assertEquals(15_000_001L, committed.addedLogs.sumOf { it.actualPaidTotalMicros ?: 0 })
        assertTrue(CartDraftCodec.decode(requireNotNull(dao.metadata(CartDraftCodec.METADATA_KEY))) { dao.productById(it) }.items.isEmpty())
        val extraId = committed.addedLogs.first { it.productId == "first" }.id
        assertEquals(1, dao.dailyExtrasForLog(extraId).size)
        dao.undoCheckout(committed.addedLogs, cart)
        assertEquals(listOf(unrelated), dao.foodLogsForDate(date.toString()))
        assertTrue(dao.dailyExtrasForLog(extraId).isEmpty())
        // Product saves update catalog timestamps. Compare the persisted cart document,
        // not stale product objects captured before those saves.
        assertEquals(CartDraftCodec.encode(cart), dao.metadata(CartDraftCodec.METADATA_KEY))
        assertTrue(runCatching { dao.undoCheckout(committed.addedLogs, cart) }.isFailure)
        assertEquals(listOf(unrelated), dao.foodLogsForDate(date.toString()))
    }

    @Test fun missingOrChangedFoodRollsBackTheWholeOperation() = runBlocking {
        val dao = seed()
        val original = dao.metadata(CartDraftCodec.METADATA_KEY)
        assertTrue(runCatching { dao.checkoutCart(date.toString(), "Shop", selections() + BulkLogSelection("gone", 1.0), 0, false) }.isFailure)
        assertTrue(dao.foodLogsForDate(date.toString()).isEmpty())
        assertEquals(original, dao.metadata(CartDraftCodec.METADATA_KEY))
        val committed = dao.checkoutCart(date.toString(), "Shop", selections(), 0, false)
        val changed = committed.addedLogs.last().copy(quantity = 2.0)
        dao.updateFoodLog(changed)
        assertTrue(runCatching { dao.undoCheckout(committed.addedLogs, cart) }.isFailure)
        assertEquals(2, dao.foodLogsForDate(date.toString()).size)
        assertEquals(changed, dao.foodLogById(changed.id))
    }

    @Test fun invalidUnitsDuplicateProductsAndFutureDatesCannotWriteLogs() = runBlocking {
        val dao = seed()
        for (entries in listOf(listOf(BulkLogSelection("first", Double.NaN)),
            listOf(BulkLogSelection("first", 1.0, "GRAMS", 100.0)), selections() + selections().first())) {
            assertTrue(runCatching { dao.checkoutCart(date.toString(), "Shop", entries, 0, false) }.isFailure)
        }
        assertTrue(runCatching { dao.checkoutCart(LocalDate.now().plusDays(1).toString(), "Shop", selections(), 0, false) }.isFailure)
        assertTrue(dao.foodLogsForDate(date.toString()).isEmpty())
        assertEquals(CartDraftCodec.encode(cart), dao.metadata(CartDraftCodec.METADATA_KEY))
    }
}

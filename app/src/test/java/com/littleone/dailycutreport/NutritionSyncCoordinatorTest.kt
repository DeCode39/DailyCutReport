package com.littleone.dailycutreport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class NutritionSyncCoordinatorTest {
    private lateinit var database: NutritionDatabase
    private lateinit var health: FakeHealthDataSource

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NutritionDatabase::class.java)
            .allowMainThreadQueries().build()
        health = FakeHealthDataSource()
    }

    @After fun tearDown() = database.close()

    @Test fun repeatedSyncUpsertsCurrentIdsAndDeletesOnlyStaleIds() = runBlocking {
        val dao = database.nutritionDao()
        val date = LocalDate.of(2026, 7, 10)
        val product = ProductEntity(productId = "meal", name = "Meal", calories = 100.0)
        dao.saveProductWithExtras(product, emptyList())
        dao.addProductToDate(date.toString(), product, 1.0, emptyList())
        val coordinator = NutritionSyncCoordinator(dao, health) { 100L }

        coordinator.sync(date).getOrThrow()
        val id = health.calls.single().logs.single().healthClientRecordId
        dao.deleteFoodLogSnapshot(dao.foodLogsForDate(date.toString()).single().id)
        coordinator.sync(date).getOrThrow()

        assertEquals(setOf(id), health.calls.last().priorIds)
        assertTrue(health.calls.last().logs.isEmpty())
        assertTrue(health.calls.last().version > health.calls.first().version)
    }

    @Test fun failedWriteRemainsPendingForForegroundRetry() = runBlocking {
        val date = LocalDate.of(2026, 7, 10)
        val coordinator = NutritionSyncCoordinator(database.nutritionDao(), health)
        health.failWrites = true

        assertTrue(coordinator.sync(date).isFailure)
        health.failWrites = false
        coordinator.retryPending()

        assertEquals(2, health.calls.size)
        assertTrue(coordinator.status()?.startsWith("Synced") == true)
    }
}

private data class HealthWriteCall(
    val logs: List<FoodLogSnapshot>,
    val priorIds: Set<String>,
    val version: Long
)

private class FakeHealthDataSource : HealthDataSource {
    val calls = mutableListOf<HealthWriteCall>()
    var failWrites = false
    override fun availabilityMessage() = "Available"
    override fun isAvailable() = true
    override suspend fun hasCorePermissions() = true
    override suspend fun hasNutritionPermission() = true
    override suspend fun hasNutritionWritePermission() = true
    override suspend fun readDailySummary(date: LocalDate) = HealthSummary()
    override suspend fun writeNutrition(
        date: LocalDate,
        logs: List<FoodLogSnapshot>,
        priorClientRecordIds: Set<String>,
        clientRecordVersion: Long
    ): HealthWriteSummary {
        calls += HealthWriteCall(logs, priorClientRecordIds, clientRecordVersion)
        if (failWrites) error("temporary failure")
        return HealthWriteSummary(logs.size, date)
    }
}

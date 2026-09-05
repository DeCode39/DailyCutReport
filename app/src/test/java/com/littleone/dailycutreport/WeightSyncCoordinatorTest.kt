package com.littleone.dailycutreport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class WeightSyncCoordinatorTest {
    private lateinit var db: NutritionDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NutritionDatabase::class.java).allowMainThreadQueries().build() }
    @After fun close() = db.close()
    @Test fun onlyManualRecordsExportAndDeletedRecordsAreRemoved() = runBlocking {
        val dao = db.nutritionDao()
        val health = WeightGateway()
        val sync = WeightSyncCoordinator(dao, health)
        val entry = WeightEntry("manual:one", LocalDate.now(), 1000, 80.0, WeightSource.MANUAL)
        dao.upsertWeightEntries(listOf(entry.toEntity(), entry.copy(entryId = "hc:one", source = WeightSource.HEALTH_CONNECT).toEntity()))
        sync.sync()
        assertEquals(listOf(entry), health.entries)
        val version = health.version
        sync.sync()
        assertEquals("Unchanged data must not be exported again", version, health.version)
        dao.deleteWeightEntry(entry.entryId)
        sync.sync()
        assertEquals(setOf(entry.weightClientId), health.stale)
        assertTrue(health.entries.isEmpty())
        assertTrue(health.version > version)
    }
    @Test fun failedAttemptRetainsIdsForDeletionOnRetry() = runBlocking {
        val dao = db.nutritionDao()
        val health = WeightGateway()
        val sync = WeightSyncCoordinator(dao, health)
        val entry = WeightEntry("manual:one", LocalDate.now(), 1000, 80.0, WeightSource.MANUAL)
        dao.upsertWeightEntries(listOf(entry.toEntity()))
        health.fail = true
        sync.sync()
        assertTrue(dao.metadata(WeightSyncCoordinator.STATUS)!!.contains("pending"))
        dao.deleteWeightEntry(entry.entryId)
        health.fail = false
        sync.sync()
        assertEquals(setOf(entry.weightClientId), health.stale)
    }
    @Test fun missingPermissionIsQuietAndRetryable() = runBlocking {
        val health = WeightGateway().apply { permission = false }
        val sync = WeightSyncCoordinator(db.nutritionDao(), health)
        sync.sync()
        assertEquals(0L, health.version)
        assertTrue(db.nutritionDao().metadata(WeightSyncCoordinator.STATUS)!!.contains("permission required"))
        health.permission = true
        sync.sync()
        assertTrue(health.version > 0)
    }
}

private class WeightGateway : HealthDataSource {
    var permission = true
    var fail = false
    var entries = emptyList<WeightEntry>()
    var stale = emptySet<String>()
    var version = 0L
    override fun availabilityMessage() = "Available"
    override fun isAvailable() = true
    override suspend fun hasCorePermissions() = true
    override suspend fun hasNutritionPermission() = false
    override suspend fun hasNutritionWritePermission() = false
    override suspend fun writeNutrition(date: LocalDate, logs: List<FoodLogSnapshot>, priorClientRecordIds: Set<String>, clientRecordVersion: Long) = HealthWriteSummary(0, date)
    override suspend fun readDailySummary(date: LocalDate) = HealthSummary()
    override suspend fun hasWeightWritePermission() = permission
    override suspend fun writeWeights(entries: List<WeightEntry>, staleIds: Set<String>, version: Long) {
        this.entries = entries; stale = staleIds; this.version = version
        if (fail) error("failed")
    }
}

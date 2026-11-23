package com.littleone.dailycutreport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class RecentHealthRefreshTest {
    private lateinit var db: NutritionDatabase
    private lateinit var repository: DailyCutRepository
    private lateinit var health: RefreshGateway
    private val today get() = LocalDate.now()
    private val dao get() = db.nutritionDao()

    @Before fun setup() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, NutritionDatabase::class.java).allowMainThreadQueries().build()
        health = RefreshGateway()
        repository = DefaultDailyCutRepository(context, dao, health, LegacyReportImporter(context, dao),
            ProductCatalogImporter(context, dao), EncryptedAppBackupManager(context, dao))
        repository.initialize()
    }

    @After fun close() { db.close() }

    @Test fun firstForegroundRechecksYesterdayBeforeTodayAndRetriesMissingBurn() = runBlocking {
        val yesterday = today.minusDays(1)
        health.missingDailyBurn = true
        repository.refreshOnForeground().getOrThrow()
        health.missingDailyBurn = false
        repository.refreshOnForeground().getOrThrow()
        repository.refreshOnForeground().getOrThrow()
        assertEquals(listOf("day:$yesterday", "day:$today", "day:$yesterday", "day:$today", "day:$today"), health.calls)
    }

    @Test fun missingProviderMeasurementsKeepPriorBurnAndOptionalData() = runBlocking {
        val day = today.minusDays(1)
        dao.upsertDailyReport(DailyReportEntity(day.toString(), totalCalories = 2345.0, nutritionCalories = 1500.0, nutritionRecords = 1))
        dao.upsertWeightEntry(WeightEntryEntity("imported", day.toString(), 1234, 70.0, WeightSource.HEALTH_CONNECT.name))
        health.missingHistory = true
        repository.refreshRecentHealth().getOrThrow()
        assertEquals(2345.0, dao.dailyReport(day.toString())!!.totalCalories, 0.0)
        assertEquals(1, dao.allWeightEntries().size)
        val json = org.json.JSONObject(ReportJsonExporter(repository).export(day, false))
        assertEquals(1500, json.getJSONObject("effectiveIntake").getInt("caloriesKcal"))
        assertTrue(json.getJSONObject("activity").isNull("steps"))
        assertTrue(json.getJSONObject("activity").getString("source").contains("retained"))
    }

    @Test fun successfulRangeAlreadyCoversYesterdayAndSnapshotLabelsMissingAndImportedIntake() = runBlocking {
        repository.syncHealthHistory(force = true).getOrThrow()
        repository.refreshOnForeground().getOrThrow()
        assertEquals(listOf("history:${today.minusDays(29)}:$today", "day:$today"), health.calls)
        val end = today.minusDays(45)
        dao.upsertDailyReport(DailyReportEntity(end.toString(), nutritionRecords = 1, nutritionCalories = 1300.0))
        val week = org.json.JSONObject(ReportJsonExporter(repository).export(end, true)).getJSONArray("days")
        assertTrue(week.getJSONObject(0).getJSONObject("activity").isNull("updatedAt"))
        assertTrue(week.getJSONObject(0).getJSONObject("effectiveIntake").isNull("caloriesKcal"))
        val intake = week.getJSONObject(6).getJSONObject("effectiveIntake")
        assertEquals("health_connect", intake.getString("source"))
        assertEquals(1300, intake.getInt("caloriesKcal"))
    }

    @Test fun manualRefreshBypassesThrottleImportsExactly28DaysAndForecastsTodayLast() = runBlocking {
        val previous = today.minusDays(1)
        dao.upsertDailyReport(DailyReportEntity(previous.toString(), totalCalories = 1000.0, notes = "Keep note"))
        val old = BurnForecast(previous, 100.0, 1100.0, 900.0, 1200.0,
            BurnForecastSource.HISTORICAL_REMAINDER, BurnForecastConfidence.LOW, 1, 0)
        dao.upsertMetadata(AppMetadataEntity(burnForecastMetadataKey(previous), BurnForecastCodec.encode(old)))
        val untouched = DailyReportEntity(today.minusDays(28).toString(), totalCalories = 2100.0)
        dao.upsertDailyReport(untouched)
        val food = ProductEntity("test-food", name = "Test food", calories = 500.0, purchasePriceMicros = 20_000_000)
        dao.saveProductWithExtras(food, emptyList())
        val log = dao.addProductToDate(previous.toString(), food, 2.0, emptyList(), actualPaidTotalMicros = 15_000_000).addedLogs.single()
        dao.upsertWeightEntry(WeightEntryEntity("manual-test", previous.toString(),
            previous.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), 75.0, "MANUAL"))
        val weights = dao.allWeightEntries()
        dao.upsertMetadata(AppMetadataEntity("health_history_sync_day_v1", today.toString()))

        assertTrue(repository.refreshRecentHealth().isSuccess)
        assertEquals(listOf("history:${today.minusDays(27)}:$today", "day:$today"), health.calls)
        assertEquals(2400.0, dao.dailyReport(previous.toString())!!.totalCalories, 0.0)
        assertEquals("Keep note", dao.dailyReport(previous.toString())!!.notes)
        assertEquals(untouched, dao.dailyReport(untouched.date))
        assertEquals(log, dao.foodLogById(log.id))
        assertEquals(weights, dao.allWeightEntries())
        val pastForecast = repository.observeBurnForecast(previous).first()!!
        assertEquals(BurnForecastSource.ACTUAL, pastForecast.source)
        assertEquals(2400.0, pastForecast.estimatedFinalCalories!!, 0.0)
        val currentForecast = repository.observeBurnForecast(today).first()!!
        assertEquals(BurnForecastSource.HISTORICAL_REMAINDER, currentForecast.source)
        assertEquals(1200.0, currentForecast.liveBurnCalories!!, 0.0)
        assertEquals(currentForecast.estimatedFinalCalories!!, dao.dailyReport(today.toString())!!.totalCalories, 0.0)
        assertTrue(currentForecast.sampleDays >= 27)

        health.historicalBurn = 2500.0
        assertTrue(repository.refreshRecentHealth().isSuccess)
        assertEquals(2, health.calls.count { it.startsWith("history:") })
        assertEquals(2500.0, dao.dailyReport(previous.toString())!!.totalCalories, 0.0)
    }

    @Test fun missingPermissionFailureAndIncompleteImportsPreserveExistingDataAndAllowRetry() = runBlocking {
        val original = DailyReportEntity(today.minusDays(1).toString(), totalCalories = 2300.0)
        dao.upsertDailyReport(original)
        for (failure in listOf("permission", "unavailable", "provider", "incomplete")) {
            health.permissions = failure != "permission"
            health.available = failure != "unavailable"
            health.failHistory = failure == "provider"
            health.incomplete = failure == "incomplete"
            assertTrue(repository.refreshRecentHealth().isFailure)
            assertEquals(original, dao.dailyReport(original.date))
            assertNull(dao.metadata("health_history_sync_day_v1"))
        }
        health.incomplete = false
        assertTrue(repository.refreshRecentHealth().isSuccess)
        assertEquals(today.toString(), dao.metadata("health_history_sync_day_v1"))
    }

    @Test fun concurrentDailyRefreshWaitsForWholeWindowAndCancellationDoesNotMarkSuccess() = runBlocking {
        health.gate = CompletableDeferred()
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { repository.refreshRecentHealth() }
        cancelled.cancelAndJoin()
        assertNull(dao.metadata("health_history_sync_day_v1"))
        health.calls.clear()
        val first = async(start = CoroutineStart.UNDISPATCHED) { repository.refreshRecentHealth() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { repository.refreshHealth(today) }
        assertEquals(listOf("history:${today.minusDays(27)}:$today"), health.calls)
        health.gate!!.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(listOf("history:${today.minusDays(27)}:$today", "day:$today", "day:$today"), health.calls)
    }

    @Test fun bootstrapStillImports30DaysAndForegroundRefreshRemainsSingleDay() = runBlocking {
        assertTrue(repository.syncHealthHistory(force = false).isSuccess)
        assertTrue(repository.syncHealthHistory(force = false).isSuccess)
        assertTrue(repository.refreshHealth(today).isSuccess)
        assertEquals(listOf("history:${today.minusDays(29)}:$today", "day:$today"), health.calls)
    }

    @Test fun todayFailureIsReportedAfterHistoryUpdateAndNextManualRefreshCanRecover() = runBlocking {
        val original = DailyReportEntity(today.toString(), totalCalories = 2300.0)
        dao.upsertDailyReport(original)
        health.failToday = true
        val result = repository.refreshRecentHealth()
        assertTrue(result.exceptionOrNull()!!.message!!.contains("today's refresh failed"))
        assertEquals(2400.0, dao.dailyReport(today.minusDays(1).toString())!!.totalCalories, 0.0)
        assertEquals(original, dao.dailyReport(today.toString()))
        health.failToday = false
        assertTrue(repository.refreshRecentHealth().isSuccess)
    }
}

private class RefreshGateway : HealthDataSource {
    val calls = mutableListOf<String>()
    var permissions = true
    var available = true
    var failHistory = false
    var failToday = false
    var missingDailyBurn = false
    var missingHistory = false
    var incomplete = false
    var historicalBurn = 2400.0
    var gate: CompletableDeferred<Unit>? = null
    override fun availabilityMessage() = "Health Connect unavailable"
    override fun isAvailable() = available
    override suspend fun hasCorePermissions() = permissions
    override suspend fun hasNutritionPermission() = false
    override suspend fun hasNutritionWritePermission() = false
    override suspend fun readDailySummary(date: LocalDate): HealthSummary {
        calls += "day:$date"
        check(!failToday) { "Provider busy" }
        val noon = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant()
        return HealthSummary(totalCalories = if (missingDailyBurn) 0.0 else 1200.0,
            totalBurnMissing = missingDailyBurn, recordedThroughEpochMs = noon.toEpochMilli())
    }
    override suspend fun readHealthHistory(startDate: LocalDate, endDate: LocalDate): HealthHistoryImport {
        calls += "history:$startDate:$endDate"
        gate?.await()
        check(!failHistory) { "Provider busy" }
        val days = generateSequence(startDate) { it.plusDays(1).takeIf { next -> next <= endDate } }.toList()
        val selected = if (incomplete) days.dropLast(1) else days
        return HealthHistoryImport(selected.associateWith { HealthSummary(totalCalories = if (missingHistory) 0.0 else historicalBurn,
            totalBurnMissing = missingHistory, nutritionAccess = !missingHistory,
            missingActivityFields = if (missingHistory) setOf("steps") else emptySet(),
            recordedThroughEpochMs = Instant.now().toEpochMilli()) }, emptyList(), emptyList(), weightAccess = !missingHistory)
    }
    override suspend fun writeNutrition(date: LocalDate, logs: List<FoodLogSnapshot>, priorClientRecordIds: Set<String>, clientRecordVersion: Long) =
        HealthWriteSummary(logs.size, date)
}

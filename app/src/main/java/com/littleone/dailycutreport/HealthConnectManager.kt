package com.littleone.dailycutreport

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

internal data class HealthRecordWindow(
    val start: ZonedDateTime,
    val end: ZonedDateTime
)

internal fun FoodLogSnapshot.healthRecordWindow(zone: ZoneId): HealthRecordWindow {
    val logged = Instant.ofEpochMilli(loggedAt).atZone(zone)
    val localTime = if (logged.toLocalDate() == date) logged.toLocalTime() else LocalTime.NOON
    val safeTime = localTime.coerceAtMost(LocalTime.of(23, 58))
    val start = ZonedDateTime.of(date, safeTime, zone)
    return HealthRecordWindow(start, start.plusMinutes(1))
}

interface HealthDataSource {
    fun availabilityMessage(): String
    fun isAvailable(): Boolean
    suspend fun hasCorePermissions(): Boolean
    suspend fun hasNutritionPermission(): Boolean
    suspend fun hasNutritionWritePermission(): Boolean
    suspend fun hasWeightPermission(): Boolean = false
    suspend fun hasWeightWritePermission(): Boolean = false
    suspend fun writeWeights(entries: List<WeightEntry>, staleIds: Set<String>, version: Long) { error("Weight export unavailable") }
    suspend fun readDailySummary(date: LocalDate): HealthSummary
    suspend fun readHealthHistory(startDate: LocalDate, endDate: LocalDate): HealthHistoryImport =
        HealthHistoryImport(emptyMap(), emptyList(), emptyList())
    suspend fun writeNutrition(
        date: LocalDate,
        logs: List<FoodLogSnapshot>,
        priorClientRecordIds: Set<String>,
        clientRecordVersion: Long
    ): HealthWriteSummary
}

data class HealthHistoryImport(
    val dailySummaries: Map<LocalDate, HealthSummary>,
    val weights: List<WeightEntry>,
    val walkingSessions: List<WalkingSessionSample>
)

class HealthConnectManager(private val context: Context) : HealthDataSource {
    companion object {
        val CORE_PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        )
        val NUTRITION_PERMISSION: String = HealthPermission.getReadPermission(NutritionRecord::class)
        val NUTRITION_WRITE_PERMISSION: String = HealthPermission.getWritePermission(NutritionRecord::class)
        val WEIGHT_PERMISSION: String = HealthPermission.getReadPermission(WeightRecord::class)
        val WEIGHT_WRITE_PERMISSION: String = HealthPermission.getWritePermission(WeightRecord::class)
        val PERMISSIONS: Set<String> = CORE_PERMISSIONS + NUTRITION_PERMISSION
    }

    private val client: HealthConnectClient? by lazy {
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null
    }

    override fun availabilityMessage(): String = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "Health Connect available"
        HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect unavailable on this device"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect provider update required"
        else -> "Unknown Health Connect status"
    }

    override fun isAvailable(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    override suspend fun hasCorePermissions(): Boolean {
        val hc = client ?: return false
        return hc.permissionController.getGrantedPermissions().containsAll(CORE_PERMISSIONS)
    }

    override suspend fun hasNutritionPermission(): Boolean = client?.permissionController
        ?.getGrantedPermissions()?.contains(NUTRITION_PERMISSION) == true

    override suspend fun hasNutritionWritePermission(): Boolean = client?.permissionController
        ?.getGrantedPermissions()?.contains(NUTRITION_WRITE_PERMISSION) == true

    override suspend fun hasWeightPermission(): Boolean = client?.permissionController
        ?.getGrantedPermissions()?.contains(WEIGHT_PERMISSION) == true

    override suspend fun hasWeightWritePermission(): Boolean = client?.permissionController
        ?.getGrantedPermissions()?.contains(WEIGHT_WRITE_PERMISSION) == true

    override suspend fun writeWeights(entries: List<WeightEntry>, staleIds: Set<String>, version: Long) {
        val hc = requireNotNull(client)
        check(hasWeightWritePermission()) { "Weight export permission required" }
        entries.chunked(200).forEach { batch ->
            hc.insertRecords(batch.map { entry ->
                val instant = Instant.ofEpochMilli(entry.recordedAtEpochMs)
                WeightRecord(
                    time = instant, zoneOffset = ZoneId.systemDefault().rules.getOffset(instant),
                    weight = Mass.kilograms(entry.weightKg),
                    metadata = Metadata.manualEntry(clientRecordId = entry.weightClientId, clientRecordVersion = version)
                )
            })
        }
        staleIds.toList().chunked(200).forEach {
            hc.deleteRecords(WeightRecord::class, recordIdsList = emptyList(), clientRecordIdsList = it)
        }
    }

    override suspend fun readDailySummary(date: LocalDate): HealthSummary {
        val hc = client ?: return HealthSummary(healthConnectStatus = availabilityMessage())
        val granted = hc.permissionController.getGrantedPermissions()
        if (!granted.containsAll(CORE_PERMISSIONS)) {
            return HealthSummary(healthConnectStatus = "Health Connect activity permission not granted")
        }

        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        val now = Instant.now()
        val isToday = date == now.atZone(zone).toLocalDate()
        val end = if (isToday && now < dayEnd) now else dayEnd
        val timeRange = TimeRangeFilter.between(start, end)
        val aggregate = hc.aggregate(
            AggregateRequest(
                metrics = setOf(
                    StepsRecord.COUNT_TOTAL,
                    DistanceRecord.DISTANCE_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                    TotalCaloriesBurnedRecord.ENERGY_TOTAL
                ),
                timeRangeFilter = timeRange
            )
        )
        val providerFullDayCalories = if (isToday && end < dayEnd) {
            hc.aggregate(
                AggregateRequest(
                    metrics = setOf(TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(start, dayEnd)
                )
            )[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
        } else null

        val sessions = readAllExerciseSessions(hc, timeRange)
        val nutritionGranted = NUTRITION_PERMISSION in granted
        val nutritionRecords = if (nutritionGranted) readAllNutritionRecords(hc, timeRange) else emptyList()
        val exerciseMinutes = sessions.sumOf { session ->
            Duration.between(session.startTime, session.endTime).toMinutes().coerceAtLeast(0)
        }
        val nutritionStatus = when {
            !nutritionGranted -> "Activity loaded; Health Connect nutrition is optional and not granted"
            nutritionRecords.isEmpty() -> "Activity loaded; no Health Connect nutrition records found"
            else -> "Loaded from Health Connect, including ${nutritionRecords.size} nutrition record(s)"
        }

        return HealthSummary(
            steps = aggregate[StepsRecord.COUNT_TOTAL] ?: 0L,
            distanceKm = aggregate[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0,
            activeCalories = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
            totalCalories = aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
            exerciseSessions = sessions.size,
            exerciseMinutes = exerciseMinutes,
            nutritionCalories = nutritionRecords.sumOf { it.energy?.inKilocalories ?: 0.0 },
            nutritionProteinG = nutritionRecords.sumOf { it.protein?.inGrams ?: 0.0 },
            nutritionSodiumMg = nutritionRecords.sumOf { it.sodium?.inMilligrams ?: 0.0 },
            nutritionRecords = nutritionRecords.size,
            healthConnectStatus = nutritionStatus,
            providerFullDayCalories = providerFullDayCalories,
            recordedThroughEpochMs = end.toEpochMilli()
        )
    }

    override suspend fun readHealthHistory(startDate: LocalDate, endDate: LocalDate): HealthHistoryImport {
        require(!endDate.isBefore(startDate)) { "History end date must not precede its start date." }
        val hc = client ?: error(availabilityMessage())
        val granted = hc.permissionController.getGrantedPermissions()
        require(granted.containsAll(CORE_PERMISSIONS)) { "Health Connect activity permission not granted" }
        val zone = ZoneId.systemDefault()
        val range = TimeRangeFilter.between(
            startDate.atStartOfDay(zone).toInstant(),
            endDate.plusDays(1).atStartOfDay(zone).toInstant()
        )
        val sessions = readAllExerciseSessions(hc, range)
        val nutritionByDate = if (NUTRITION_PERMISSION in granted) {
            readAllNutritionRecords(hc, range).groupBy { record ->
                val offset = record.startZoneOffset ?: zone.rules.getOffset(record.startTime)
                record.startTime.atOffset(offset).toLocalDate()
            }
        } else emptyMap()
        val daily = generateSequence(startDate) { current ->
            current.plusDays(1).takeIf { !it.isAfter(endDate) }
        }.associateWith { date ->
            val dayStart = date.atStartOfDay(zone).toInstant()
            val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
            val aggregate = hc.aggregate(
                AggregateRequest(
                    metrics = setOf(
                        StepsRecord.COUNT_TOTAL,
                        DistanceRecord.DISTANCE_TOTAL,
                        ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,
                        TotalCaloriesBurnedRecord.ENERGY_TOTAL
                    ),
                    timeRangeFilter = TimeRangeFilter.between(dayStart, dayEnd)
                )
            )
            val daySessions = sessions.filter { it.startTime >= dayStart && it.startTime < dayEnd }
            val dayNutrition = nutritionByDate[date].orEmpty()
            HealthSummary(
                steps = aggregate[StepsRecord.COUNT_TOTAL] ?: 0L,
                distanceKm = aggregate[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0,
                activeCalories = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
                totalCalories = aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
                exerciseSessions = daySessions.size,
                exerciseMinutes = daySessions.sumOf {
                    Duration.between(it.startTime, it.endTime).toMinutes().coerceAtLeast(0)
                },
                nutritionCalories = dayNutrition.sumOf { it.energy?.inKilocalories ?: 0.0 },
                nutritionProteinG = dayNutrition.sumOf { it.protein?.inGrams ?: 0.0 },
                nutritionSodiumMg = dayNutrition.sumOf { it.sodium?.inMilligrams ?: 0.0 },
                nutritionRecords = dayNutrition.size,
                healthConnectStatus = if (NUTRITION_PERMISSION in granted) {
                    "Activity and nutrition history loaded from Health Connect"
                } else "Activity history loaded from Health Connect"
            )
        }
        val weights = if (WEIGHT_PERMISSION in granted) {
            readAllWeights(hc, range).filterNot { record ->
                record.metadata.dataOrigin.packageName == context.packageName &&
                    record.metadata.clientRecordId?.startsWith("dailycut-weight-") == true
            }.map { record ->
                val offset = record.zoneOffset ?: zone.rules.getOffset(record.time)
                WeightEntry(
                    entryId = "health-connect-${record.metadata.id}",
                    date = record.time.atOffset(offset).toLocalDate(),
                    recordedAtEpochMs = record.time.toEpochMilli(),
                    weightKg = record.weight.inKilograms,
                    source = WeightSource.HEALTH_CONNECT
                )
            }
        } else emptyList()
        val walking = buildList {
            sessions.filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WALKING }.forEach { session ->
                val durationMinutes = Duration.between(session.startTime, session.endTime).toMillis() / 60_000.0
                if (durationMinutes <= 0.0) return@forEach
                val aggregate = hc.aggregate(
                    AggregateRequest(
                        metrics = setOf(
                            StepsRecord.COUNT_TOTAL,
                            DistanceRecord.DISTANCE_TOTAL,
                            ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
                        ),
                        timeRangeFilter = TimeRangeFilter.between(session.startTime, session.endTime)
                    )
                )
                val offset = session.startZoneOffset ?: zone.rules.getOffset(session.startTime)
                add(WalkingSessionSample(
                    sessionId = "health-connect-${session.metadata.id}",
                    date = session.startTime.atOffset(offset).toLocalDate(),
                    startEpochMs = session.startTime.toEpochMilli(),
                    durationMinutes = durationMinutes,
                    steps = aggregate[StepsRecord.COUNT_TOTAL] ?: 0L,
                    distanceKm = aggregate[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0,
                    activeCalories = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
                ))
            }
        }
        return HealthHistoryImport(daily, weights, walking)
    }

    override suspend fun writeNutrition(
        date: LocalDate,
        logs: List<FoodLogSnapshot>,
        priorClientRecordIds: Set<String>,
        clientRecordVersion: Long
    ): HealthWriteSummary {
        val hc = client ?: error(availabilityMessage())
        require(hasNutritionWritePermission()) { "Health Connect nutrition write permission not granted" }
        val currentIds = logs.map { it.healthClientRecordId }.toSet()
        val records = logs.map { it.toNutritionRecord(clientRecordVersion) }
        if (records.isNotEmpty()) hc.insertRecords(records)
        val staleIds = priorClientRecordIds - currentIds
        if (staleIds.isNotEmpty()) {
            hc.deleteRecords(
                recordType = NutritionRecord::class,
                recordIdsList = emptyList(),
                clientRecordIdsList = staleIds.toList()
            )
        }
        return HealthWriteSummary(records.size, date)
    }

    private suspend fun readAllExerciseSessions(
        hc: HealthConnectClient,
        timeRange: TimeRangeFilter
    ): List<ExerciseSessionRecord> {
        val records = mutableListOf<ExerciseSessionRecord>()
        var pageToken: String? = null
        do {
            val page = hc.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = timeRange,
                    ascendingOrder = false,
                    pageSize = 100,
                    pageToken = pageToken
                )
            )
            records += page.records
            pageToken = page.pageToken
        } while (pageToken != null)
        return records
    }

    private suspend fun readAllNutritionRecords(
        hc: HealthConnectClient,
        timeRange: TimeRangeFilter
    ): List<NutritionRecord> {
        val records = mutableListOf<NutritionRecord>()
        var pageToken: String? = null
        do {
            val page = hc.readRecords(
                ReadRecordsRequest(
                    recordType = NutritionRecord::class,
                    timeRangeFilter = timeRange,
                    ascendingOrder = false,
                    pageSize = 200,
                    pageToken = pageToken
                )
            )
            records += page.records
            pageToken = page.pageToken
        } while (pageToken != null)
        return records
    }

    private suspend fun readAllWeights(
        hc: HealthConnectClient,
        timeRange: TimeRangeFilter
    ): List<WeightRecord> {
        val records = mutableListOf<WeightRecord>()
        var pageToken: String? = null
        do {
            val page = hc.readRecords(
                ReadRecordsRequest(
                    recordType = WeightRecord::class,
                    timeRangeFilter = timeRange,
                    ascendingOrder = true,
                    pageSize = 200,
                    pageToken = pageToken
                )
            )
            records += page.records
            pageToken = page.pageToken
        } while (pageToken != null)
        return records
    }

    private fun FoodLogSnapshot.toNutritionRecord(clientRecordVersion: Long): NutritionRecord {
        val zone = ZoneId.systemDefault()
        val (start, end) = healthRecordWindow(zone)
        val displayName = listOf(productName, brand.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · ")
        return NutritionRecord(
            startTime = start.toInstant(),
            startZoneOffset = start.offset,
            endTime = end.toInstant(),
            endZoneOffset = end.offset,
            energy = calories.positiveEnergy(),
            protein = proteinG.positiveGrams(),
            sodium = sodiumMg.positiveMilligrams(),
            totalCarbohydrate = carbsG.positiveGrams(),
            totalFat = fatG.positiveGrams(),
            sugar = sugarG.positiveGrams(),
            dietaryFiber = fiberG.positiveGrams(),
            saturatedFat = saturatedFatG.positiveGrams(),
            name = displayName,
            mealType = MealType.MEAL_TYPE_UNKNOWN,
            metadata = Metadata.manualEntry(
                clientRecordId = healthClientRecordId,
                clientRecordVersion = clientRecordVersion
            )
        )
    }

    private fun Double.positiveEnergy(): Energy? = takeIf { it > 0.0 }?.let(Energy::kilocalories)
    private fun Double.positiveGrams(): Mass? = takeIf { it > 0.0 }?.let(Mass::grams)
    private fun Double.positiveMilligrams(): Mass? = takeIf { it > 0.0 }?.let(Mass::milligrams)
}

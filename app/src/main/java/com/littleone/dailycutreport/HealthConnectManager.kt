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
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthConnectManager(private val context: Context) {
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
        val PERMISSIONS: Set<String> = CORE_PERMISSIONS + NUTRITION_PERMISSION
    }

    private val client: HealthConnectClient? by lazy {
        if (isAvailable()) HealthConnectClient.getOrCreate(context) else null
    }

    fun availabilityMessage(): String {
        return when (HealthConnectClient.getSdkStatus(context)) {
            HealthConnectClient.SDK_AVAILABLE -> "Health Connect available"
            HealthConnectClient.SDK_UNAVAILABLE -> "Health Connect unavailable on this device"
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "Health Connect provider update required"
            else -> "Unknown Health Connect status"
        }
    }

    fun isAvailable(): Boolean = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasCorePermissions(): Boolean {
        val hc = client ?: return false
        val granted = hc.permissionController.getGrantedPermissions()
        return granted.containsAll(CORE_PERMISSIONS)
    }

    suspend fun hasNutritionPermission(): Boolean = client?.permissionController
        ?.getGrantedPermissions()?.contains(NUTRITION_PERMISSION) == true

    suspend fun hasNutritionWritePermission(): Boolean = client?.permissionController
        ?.getGrantedPermissions()?.contains(NUTRITION_WRITE_PERMISSION) == true

    suspend fun readDailySummary(date: LocalDate): HealthSummary {
        val hc = client ?: return HealthSummary(healthConnectStatus = availabilityMessage())
        if (!hasCorePermissions()) return HealthSummary(healthConnectStatus = "Health Connect activity permission not granted")

        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
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

        val sessions = runCatching {
            hc.readRecords(
                ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = timeRange,
                    ascendingOrder = false,
                    pageSize = 100
                )
            ).records
        }.getOrDefault(emptyList())

        val nutritionRecords = if (hasNutritionPermission()) runCatching {
            hc.readRecords(
                ReadRecordsRequest(
                    recordType = NutritionRecord::class,
                    timeRangeFilter = timeRange,
                    ascendingOrder = false,
                    pageSize = 200
                )
            ).records
        }.getOrDefault(emptyList()) else emptyList()

        val exerciseMinutes = sessions.sumOf { session ->
            val minutes = Duration.between(session.startTime, session.endTime).toMinutes()
            minutes.coerceAtLeast(0)
        }

        val nutritionCalories = nutritionRecords.sumOf { it.energy?.inKilocalories ?: 0.0 }
        val nutritionProteinG = nutritionRecords.sumOf { it.protein?.inGrams ?: 0.0 }
        val nutritionSodiumMg = nutritionRecords.sumOf { it.sodium?.inMilligrams ?: 0.0 }
        val nutritionStatus = if (!hasNutritionPermission()) {
            "Activity loaded; Health Connect nutrition is optional and not granted"
        } else if (nutritionRecords.isEmpty()) {
            "Activity loaded; no Health Connect nutrition records found"
        } else {
            "Loaded from Health Connect, including ${nutritionRecords.size} nutrition record(s)"
        }

        return HealthSummary(
            steps = aggregate[StepsRecord.COUNT_TOTAL] ?: 0L,
            distanceKm = aggregate[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0,
            activeCalories = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
            totalCalories = aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
            exerciseSessions = sessions.size,
            exerciseMinutes = exerciseMinutes,
            nutritionCalories = nutritionCalories,
            nutritionProteinG = nutritionProteinG,
            nutritionSodiumMg = nutritionSodiumMg,
            nutritionRecords = nutritionRecords.size,
            healthConnectStatus = nutritionStatus
        )
    }

    suspend fun writeNutrition(
        date: LocalDate,
        logs: List<FoodLogSnapshot>,
        priorClientRecordIds: Set<String> = emptySet()
    ): HealthWriteSummary {
        val hc = client ?: error(availabilityMessage())
        require(hasNutritionWritePermission()) { "Health Connect nutrition write permission not granted" }

        val records = logs.map { it.toNutritionRecord() }
        val clientIds = (priorClientRecordIds + records.mapNotNull { it.metadata.clientRecordId }).toList()
        if (clientIds.isNotEmpty()) {
            runCatching {
                hc.deleteRecords(
                    recordType = NutritionRecord::class,
                    recordIdsList = emptyList(),
                    clientRecordIdsList = clientIds
                )
            }
        }
        if (records.isNotEmpty()) hc.insertRecords(records)
        return HealthWriteSummary(records.size, date)
    }

    private fun FoodLogSnapshot.toNutritionRecord(): NutritionRecord {
        val start = Instant.ofEpochMilli(loggedAt)
        val end = start.plus(Duration.ofMinutes(1))
        return NutritionRecord(
            startTime = start,
            startZoneOffset = null,
            endTime = end,
            endZoneOffset = null,
            energy = calories.positiveEnergy(),
            protein = proteinG.positiveGrams(),
            sodium = sodiumMg.positiveMilligrams(),
            totalCarbohydrate = carbsG.positiveGrams(),
            totalFat = fatG.positiveGrams(),
            sugar = sugarG.positiveGrams(),
            dietaryFiber = fiberG.positiveGrams(),
            saturatedFat = saturatedFatG.positiveGrams(),
            mealType = MealType.MEAL_TYPE_UNKNOWN,
            metadata = Metadata(clientRecordId = healthClientRecordId)
        )
    }

    private fun Double.positiveEnergy(): Energy? = takeIf { it > 0.0 }?.let { Energy.kilocalories(it) }
    private fun Double.positiveGrams(): Mass? = takeIf { it > 0.0 }?.let { Mass.grams(it) }
    private fun Double.positiveMilligrams(): Mass? = takeIf { it > 0.0 }?.let { Mass.milligrams(it) }
}

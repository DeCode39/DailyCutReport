package com.littleone.dailycutreport

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

class HealthConnectManager(private val context: Context) {
    companion object {
        val PERMISSIONS: Set<String> = setOf(
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        )
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

    suspend fun hasAllPermissions(): Boolean {
        val hc = client ?: return false
        val granted = hc.permissionController.getGrantedPermissions()
        return granted.containsAll(PERMISSIONS)
    }

    suspend fun readDailySummary(date: LocalDate): HealthSummary {
        val hc = client ?: return HealthSummary(healthConnectStatus = availabilityMessage())
        if (!hasAllPermissions()) return HealthSummary(healthConnectStatus = "Health Connect permission not granted")

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

        val exerciseMinutes = sessions.sumOf { session ->
            val minutes = Duration.between(session.startTime, session.endTime).toMinutes()
            minutes.coerceAtLeast(0)
        }

        return HealthSummary(
            steps = aggregate[StepsRecord.COUNT_TOTAL] ?: 0L,
            distanceKm = aggregate[DistanceRecord.DISTANCE_TOTAL]?.inKilometers ?: 0.0,
            activeCalories = aggregate[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0,
            totalCalories = aggregate[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories ?: 0.0,
            exerciseSessions = sessions.size,
            exerciseMinutes = exerciseMinutes,
            healthConnectStatus = "Loaded from Health Connect"
        )
    }
}

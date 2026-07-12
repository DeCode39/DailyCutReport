package com.littleone.dailycutreport

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

class NutritionSyncCoordinator(
    private val dao: NutritionDao,
    private val healthConnect: HealthDataSource,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private val mutex = Mutex()

    suspend fun enqueue(dates: Set<LocalDate>) = mutex.withLock {
        val pending = pendingDates().toMutableSet().apply { addAll(dates) }
        storePending(pending)
    }

    suspend fun sync(date: LocalDate): Result<HealthWriteSummary> = mutex.withLock {
        runCatching {
            markPending(date)
            if (!healthConnect.isAvailable()) return@runCatching unavailable(date, "Health Connect unavailable")
            if (!healthConnect.hasNutritionWritePermission()) {
                return@runCatching unavailable(date, "Nutrition write permission not granted")
            }
            val logs = dao.foodLogsForDate(date.toString()).map(DailyFoodLogEntity::toDomainSnapshot)
            val priorIds = exportedIds(date)
            val version = nextVersion(date)
            val summary = healthConnect.writeNutrition(date, logs, priorIds, version)
            dao.upsertMetadata(AppMetadataEntity(exportedIdsKey(date), logs.map { it.healthClientRecordId }.distinct().joinToString("\n")))
            clearPending(date)
            storeStatus("Synced ${summary.recordsWritten} record(s) for $date")
            summary
        }.onFailure { error ->
            storeStatus("Nutrition sync pending: ${error.message ?: "unknown error"}")
        }
    }

    suspend fun retryPending() {
        pendingDates().forEach { sync(it) }
    }

    suspend fun status(): String? = dao.metadata(STATUS_KEY)

    private suspend fun unavailable(date: LocalDate, status: String): HealthWriteSummary {
        storeStatus(status)
        return HealthWriteSummary(0, date)
    }

    private suspend fun exportedIds(date: LocalDate): Set<String> = dao.metadata(exportedIdsKey(date))
        ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toSet().orEmpty()

    private suspend fun nextVersion(date: LocalDate): Long {
        val key = versionKey(date)
        val prior = dao.metadata(key)?.toLongOrNull() ?: 0L
        val next = maxOf(prior + 1, clockMillis())
        dao.upsertMetadata(AppMetadataEntity(key, next.toString()))
        return next
    }

    private suspend fun markPending(date: LocalDate) {
        val pending = pendingDates().toMutableSet().apply { add(date) }
        storePending(pending)
    }

    private suspend fun clearPending(date: LocalDate) {
        val pending = pendingDates().toMutableSet().apply { remove(date) }
        storePending(pending)
    }

    private suspend fun pendingDates(): Set<LocalDate> = dao.metadata(PENDING_DATES_KEY)
        ?.lineSequence()?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }?.toSet().orEmpty()

    private suspend fun storePending(dates: Set<LocalDate>) {
        dao.upsertMetadata(AppMetadataEntity(PENDING_DATES_KEY, dates.sorted().joinToString("\n")))
    }

    private suspend fun storeStatus(value: String) {
        dao.upsertMetadata(AppMetadataEntity(STATUS_KEY, value))
    }

    private companion object {
        const val STATUS_KEY = "health_nutrition_sync_status"
        const val PENDING_DATES_KEY = "health_nutrition_pending_dates"
        fun exportedIdsKey(date: LocalDate) = "health_nutrition_exported_ids_$date"
        fun versionKey(date: LocalDate) = "health_nutrition_client_version_$date"
    }
}

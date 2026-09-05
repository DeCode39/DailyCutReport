package com.littleone.dailycutreport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal val WeightEntry.weightClientId get() = "dailycut-weight-$entryId"

/** Local measurements are authoritative; only IDs previously exported by this app are deleted. */
internal class WeightSyncCoordinator(private val dao: NutritionDao, private val health: HealthDataSource) {
    private val mutex = Mutex()
    suspend fun sync() = mutex.withLock {
        try {
            if (!health.hasWeightWritePermission()) {
                dao.upsertMetadata(AppMetadataEntity(STATUS, "Weight export off · permission required"))
                return@withLock
            }
            val entries = dao.allWeightEntries().filter { it.source == WeightSource.MANUAL.name }.map { it.toDomain() }
            val current = entries.map { it.weightClientId }.toSet()
            val prior = dao.metadata(IDS).orEmpty().lineSequence().filter(String::isNotBlank).toSet()
            val payload = entries.sortedBy { it.entryId }.joinToString("\n") {
                "${it.entryId}|${it.recordedAtEpochMs}|${it.weightKg}"
            }
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            if (current == prior && dao.metadata(FINGERPRINT) == fingerprint &&
                dao.metadata(STATUS)?.startsWith("Synced") == true) return@withLock
            val version = maxOf(System.currentTimeMillis(), (dao.metadata(VERSION)?.toLongOrNull() ?: 0L) + 1)
            dao.upsertMetadata(AppMetadataEntity(VERSION, version.toString()))
            // Remember attempted insertions too: an interrupted batch can otherwise leave orphan exports.
            dao.upsertMetadata(AppMetadataEntity(IDS, (prior + current).sorted().joinToString("\n")))
            health.writeWeights(entries, prior - current, version)
            dao.upsertMetadata(AppMetadataEntity(IDS, current.sorted().joinToString("\n")))
            dao.upsertMetadata(AppMetadataEntity(FINGERPRINT, fingerprint))
            dao.upsertMetadata(AppMetadataEntity(STATUS, "Synced ${entries.size} manual weights · ${java.time.Instant.now()}"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            dao.upsertMetadata(AppMetadataEntity(STATUS, "Weight sync pending · retry on next open"))
        }
    }
    companion object {
        const val STATUS = "weight_sync_status_v1"
        private const val IDS = "weight_sync_ids_v1"
        private const val VERSION = "weight_sync_version_v1"
        private const val FINGERPRINT = "weight_sync_fingerprint_v1"
    }
}

package com.littleone.dailycutreport

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate

interface DailyCutRepository {
    suspend fun initialize()
    fun observeReport(date: LocalDate): Flow<DailyReport>
    fun observeFoodLogs(date: LocalDate): Flow<List<FoodLogSnapshot>>
    fun observeProducts(query: String): Flow<List<ProductEntity>>
    suspend fun nutritionForDate(date: LocalDate): NutritionSummary
    suspend fun refreshHealth(date: LocalDate): Result<Unit>
    suspend fun lookupProduct(barcode: String): ProductWithExtras?
    suspend fun getProduct(productId: String): ProductWithExtras?
    suspend fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>)
    suspend fun addProduct(date: LocalDate, product: ProductWithExtras, quantity: Double)
    suspend fun updateFoodLog(edit: FoodLogEdit)
    suspend fun deleteFoodLog(id: Long)
    suspend fun saveReport(report: DailyReport): Uri?
    suspend fun writeReport(uri: Uri, report: DailyReport): Boolean
    suspend fun createShareUri(report: DailyReport): Uri?
    suspend fun exportBackup(uri: Uri, password: CharArray): Result<Unit>
    suspend fun restoreBackup(uri: Uri, password: CharArray): Result<Unit>
    fun healthConnectAvailable(): Boolean
    suspend fun healthCorePermissionsGranted(): Boolean
    suspend fun healthNutritionPermissionGranted(): Boolean
    suspend fun healthNutritionWritePermissionGranted(): Boolean
    suspend fun syncNutritionToHealthConnect(date: LocalDate): Result<HealthWriteSummary>
    suspend fun nutritionSyncStatus(): String?
}

class DefaultDailyCutRepository(
    private val context: Context,
    private val dao: NutritionDao,
    private val healthConnect: HealthConnectManager,
    private val legacyImporter: LegacyReportImporter,
    private val catalogImporter: ProductCatalogImporter,
    private val exporter: ReportImageExporter,
    private val backupManager: AppBackupManager
) : DailyCutRepository {

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        legacyImporter.importIfNeeded()
        catalogImporter.importIfNeeded()
        clearManualOverridesIfNeeded()
        DailyCutWidgetUpdater.updateAll(context)
    }

    override fun observeReport(date: LocalDate): Flow<DailyReport> {
        val key = date.toString()
        return combine(
            dao.observeDailyReport(key),
            dao.observeTotalsForDate(key),
            dao.observeExtraTotalsForDate(key)
        ) { report, totals, extras ->
            val entity = report ?: DailyReportEntity(date = key)
            entity.toDomain(totals.toSummary(extras.associate { it.name to NutrientAmount(it.value, it.unit) }))
        }.flowOn(Dispatchers.IO)
    }

    override fun observeFoodLogs(date: LocalDate): Flow<List<FoodLogSnapshot>> =
        dao.observeLogsForDate(date.toString())
            .map { logs -> logs.map(DailyFoodLogEntity::toDomain) }
            .flowOn(Dispatchers.IO)

    override fun observeProducts(query: String): Flow<List<ProductEntity>> =
        dao.observeProducts(query.trim()).flowOn(Dispatchers.IO)

    override suspend fun nutritionForDate(date: LocalDate): NutritionSummary = withContext(Dispatchers.IO) {
        dao.totalsForDate(date.toString()).toSummary(emptyMap())
    }

    override suspend fun refreshHealth(date: LocalDate): Result<Unit> = runCatching {
        val summary = healthConnect.readDailySummary(date)
        withContext(Dispatchers.IO) {
            val existing = dao.dailyReport(date.toString()) ?: DailyReportEntity(date = date.toString())
            dao.upsertDailyReport(existing.withHealth(summary))
        }
        DailyCutWidgetUpdater.updateAll(context)
    }

    override suspend fun lookupProduct(barcode: String): ProductWithExtras? = withContext(Dispatchers.IO) {
        dao.productByBarcode(barcode.trim())?.let { ProductWithExtras(it, dao.extrasForProduct(it.productId)) }
    }

    override suspend fun getProduct(productId: String): ProductWithExtras? = withContext(Dispatchers.IO) {
        dao.productById(productId)?.let { ProductWithExtras(it, dao.extrasForProduct(it.productId)) }
    }

    override suspend fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>) = withContext(Dispatchers.IO) {
        product.barcode?.let { barcode ->
            val owner = dao.productByBarcode(barcode)
            require(owner == null || owner.productId == product.productId) { "Barcode is already assigned to ${owner?.name}." }
        }
        dao.saveProductWithExtras(product, extras)
    }

    override suspend fun addProduct(date: LocalDate, product: ProductWithExtras, quantity: Double) = withContext(Dispatchers.IO) {
        require(quantity > 0.0) { "Quantity must be greater than zero" }
        dao.addProductToDate(date.toString(), product.product, quantity, product.extras)
    }

    override suspend fun updateFoodLog(edit: FoodLogEdit) = withContext(Dispatchers.IO) {
        require(edit.quantity > 0.0) { "Quantity must be greater than zero" }
        val existing = dao.foodLogById(edit.id) ?: return@withContext
        dao.updateFoodLog(existing.copy(
            quantity = edit.quantity,
            servingLabel = edit.servingLabel.ifBlank { "1 serving" },
            caloriesPerServing = edit.caloriesPerServing,
            proteinGPerServing = edit.proteinGPerServing,
            sodiumMgPerServing = edit.sodiumMgPerServing,
            carbsGPerServing = edit.carbsGPerServing,
            fatGPerServing = edit.fatGPerServing,
            sugarGPerServing = edit.sugarGPerServing,
            fiberGPerServing = edit.fiberGPerServing,
            saturatedFatGPerServing = edit.saturatedFatGPerServing
        ))
    }

    override suspend fun deleteFoodLog(id: Long) = withContext(Dispatchers.IO) { dao.deleteLog(id) }

    override suspend fun saveReport(report: DailyReport): Uri? = withContext(Dispatchers.IO) {
        exporter.saveReportToPictures(report)
    }

    override suspend fun writeReport(uri: Uri, report: DailyReport): Boolean = withContext(Dispatchers.IO) {
        exporter.writeReport(uri, report)
    }

    override suspend fun createShareUri(report: DailyReport): Uri? = withContext(Dispatchers.IO) {
        exporter.createShareUri(report)
    }

    override suspend fun exportBackup(uri: Uri, password: CharArray): Result<Unit> = runCatching {
        backupManager.export(uri, password)
    }

    override suspend fun restoreBackup(uri: Uri, password: CharArray): Result<Unit> = runCatching {
        backupManager.restore(uri, password)
    }

    override fun healthConnectAvailable(): Boolean = healthConnect.isAvailable()

    override suspend fun healthCorePermissionsGranted(): Boolean = healthConnect.hasCorePermissions()
    override suspend fun healthNutritionPermissionGranted(): Boolean = healthConnect.hasNutritionPermission()
    override suspend fun healthNutritionWritePermissionGranted(): Boolean = healthConnect.hasNutritionWritePermission()

    override suspend fun syncNutritionToHealthConnect(date: LocalDate): Result<HealthWriteSummary> = runCatching {
        if (!healthConnect.isAvailable()) {
            storeNutritionSyncStatus("Health Connect unavailable")
            DailyCutWidgetUpdater.updateAll(context)
            return@runCatching HealthWriteSummary(0, date)
        }
        if (!healthConnect.hasNutritionWritePermission()) {
            storeNutritionSyncStatus("Nutrition write permission not granted")
            DailyCutWidgetUpdater.updateAll(context)
            return@runCatching HealthWriteSummary(0, date)
        }
        val logs = withContext(Dispatchers.IO) {
            dao.foodLogsForDate(date.toString()).map(DailyFoodLogEntity::toDomain)
        }
        val priorIds = exportedNutritionIds(date)
        val summary = healthConnect.writeNutrition(date, logs, priorIds)
        storeExportedNutritionIds(date, logs.map { it.healthClientRecordId })
        storeNutritionSyncStatus("Synced ${summary.recordsWritten} record(s) for $date")
        DailyCutWidgetUpdater.updateAll(context)
        summary
    }.onFailure { error ->
        storeNutritionSyncStatus("Nutrition sync failed: ${error.message ?: "unknown error"}")
        DailyCutWidgetUpdater.updateAll(context)
    }

    override suspend fun nutritionSyncStatus(): String? = withContext(Dispatchers.IO) {
        dao.metadata(NUTRITION_SYNC_STATUS_KEY)
    }

    private suspend fun clearManualOverridesIfNeeded() {
        if (dao.metadata(CLEAR_OVERRIDES_KEY) == "complete") return
        dao.clearManualOverrides()
        dao.upsertMetadata(AppMetadataEntity(CLEAR_OVERRIDES_KEY, "complete"))
    }

    private suspend fun exportedNutritionIds(date: LocalDate): Set<String> = withContext(Dispatchers.IO) {
        dao.metadata(exportedNutritionIdsKey(date))
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
    }

    private suspend fun storeExportedNutritionIds(date: LocalDate, ids: List<String>) = withContext(Dispatchers.IO) {
        dao.upsertMetadata(AppMetadataEntity(exportedNutritionIdsKey(date), ids.distinct().joinToString("\n")))
    }

    private suspend fun storeNutritionSyncStatus(status: String) = withContext(Dispatchers.IO) {
        dao.upsertMetadata(AppMetadataEntity(NUTRITION_SYNC_STATUS_KEY, status))
    }

    private companion object {
        const val CLEAR_OVERRIDES_KEY = "manual_overrides_cleared_0_8_5"
        const val NUTRITION_SYNC_STATUS_KEY = "health_nutrition_sync_status"
        fun exportedNutritionIdsKey(date: LocalDate) = "health_nutrition_exported_ids_$date"
    }
}

private fun DailyReportEntity.toDomain(nutrition: NutritionSummary) = DailyReport(
    date = LocalDate.parse(date),
    health = HealthSummary(
        steps, distanceKm, activeCalories, totalCalories, exerciseSessions, exerciseMinutes,
        nutritionCalories, nutritionProteinG, nutritionSodiumMg, nutritionRecords, healthConnectStatus
    ),
    nutrition = nutrition,
    manual = ManualOverrides(manualFoodCalories, manualProteinG, manualSodiumMg, manualBurnCalories, notes),
    savedAtEpochMs = savedAtEpochMs
)

private fun DailyNutritionTotals.toSummary(extras: Map<String, NutrientAmount>) = NutritionSummary(
    calories = calories,
    proteinG = proteinG,
    sodiumMg = sodiumMg,
    carbsG = carbsG,
    fatG = fatG,
    sugarG = sugarG,
    fiberG = fiberG,
    saturatedFatG = saturatedFatG,
    entries = entries,
    extras = extras
)

val FoodLogSnapshot.healthClientRecordId: String get() = "dailycut-food-log-$id"

private fun DailyReportEntity.withHealth(health: HealthSummary) = copy(
    steps = health.steps,
    distanceKm = health.distanceKm,
    activeCalories = health.activeCalories,
    totalCalories = health.totalCalories,
    exerciseSessions = health.exerciseSessions,
    exerciseMinutes = health.exerciseMinutes,
    nutritionCalories = health.nutritionCalories,
    nutritionProteinG = health.nutritionProteinG,
    nutritionSodiumMg = health.nutritionSodiumMg,
    nutritionRecords = health.nutritionRecords,
    healthConnectStatus = health.healthConnectStatus,
    savedAtEpochMs = System.currentTimeMillis()
)

private fun DailyFoodLogEntity.toDomain() = FoodLogSnapshot(
    id = id,
    date = LocalDate.parse(date),
    productId = productId,
    barcode = barcode,
    productName = productName,
    brand = brand,
    servingLabel = servingLabel,
    quantity = quantity,
    caloriesPerServing = caloriesPerServing,
    proteinGPerServing = proteinGPerServing,
    sodiumMgPerServing = sodiumMgPerServing,
    carbsGPerServing = carbsGPerServing,
    fatGPerServing = fatGPerServing,
    sugarGPerServing = sugarGPerServing,
    fiberGPerServing = fiberGPerServing,
    saturatedFatGPerServing = saturatedFatGPerServing,
    loggedAt = loggedAt
)

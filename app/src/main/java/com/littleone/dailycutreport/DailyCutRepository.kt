package com.littleone.dailycutreport

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
    suspend fun refreshHealth(date: LocalDate): Result<Unit>
    suspend fun saveManualOverrides(date: LocalDate, overrides: ManualOverrides)
    suspend fun lookupProduct(barcode: String): ProductWithExtras?
    suspend fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>)
    suspend fun addProduct(date: LocalDate, product: ProductWithExtras, quantity: Double)
    suspend fun updateFoodLog(edit: FoodLogEdit)
    suspend fun deleteFoodLog(id: Long)
    suspend fun saveReport(report: DailyReport): Uri?
    suspend fun writeReport(uri: Uri, report: DailyReport): Boolean
    suspend fun createShareUri(report: DailyReport): Uri?
    fun healthConnectAvailable(): Boolean
    suspend fun healthPermissionsGranted(): Boolean
}

class DefaultDailyCutRepository(
    private val dao: NutritionDao,
    private val healthConnect: HealthConnectManager,
    private val legacyImporter: LegacyReportImporter,
    private val exporter: ReportImageExporter
) : DailyCutRepository {

    override suspend fun initialize() = withContext(Dispatchers.IO) {
        legacyImporter.importIfNeeded()
    }

    override fun observeReport(date: LocalDate): Flow<DailyReport> {
        val key = date.toString()
        return combine(
            dao.observeDailyReport(key),
            dao.observeTotalsForDate(key),
            dao.observeExtraTotalsForDate(key)
        ) { report, totals, extras ->
            val entity = report ?: DailyReportEntity(date = key)
            entity.toDomain(
                NutritionSummary(
                    calories = totals.calories,
                    proteinG = totals.proteinG,
                    sodiumMg = totals.sodiumMg,
                    carbsG = totals.carbsG,
                    fatG = totals.fatG,
                    sugarG = totals.sugarG,
                    fiberG = totals.fiberG,
                    saturatedFatG = totals.saturatedFatG,
                    entries = totals.entries,
                    extras = extras.associate { it.name to NutrientAmount(it.value, it.unit) }
                )
            )
        }.flowOn(Dispatchers.IO)
    }

    override fun observeFoodLogs(date: LocalDate): Flow<List<FoodLogSnapshot>> =
        dao.observeLogsForDate(date.toString())
            .map { logs -> logs.map(DailyFoodLogEntity::toDomain) }
            .flowOn(Dispatchers.IO)

    override fun observeProducts(query: String): Flow<List<ProductEntity>> =
        dao.observeProducts(query.trim()).flowOn(Dispatchers.IO)

    override suspend fun refreshHealth(date: LocalDate): Result<Unit> = runCatching {
        val summary = healthConnect.readDailySummary(date)
        withContext(Dispatchers.IO) {
            val existing = dao.dailyReport(date.toString()) ?: DailyReportEntity(date = date.toString())
            dao.upsertDailyReport(existing.withHealth(summary))
        }
    }

    override suspend fun saveManualOverrides(date: LocalDate, overrides: ManualOverrides) = withContext(Dispatchers.IO) {
        val existing = dao.dailyReport(date.toString()) ?: DailyReportEntity(date = date.toString())
        dao.upsertDailyReport(existing.copy(
            manualFoodCalories = overrides.foodCalories,
            manualProteinG = overrides.proteinG,
            manualSodiumMg = overrides.sodiumMg,
            manualBurnCalories = overrides.burnCalories,
            notes = overrides.notes,
            savedAtEpochMs = System.currentTimeMillis()
        ))
    }

    override suspend fun lookupProduct(barcode: String): ProductWithExtras? = withContext(Dispatchers.IO) {
        dao.productByBarcode(barcode.trim())?.let { ProductWithExtras(it, dao.extrasForProduct(it.barcode)) }
    }

    override suspend fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>) = withContext(Dispatchers.IO) {
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

    override fun healthConnectAvailable(): Boolean = healthConnect.isAvailable()

    override suspend fun healthPermissionsGranted(): Boolean = healthConnect.hasAllPermissions()
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

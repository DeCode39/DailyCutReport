package com.littleone.dailycutreport

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.LocalDate

interface DailyCutRepository {
    suspend fun initialize()
    fun observeReport(date: LocalDate): Flow<DailyReport>
    fun observeFoodLogs(date: LocalDate): Flow<List<FoodLogSnapshot>>
    fun observeProducts(query: String): Flow<List<ProductEntity>>
    fun observeRecentProducts(): Flow<List<ProductEntity>>
    fun observeGoals(): Flow<UserGoals>
    fun observeSpending(date: LocalDate): Flow<DailySpending>
    suspend fun updateGoals(goals: UserGoals)
    suspend fun recommendations(date: LocalDate): RecommendationResult
    suspend fun nutritionForDate(date: LocalDate): NutritionSummary
    suspend fun refreshHealth(date: LocalDate): Result<Unit>
    suspend fun lookupProduct(barcode: String): ProductWithExtras?
    suspend fun getProduct(productId: String): ProductWithExtras?
    suspend fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>): ProductMutationResult
    suspend fun addProduct(
        date: LocalDate,
        product: ProductWithExtras,
        quantity: Double,
        actualPaidTotalMicros: Long? = null,
        excludeCostFromBudget: Boolean = false
    ): FoodMutationResult
    suspend fun addBulkPurchase(
        date: LocalDate,
        label: String,
        entries: List<BulkLogSelection>,
        actualPaidTotalMicros: Long? = null,
        excludeCostFromBudget: Boolean = false
    ): FoodMutationResult
    suspend fun updateFoodLog(edit: FoodQuantityEdit): FoodMutationResult
    suspend fun deleteFoodLog(id: Long): FoodMutationResult
    suspend fun restoreFoodLog(deleted: DeletedFoodLogSnapshot): FoodMutationResult
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
    suspend fun retryPendingNutritionSync()
    suspend fun nutritionSyncStatus(): String?
}

class DefaultDailyCutRepository(
    private val context: Context,
    private val dao: NutritionDao,
    private val healthConnect: HealthDataSource,
    private val legacyImporter: LegacyReportImporter,
    private val catalogImporter: ProductCatalogImporter,
    private val exporter: ReportImageExporter,
    private val backupManager: AppBackupManager
) : DailyCutRepository {
    private val initializationMutex = Mutex()
    private var initialized = false
    private val nutritionSync = NutritionSyncCoordinator(dao, healthConnect)
    private val mealPlanner = OfflineMealPlanner()
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override suspend fun initialize() = initializationMutex.withLock {
        if (initialized) return@withLock
        withContext(Dispatchers.IO) {
            legacyImporter.importIfNeeded()
            catalogImporter.importIfNeeded()
            val storedGoals = dao.userGoals()
            if (storedGoals == null) {
                dao.upsertUserGoals(UserGoalsEntity())
            } else {
                val sanitized = storedGoals.toDomain().sanitized()
                if (sanitized != storedGoals.toDomain()) dao.upsertUserGoals(sanitized.toEntity())
            }
            clearManualOverridesIfNeeded()
            DailyCutWidgetUpdater.updateAll(context)
        }
        initialized = true
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
            .map { logs -> logs.map(DailyFoodLogEntity::toDomainSnapshot) }
            .flowOn(Dispatchers.IO)

    override fun observeProducts(query: String): Flow<List<ProductEntity>> =
        dao.observeProducts(query.trim().escapeLikePattern()).flowOn(Dispatchers.IO)

    override fun observeRecentProducts(): Flow<List<ProductEntity>> =
        dao.observeRecentProducts().flowOn(Dispatchers.IO)

    override fun observeGoals(): Flow<UserGoals> = dao.observeUserGoals()
        .map { (it ?: UserGoalsEntity()).toDomain().sanitized() }
        .flowOn(Dispatchers.IO)

    override fun observeSpending(date: LocalDate): Flow<DailySpending> = combine(
        dao.observeSpendingForDate(date.toString()),
        observeGoals()
    ) { spending, goals -> DailySpending(
        spending.knownTotalMicros, spending.unknownEntries, goals.dailyBudgetMicros,
        spending.catalogEstimatedMicros, spending.actualPaidMicros, spending.actualPaidEntries
    ) }
        .flowOn(Dispatchers.IO)

    override suspend fun updateGoals(goals: UserGoals) = withContext(Dispatchers.IO) {
        goals.requireValid()
        dao.upsertUserGoals(goals.toEntity())
        DailyCutWidgetUpdater.updateAll(context)
    }

    override suspend fun recommendations(date: LocalDate): RecommendationResult = withContext(Dispatchers.Default) {
        val goals = withContext(Dispatchers.IO) { (dao.userGoals() ?: UserGoalsEntity()).toDomain() }
        val nutrition = withContext(Dispatchers.IO) { dao.totalsForDate(date.toString()).toSummary(emptyMap()) }
        val rawSpending = withContext(Dispatchers.IO) { dao.spendingForDate(date.toString()) }
        val projectedBurn = withContext(Dispatchers.IO) {
            dao.dailyReport(date.toString())?.totalCalories?.takeIf { it.isFinite() && it > 0.0 }
        }
        val planningGoals = goals.forPlanning(projectedBurn) ?: return@withContext RecommendationResult(
            plans = emptyList(),
            unpricedProducts = 0,
            spendingIncomplete = rawSpending.unknownEntries > 0,
            message = if (projectedBurn == null) {
                "Refresh Health Connect to load projected burn before planning in deficit mode."
            } else {
                "Projected burn must exceed the desired deficit before calories can be planned."
            }
        )
        val products = withContext(Dispatchers.IO) { dao.allProducts() }
        mealPlanner.generate(
            products, nutrition,
            DailySpending(
                rawSpending.knownTotalMicros, rawSpending.unknownEntries, goals.dailyBudgetMicros,
                rawSpending.catalogEstimatedMicros, rawSpending.actualPaidMicros, rawSpending.actualPaidEntries
            ),
            planningGoals
        )
    }

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

    override suspend fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>): ProductMutationResult = withContext(Dispatchers.IO) {
        require(product.purchasePriceMicros == null || product.purchasePriceMicros >= 0L) { "Price cannot be negative." }
        require(product.purchaseUnitServings > 0.0) { "Minimum purchase servings must be greater than zero." }
        require(product.plannerItemType in PlannerItemType.entries.map(PlannerItemType::name)) { "Invalid planner item type." }
        require(!product.alwaysIncludeInPlanner || product.includeInPlanner) { "A fixed planner item must be enabled for planning." }
        product.barcode?.let { barcode ->
            val owner = dao.productByBarcode(barcode)
            require(owner == null || owner.productId == product.productId) { "Barcode is already assigned to ${owner?.name}." }
        }
        val mutation = dao.saveProductAndUpdateLinkedLogs(product, extras)
        val dates = mutation.affectedDates.map(LocalDate::parse).toSet()
        DailyCutWidgetUpdater.updateAll(context)
        nutritionSync.enqueue(dates)
        syncScope.launch { nutritionSync.retryPending() }
        ProductMutationResult(product, mutation.linkedEntriesUpdated, dates)
    }

    override suspend fun addProduct(
        date: LocalDate,
        product: ProductWithExtras,
        quantity: Double,
        actualPaidTotalMicros: Long?,
        excludeCostFromBudget: Boolean
    ): FoodMutationResult = withContext(Dispatchers.IO) {
        require(quantity > 0.0) { "Quantity must be greater than zero" }
        require(actualPaidTotalMicros == null || actualPaidTotalMicros >= 0L) { "Actual paid cannot be negative." }
        completeFoodMutation(dao.addProductToDate(
            date.toString(), product.product, quantity, product.extras,
            actualPaidTotalMicros, excludeCostFromBudget
        ))
    }

    override suspend fun updateFoodLog(edit: FoodQuantityEdit): FoodMutationResult = withContext(Dispatchers.IO) {
        require(edit.quantity > 0.0) { "Quantity must be greater than zero" }
        require(edit.actualPaidTotalMicros == null || edit.actualPaidTotalMicros >= 0L) { "Actual paid cannot be negative." }
        val mutation = dao.updateFoodLogQuantitySnapshot(
            edit.id, edit.quantity, edit.actualPaidTotalMicros, edit.excludeCostFromBudget
        )
            ?: error("Food entry no longer exists.")
        completeFoodMutation(mutation)
    }

    override suspend fun addBulkPurchase(
        date: LocalDate,
        label: String,
        entries: List<BulkLogSelection>,
        actualPaidTotalMicros: Long?,
        excludeCostFromBudget: Boolean
    ): FoodMutationResult = withContext(Dispatchers.IO) {
        require(entries.size >= 2) { "Choose at least two items." }
        require(entries.map(BulkLogSelection::productId).distinct().size == entries.size) {
            "Use one cart row per product and adjust its quantity."
        }
        require(entries.all { it.quantity > 0.0 && it.quantity.isFinite() }) { "Item quantities must be greater than zero." }
        require(actualPaidTotalMicros == null || actualPaidTotalMicros >= 0L) { "Actual paid cannot be negative." }
        completeFoodMutation(dao.addBulkPurchaseToDate(
            date = date.toString(),
            groupId = java.util.UUID.randomUUID().toString(),
            groupLabel = label.trim().ifBlank { "Bulk purchase" },
            selections = entries,
            actualPaidTotalMicros = actualPaidTotalMicros,
            excludeCostFromBudget = excludeCostFromBudget
        ))
    }

    override suspend fun deleteFoodLog(id: Long): FoodMutationResult = withContext(Dispatchers.IO) {
        val mutation = dao.deleteFoodLogSnapshot(id) ?: error("Food entry no longer exists.")
        completeFoodMutation(mutation)
    }

    override suspend fun restoreFoodLog(deleted: DeletedFoodLogSnapshot): FoodMutationResult = withContext(Dispatchers.IO) {
        val entity = deleted.log.toEntity()
        completeFoodMutation(dao.restoreFoodLogSnapshot(DeletedFoodLogEntity(entity, deleted.extras)))
    }

    override suspend fun saveReport(report: DailyReport): Uri? = withContext(Dispatchers.IO) {
        val (goals, spending) = reportContext(report.date)
        exporter.saveReportToPictures(report, goals, spending)
    }

    override suspend fun writeReport(uri: Uri, report: DailyReport): Boolean = withContext(Dispatchers.IO) {
        val (goals, spending) = reportContext(report.date)
        exporter.writeReport(uri, report, goals, spending)
    }

    override suspend fun createShareUri(report: DailyReport): Uri? = withContext(Dispatchers.IO) {
        val (goals, spending) = reportContext(report.date)
        exporter.createShareUri(report, goals, spending)
    }

    override suspend fun exportBackup(uri: Uri, password: CharArray): Result<Unit> = runCatching {
        backupManager.export(uri, password)
    }

    override suspend fun restoreBackup(uri: Uri, password: CharArray): Result<Unit> = runCatching {
        backupManager.restore(uri, password)
        DailyCutWidgetUpdater.updateAll(context)
    }

    override fun healthConnectAvailable(): Boolean = healthConnect.isAvailable()

    override suspend fun healthCorePermissionsGranted(): Boolean = healthConnect.hasCorePermissions()
    override suspend fun healthNutritionPermissionGranted(): Boolean = healthConnect.hasNutritionPermission()
    override suspend fun healthNutritionWritePermissionGranted(): Boolean = healthConnect.hasNutritionWritePermission()

    override suspend fun syncNutritionToHealthConnect(date: LocalDate): Result<HealthWriteSummary> {
        val result = nutritionSync.sync(date)
        DailyCutWidgetUpdater.updateAll(context)
        return result
    }

    override suspend fun retryPendingNutritionSync() {
        nutritionSync.retryPending()
        DailyCutWidgetUpdater.updateAll(context)
    }

    override suspend fun nutritionSyncStatus(): String? = nutritionSync.status()

    private suspend fun clearManualOverridesIfNeeded() {
        if (dao.metadata(CLEAR_OVERRIDES_KEY) == "complete") return
        dao.clearManualOverrides()
        dao.upsertMetadata(AppMetadataEntity(CLEAR_OVERRIDES_KEY, "complete"))
    }

    private suspend fun completeFoodMutation(mutation: DailyNutritionMutation): FoodMutationResult {
        val date = LocalDate.parse(mutation.date)
        val result = FoodMutationResult(
            date = date,
            before = mutation.before.toSummary(emptyMap()),
            after = mutation.after.toSummary(emptyMap()),
            deleted = mutation.deleted?.let { deleted ->
                DeletedFoodLogSnapshot(deleted.log.toDomainSnapshot(), deleted.extras)
            }
        )
        DailyCutWidgetUpdater.updateAll(context)
        nutritionSync.sync(date)
        return result
    }

    private suspend fun reportContext(date: LocalDate): Pair<UserGoals, DailySpending> {
        val goals = (dao.userGoals() ?: UserGoalsEntity()).toDomain()
        val raw = dao.spendingForDate(date.toString())
        return goals to DailySpending(
            raw.knownTotalMicros, raw.unknownEntries, goals.dailyBudgetMicros,
            raw.catalogEstimatedMicros, raw.actualPaidMicros, raw.actualPaidEntries
        )
    }

    private companion object {
        const val CLEAR_OVERRIDES_KEY = "manual_overrides_cleared_0_8_5"
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

fun DailyNutritionTotals.toSummary(extras: Map<String, NutrientAmount>) = NutritionSummary(
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

private fun FoodLogSnapshot.toEntity() = DailyFoodLogEntity(
    id = id,
    date = date.toString(),
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
    catalogCostPerServingMicros = catalogCostPerServingMicros,
    actualPaidTotalMicros = actualPaidTotalMicros,
    excludeCostFromBudget = excludeCostFromBudget,
    mealId = mealId,
    mealName = mealName,
    loggedAt = loggedAt
)

private fun String.escapeLikePattern(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

fun UserGoalsEntity.toDomain() = UserGoals(
    GoalMode.entries.firstOrNull { it.name == mode } ?: GoalMode.CALORIE,
    calories, expectedBurnCalories, desiredDeficitCalories, proteinG, sodiumMg, carbsG, fatG,
    sugarG, fiberG, saturatedFatG, currencyCode, dailyBudgetMicros
)

fun UserGoals.toEntity() = UserGoalsEntity(
    mode = mode.name,
    calories = calories,
    expectedBurnCalories = expectedBurnCalories,
    desiredDeficitCalories = desiredDeficitCalories,
    proteinG = proteinG,
    sodiumMg = sodiumMg,
    carbsG = carbsG,
    fatG = fatG,
    sugarG = sugarG,
    fiberG = fiberG,
    saturatedFatG = saturatedFatG,
    currencyCode = currencyCode,
    dailyBudgetMicros = dailyBudgetMicros
)

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
import java.time.LocalTime
import java.time.ZoneId

interface DailyCutRepository {
    suspend fun initialize()
    fun observeReport(date: LocalDate): Flow<DailyReport>
    fun observeFoodLogs(date: LocalDate): Flow<List<FoodLogSnapshot>>
    fun observeProducts(query: String): Flow<List<ProductEntity>>
    fun observeRecentProducts(): Flow<List<ProductEntity>>
    fun observeGoals(): Flow<UserGoals>
    fun observeSpending(date: LocalDate): Flow<DailySpending>
    fun observeHealthDashboard(date: LocalDate): Flow<HealthDashboard>
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
    suspend fun healthWeightPermissionGranted(): Boolean
    suspend fun updateHealthProfile(profile: HealthProfile)
    suspend fun upsertManualWeight(date: LocalDate, weightKg: Double)
    suspend fun deleteManualWeight(date: LocalDate)
    suspend fun syncHealthHistory(force: Boolean = false): Result<Unit>
    suspend fun healthHistoryStatus(): String?
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
    private val healthAnalytics = HealthAnalyticsEngine()
    private val historySyncMutex = Mutex()
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
            if (dao.healthProfile() == null) dao.upsertHealthProfile(HealthProfileEntity())
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

    override fun observeHealthDashboard(date: LocalDate): Flow<HealthDashboard> {
        val start = date.minusDays(27).toString()
        val end = date.toString()
        val history = combine(
            dao.observeDailyReports(start, end),
            dao.observeNutritionHistory(start, end),
            dao.observeWeightEntries(start, end),
            dao.observeWalkingSamples(start, end)
        ) { reports, nutrition, weights, walking ->
            HealthHistoryState(reports, nutrition, weights, walking)
        }
        return combine(
            history, observeGoals(), dao.observeHealthProfile(), dao.observeMetadata(HEALTH_HISTORY_SYNC_STATUS_KEY)
        ) { state, goals, profileEntity, historyStatus ->
            val reportByDate = state.reports.associateBy(DailyReportEntity::date)
            val nutritionByDate = state.nutrition.associateBy(DailyNutritionHistoryRow::date)
            val days = generateSequence(date.minusDays(27)) { current ->
                current.plusDays(1).takeIf { !it.isAfter(date) }
            }.map { day ->
                val key = day.toString()
                val report = reportByDate[key]
                val local = nutritionByDate[key]
                val localPresent = (local?.entries ?: 0) > 0
                DeficitHistoryDay(
                    date = day,
                    burnCalories = report?.totalCalories ?: 0.0,
                    intakeCalories = if (localPresent) local?.calories ?: 0.0 else report?.nutritionCalories ?: 0.0,
                    nutritionPresent = localPresent || (report?.nutritionRecords ?: 0) > 0
                )
            }.toList()
            val selectedNutrition = nutritionByDate[end]
            healthAnalytics.dashboard(
                selectedDate = date,
                today = LocalDate.now(),
                report = reportByDate[end],
                nutrition = DailyNutritionTotals(
                    calories = selectedNutrition?.calories ?: 0.0,
                    entries = selectedNutrition?.entries ?: 0
                ),
                goals = goals,
                profile = (profileEntity ?: HealthProfileEntity()).toDomain(),
                history = days,
                weights = state.weights.map(WeightEntryEntity::toDomain),
                walkingSamples = state.walking.map(WalkingSessionSampleEntity::toDomain),
                historyLastSynced = historyStatus
            )
        }.flowOn(Dispatchers.Default)
    }

    override suspend fun updateGoals(goals: UserGoals) = withContext(Dispatchers.IO) {
        goals.requireValid()
        dao.upsertUserGoals(goals.toEntity())
        DailyCutWidgetUpdater.updateAll(context)
    }

    override suspend fun recommendations(date: LocalDate): RecommendationResult = withContext(Dispatchers.Default) {
        val dateKey = date.toString()
        val input = withContext(Dispatchers.IO) {
            val goals = (dao.userGoals() ?: UserGoalsEntity()).toDomain()
            val nutrition = dao.totalsForDate(dateKey).toSummary(emptyMap())
            val rawSpending = dao.spendingForDate(dateKey)
            val projectedBurn = dao.dailyReport(dateKey)?.totalCalories?.takeIf { it.isFinite() && it > 0.0 }
            val loggedServings = dao.foodLogsForDate(dateKey)
                .mapNotNull { log -> log.productId?.let { it to log.quantity } }
                .groupBy(keySelector = Pair<String, Double>::first, valueTransform = Pair<String, Double>::second)
                .mapValues { (_, quantities) -> quantities.sum() }
            PlannerRepositoryInput(goals, nutrition, rawSpending, projectedBurn, loggedServings)
        }
        val planningGoals = input.goals.forPlanning(input.projectedBurn) ?: return@withContext RecommendationResult(
            plans = emptyList(),
            unpricedProducts = 0,
            spendingIncomplete = input.spending.unknownEntries > 0,
            message = if (input.projectedBurn == null) {
                "Refresh Health Connect to load projected burn before planning in deficit mode."
            } else {
                "Projected burn must exceed the desired deficit before calories can be planned."
            }
        )
        val products = withContext(Dispatchers.IO) { dao.allProducts() }
        mealPlanner.generate(
            products,
            PlannerDayContext(
                consumed = input.nutrition,
                spending = DailySpending(
                    input.spending.knownTotalMicros, input.spending.unknownEntries, input.goals.dailyBudgetMicros,
                    input.spending.catalogEstimatedMicros, input.spending.actualPaidMicros,
                    input.spending.actualPaidEntries
                ),
                loggedServingsByProductId = input.loggedServings
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

    override suspend fun healthWeightPermissionGranted(): Boolean = healthConnect.hasWeightPermission()

    override suspend fun updateHealthProfile(profile: HealthProfile) = withContext(Dispatchers.IO) {
        require(profile.targetWeightKg == null || profile.targetWeightKg.isFinite() && profile.targetWeightKg > 0.0) {
            "Target weight must be greater than zero."
        }
        dao.upsertHealthProfile(profile.toEntity())
    }

    override suspend fun upsertManualWeight(date: LocalDate, weightKg: Double) = withContext(Dispatchers.IO) {
        require(weightKg.isFinite() && weightKg in 10.0..1_000.0) { "Enter a valid body weight." }
        val instant = date.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        dao.upsertWeightEntry(
            WeightEntry("manual-$date", date, instant, weightKg, WeightSource.MANUAL).toEntity()
        )
    }

    override suspend fun deleteManualWeight(date: LocalDate) = withContext(Dispatchers.IO) {
        dao.deleteWeightEntry("manual-$date")
    }

    override suspend fun syncHealthHistory(force: Boolean): Result<Unit> = runCatching {
        historySyncMutex.withLock {
            require(healthConnect.isAvailable()) { healthConnect.availabilityMessage() }
            require(healthConnect.hasCorePermissions()) { "Health Connect activity permission not granted" }
            val today = LocalDate.now()
            if (!force && withContext(Dispatchers.IO) { dao.metadata(HEALTH_HISTORY_SYNC_DAY_KEY) } == today.toString()) {
                return@withLock
            }
            val start = today.minusDays(27)
            val imported = healthConnect.readHealthHistory(start, today)
            withContext(Dispatchers.IO) {
                val reports = imported.dailySummaries.map { (date, summary) ->
                    val existing = dao.dailyReport(date.toString()) ?: DailyReportEntity(date = date.toString())
                    existing.withActivityHealth(summary)
                }
                dao.replaceImportedHealthHistory(
                    start.toString(), today.toString(), reports,
                    imported.weights.map(WeightEntry::toEntity),
                    imported.walkingSessions.map(WalkingSessionSample::toEntity)
                )
                dao.upsertMetadata(AppMetadataEntity(HEALTH_HISTORY_SYNC_DAY_KEY, today.toString()))
                dao.upsertMetadata(AppMetadataEntity(HEALTH_HISTORY_SYNC_STATUS_KEY, java.time.Instant.now().toString()))
            }
        }
    }

    override suspend fun healthHistoryStatus(): String? = withContext(Dispatchers.IO) {
        dao.metadata(HEALTH_HISTORY_SYNC_STATUS_KEY)
    }

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
        const val HEALTH_HISTORY_SYNC_DAY_KEY = "health_history_sync_day_v1"
        const val HEALTH_HISTORY_SYNC_STATUS_KEY = "health_history_sync_status_v1"
    }
}

private data class HealthHistoryState(
    val reports: List<DailyReportEntity>,
    val nutrition: List<DailyNutritionHistoryRow>,
    val weights: List<WeightEntryEntity>,
    val walking: List<WalkingSessionSampleEntity>
)

private data class PlannerRepositoryInput(
    val goals: UserGoals,
    val nutrition: NutritionSummary,
    val spending: DailySpendingTotals,
    val projectedBurn: Double?,
    val loggedServings: Map<String, Double>
)

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

private fun DailyReportEntity.withActivityHealth(health: HealthSummary) = copy(
    steps = health.steps,
    distanceKm = health.distanceKm,
    activeCalories = health.activeCalories,
    totalCalories = health.totalCalories,
    exerciseSessions = health.exerciseSessions,
    exerciseMinutes = health.exerciseMinutes,
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

package com.littleone.dailycutreport

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.Instant

interface DailyCutRepository {
    suspend fun initialize()
    fun observeReport(date: LocalDate): Flow<DailyReport>
    fun observeBurnForecast(date: LocalDate): Flow<BurnForecast?> = flowOf(null)
    fun observeFoodLogs(date: LocalDate): Flow<List<FoodLogSnapshot>>
    fun observeProducts(query: String): Flow<List<ProductEntity>>
    fun observeRecentProducts(): Flow<List<ProductEntity>>
    fun observeFavoriteProducts(): Flow<List<ProductEntity>>
    fun observePlannerProducts(): Flow<List<ProductEntity>>
    fun observeGoals(): Flow<UserGoals>
    fun observeGoals(date: LocalDate): Flow<UserGoals> = observeGoals()
    fun observeGoalAssistant(): Flow<GoalAssistantState?> = flowOf(null)
    suspend fun previewGoalSuggestion(profile: GoalAssistantProfile): GoalSuggestion = error("Goal assistant unavailable")
    suspend fun applyGoalSuggestion(profile: GoalAssistantProfile) {}
    suspend fun adaptGoals() {}
    suspend fun stopGoalAdaptation(restorePrevious: Boolean) {}
    fun observeHealthProfile(): Flow<HealthProfile>
    fun observeSpending(date: LocalDate): Flow<DailySpending>
    fun observeHealthDashboard(date: LocalDate): Flow<HealthDashboard>
    suspend fun updateGoals(goals: UserGoals)
    suspend fun recommendations(date: LocalDate): RecommendationResult
    suspend fun nutritionForDate(date: LocalDate): NutritionSummary
    suspend fun refreshHealth(date: LocalDate): Result<Unit>
    suspend fun lookupProduct(barcode: String): ProductWithExtras?
    suspend fun getProduct(productId: String): ProductWithExtras?
    suspend fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>): ProductMutationResult
    suspend fun setProductFavorite(productId: String, favorite: Boolean)
    suspend fun updatePlannerSettings(settings: PlannerProductSettings)
    suspend fun addProduct(
        date: LocalDate,
        product: ProductWithExtras,
        quantity: Double,
        actualPaidTotalMicros: Long? = null,
        excludeCostFromBudget: Boolean = false,
        enteredUnit: QuantityUnit = QuantityUnit.SERVINGS,
        enteredAmount: Double = quantity
    ): FoodMutationResult
    suspend fun setPreferredLogUnit(productId: String, unit: QuantityUnit)
    suspend fun loadCartDraft(): BulkDraft
    suspend fun saveCartDraft(draft: BulkDraft)
    suspend fun loadPendingProductDraft(): PendingProductDraft? = null
    suspend fun savePendingProductDraft(draft: PendingProductDraft?) = Unit
    suspend fun addBulkPurchase(
        date: LocalDate,
        label: String,
        entries: List<BulkLogSelection>,
        actualPaidTotalMicros: Long? = null,
        excludeCostFromBudget: Boolean = false
    ): FoodMutationResult
    suspend fun addProducts(date: LocalDate, entries: List<BulkLogSelection>): FoodMutationResult
    suspend fun updateFoodLog(edit: FoodQuantityEdit): FoodMutationResult
    suspend fun deleteFoodLog(id: Long): FoodMutationResult
    suspend fun restoreFoodLog(deleted: DeletedFoodLogSnapshot): FoodMutationResult
    suspend fun deleteFoodLogGroup(mealId: String): FoodGroupMutationResult
    suspend fun restoreFoodLogGroup(deleted: DeletedFoodLogGroup): FoodGroupMutationResult
    suspend fun exportBackup(uri: android.net.Uri, password: CharArray): Result<Unit>
    suspend fun restoreBackup(uri: android.net.Uri, password: CharArray): Result<Unit>
    fun healthConnectAvailable(): Boolean
    suspend fun healthCorePermissionsGranted(): Boolean
    suspend fun healthNutritionPermissionGranted(): Boolean
    suspend fun healthNutritionWritePermissionGranted(): Boolean
    suspend fun syncNutritionToHealthConnect(date: LocalDate): Result<HealthWriteSummary>
    suspend fun retryPendingNutritionSync()
    suspend fun nutritionSyncStatus(): String?
    suspend fun healthWeightPermissionGranted(): Boolean
    suspend fun healthWeightWritePermissionGranted(): Boolean = false
    suspend fun syncWeights() {}
    suspend fun weightSyncStatus(): String? = null
    suspend fun updateHealthProfile(profile: HealthProfile)
    suspend fun addManualWeight(date: LocalDate, time: LocalTime, weightKg: Double)
    suspend fun deleteManualWeight(entryId: String)
    suspend fun syncHealthHistory(force: Boolean = false): Result<Unit>
    suspend fun ensureHealthBootstrap(): Result<Unit>
    suspend fun healthHistoryStatus(): String?
}

class DefaultDailyCutRepository(
    private val context: Context,
    private val dao: NutritionDao,
    private val healthConnect: HealthDataSource,
    private val legacyImporter: LegacyReportImporter,
    private val catalogImporter: ProductCatalogImporter,
    private val backupManager: AppBackupManager
) : DailyCutRepository {
    private val initializationMutex = Mutex()
    private var initialized = false
    private val nutritionSync = NutritionSyncCoordinator(dao, healthConnect)
    private val weightSync = WeightSyncCoordinator(dao, healthConnect)
    private val goalAssistant = GoalAssistantStore(dao)
    private val mealPlanner = OfflineMealPlanner()
    private val healthAnalytics = HealthAnalyticsEngine()
    private val burnForecastEngine = DailyBurnForecastEngine()
    private val historySyncMutex = Mutex()
    private val plannerSettingsMutex = Mutex()
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

    override fun observeBurnForecast(date: LocalDate): Flow<BurnForecast?> = combine(
        dao.observeMetadata(burnForecastMetadataKey(date)),
        dao.observeDailyReport(date.toString())
    ) { encoded, report ->
        BurnForecastCodec.decode(encoded.orEmpty())?.takeIf { it.date == date }
            ?: report?.totalCalories?.takeIf { date < LocalDate.now() && it > 0.0 }?.let { burn ->
                BurnForecast(
                    date, burn, burn, burn, burn, BurnForecastSource.ACTUAL,
                    BurnForecastConfidence.HIGH, 0, report.savedAtEpochMs
                )
            }
    }.flowOn(Dispatchers.IO)

    override fun observeFoodLogs(date: LocalDate): Flow<List<FoodLogSnapshot>> =
        dao.observeLogsForDate(date.toString())
            .map { logs -> logs.map(DailyFoodLogEntity::toDomainSnapshot) }
            .flowOn(Dispatchers.IO)

    override fun observeProducts(query: String): Flow<List<ProductEntity>> =
        dao.observeProducts(query.trim().escapeLikePattern()).flowOn(Dispatchers.IO)

    override fun observeRecentProducts(): Flow<List<ProductEntity>> =
        dao.observeRecentProducts().flowOn(Dispatchers.IO)

    override fun observePlannerProducts(): Flow<List<ProductEntity>> =
        dao.observePlannerProducts().flowOn(Dispatchers.IO)

    override fun observeFavoriteProducts(): Flow<List<ProductEntity>> =
        dao.observeFavoriteProducts().flowOn(Dispatchers.IO)

    override fun observeGoals(): Flow<UserGoals> = dao.observeUserGoals()
        .map { (it ?: UserGoalsEntity()).toDomain().sanitized() }
        .flowOn(Dispatchers.IO)

    override fun observeGoalAssistant(): Flow<GoalAssistantState?> = dao.observeMetadata(GoalAssistantState.KEY)
        .map { raw -> raw?.let { runCatching { GoalAssistantCodec.decode(it) }.getOrNull() } }.flowOn(Dispatchers.IO)

    override fun observeGoals(date: LocalDate): Flow<UserGoals> = combine(observeGoals(), observeGoalAssistant()) { goals, state ->
        if (date < LocalDate.now() && state != null) state.goalsFor(date, goals) else goals
    }
    override suspend fun previewGoalSuggestion(profile: GoalAssistantProfile) = withContext(Dispatchers.IO) { goalAssistant.preview(profile) }
    override suspend fun applyGoalSuggestion(profile: GoalAssistantProfile) = withContext(Dispatchers.IO) {
        goalAssistant.apply(profile)
        DailyCutWidgetUpdater.updateAll(context)
    }
    override suspend fun adaptGoals() = withContext(Dispatchers.IO) {
        goalAssistant.adapt()
        DailyCutWidgetUpdater.updateAll(context)
    }
    override suspend fun stopGoalAdaptation(restorePrevious: Boolean) = withContext(Dispatchers.IO) {
        goalAssistant.stop(restorePrevious)
        DailyCutWidgetUpdater.updateAll(context)
    }

    override fun observeHealthProfile(): Flow<HealthProfile> = dao.observeHealthProfile()
        .map { (it ?: HealthProfileEntity()).toDomain() }
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
            history, observeGoals(date), dao.observeHealthProfile(), dao.observeMetadata(HEALTH_HISTORY_SYNC_STATUS_KEY),
            dao.observeMetadata(burnForecastMetadataKey(date))
        ) { state, goals, profileEntity, historyStatus, forecastJson ->
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
                historyLastSynced = historyStatus,
                burnForecast = BurnForecastCodec.decode(forecastJson.orEmpty())
            )
        }.flowOn(Dispatchers.Default)
    }

    override suspend fun updateGoals(goals: UserGoals) = withContext(Dispatchers.IO) {
        goals.requireValid()
        goalAssistant.manual(goals)
        DailyCutWidgetUpdater.updateAll(context)
    }

    override suspend fun recommendations(date: LocalDate): RecommendationResult = withContext(Dispatchers.Default) {
        val dateKey = date.toString()
        val input = withContext(Dispatchers.IO) {
            val current = (dao.userGoals() ?: UserGoalsEntity()).toDomain()
            val goals = if (date < LocalDate.now()) goalAssistant.state().goalsFor(date, current) else current
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
        val raw = healthConnect.readDailySummary(date)
        val today = LocalDate.now()
        val refreshedAt = raw.recordedThroughEpochMs?.let(Instant::ofEpochMilli) ?: Instant.now()
        val completed = withContext(Dispatchers.IO) {
            if (date == today) dao.dailyReports(today.minusDays(28).toString(), today.minusDays(1).toString())
                .map { CompletedBurnDay(LocalDate.parse(it.date), it.totalCalories) }
            else emptyList()
        }
        val forecast = burnForecastEngine.forecast(
            date = date,
            today = today,
            refreshedAt = refreshedAt,
            zone = ZoneId.systemDefault(),
            liveBurnCalories = raw.totalCalories,
            providerFullDayCalories = raw.providerFullDayCalories,
            completedDays = completed
        )
        val forecastStatus = when (forecast.source) {
            BurnForecastSource.ACTUAL -> "Complete-day Health Connect burn"
            BurnForecastSource.HISTORICAL_REMAINDER ->
                "Estimated final burn · ${forecast.confidence.name.lowercase()} confidence · ${forecast.sampleDays} completed days"
            BurnForecastSource.PROVIDER_FALLBACK -> "Provider full-day estimate · low confidence"
            BurnForecastSource.UNAVAILABLE -> "Final burn estimate unavailable"
        }
        val summary = raw.copy(
            totalCalories = forecast.estimatedFinalCalories ?: 0.0,
            healthConnectStatus = "${raw.healthConnectStatus}. $forecastStatus",
            providerFullDayCalories = null
        )
        withContext(Dispatchers.IO) {
            val existing = dao.dailyReport(date.toString()) ?: DailyReportEntity(date = date.toString())
            dao.upsertDailyReport(existing.withHealth(summary))
            dao.upsertMetadata(AppMetadataEntity(burnForecastMetadataKey(date), BurnForecastCodec.encode(forecast)))
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
        plannerSettingsMutex.withLock {
            require(product.purchasePriceMicros == null || product.purchasePriceMicros >= 0L) { "Price cannot be negative." }
            require(product.purchaseUnitServings > 0.0) { "Minimum purchase servings must be greater than zero." }
            val quantitySpec = product.quantitySpec()
            require(!quantitySpec.mode.measureAvailable || quantitySpec.measureAvailable) { "Enter a valid weight or volume basis." }
            require(quantitySpec.supports(product.preferredQuantityUnit())) { "Preferred logging unit is unavailable." }
            require(product.fixedPurchaseUnits in 1..6) { "Fixed purchase units must be between 1 and 6." }
            require(product.plannerItemType in PlannerItemType.entries.map(PlannerItemType::name)) { "Invalid planner item type." }
            require(!product.alwaysIncludeInPlanner || product.includeInPlanner) { "A fixed planner item must be enabled for planning." }
            val existingProducts = dao.allProducts()
            val existingFixedTotal = existingProducts
                .sumOf { if (it.includeInPlanner && it.alwaysIncludeInPlanner) it.fixedPurchaseUnits else 0 }
            val fixedTotal = existingProducts
                .filterNot { it.productId == product.productId }
                .sumOf { if (it.includeInPlanner && it.alwaysIncludeInPlanner) it.fixedPurchaseUnits else 0 } +
                if (product.includeInPlanner && product.alwaysIncludeInPlanner) product.fixedPurchaseUnits else 0
            require(fixedTotal <= 20 || fixedTotal <= existingFixedTotal) {
                "Fixed products may total at most 20 purchase units."
            }
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
    }

    override suspend fun setProductFavorite(productId: String, favorite: Boolean) = withContext(Dispatchers.IO) {
        check(dao.updateProductFavorite(productId, favorite) == 1) { "Product no longer exists." }
    }

    override suspend fun updatePlannerSettings(settings: PlannerProductSettings) = withContext(Dispatchers.IO) {
        plannerSettingsMutex.withLock {
            require(settings.fixedPurchaseUnits in 1..6) { "Fixed purchase units must be between 1 and 6." }
            require(!settings.fixedInPlanner || settings.includeInPlanner) { "A fixed item must be included in planning." }
            val itemType = settings.itemType.name
            val products = dao.allProducts()
            val existingFixedTotal = products
                .sumOf { if (it.includeInPlanner && it.alwaysIncludeInPlanner) it.fixedPurchaseUnits else 0 }
            val fixedTotal = products.sumOf { product ->
                when {
                    product.productId == settings.productId ->
                        if (settings.includeInPlanner && settings.fixedInPlanner) settings.fixedPurchaseUnits else 0
                    product.includeInPlanner && product.alwaysIncludeInPlanner -> product.fixedPurchaseUnits
                    else -> 0
                }
            }
            require(fixedTotal <= 20 || fixedTotal <= existingFixedTotal) {
                "Fixed products may total at most 20 purchase units."
            }
            check(dao.updateProductPlannerSettings(
                productId = settings.productId,
                included = settings.includeInPlanner,
                itemType = itemType,
                fixed = settings.fixedInPlanner,
                fixedUnits = settings.fixedPurchaseUnits
            ) == 1) { "Product no longer exists." }
        }
    }

    override suspend fun addProduct(
        date: LocalDate,
        product: ProductWithExtras,
        quantity: Double,
        actualPaidTotalMicros: Long?,
        excludeCostFromBudget: Boolean,
        enteredUnit: QuantityUnit,
        enteredAmount: Double
    ): FoodMutationResult = withContext(Dispatchers.IO) {
        require(quantity > 0.0) { "Quantity must be greater than zero" }
        require(enteredAmount.isFinite() && enteredAmount > 0.0) { "Entered amount must be greater than zero." }
        val spec = product.product.quantitySpec()
        require(spec.supports(enteredUnit)) { "That unit is not available for this product." }
        require(spec.servingsFor(enteredAmount, enteredUnit)?.let { quantitiesEquivalent(it, quantity) } == true) {
            "Entered amount does not match the normalized quantity."
        }
        require(actualPaidTotalMicros == null || actualPaidTotalMicros >= 0L) { "Actual paid cannot be negative." }
        val result = completeFoodMutation(dao.addProductToDate(
            date.toString(), product.product, quantity, product.extras,
            enteredUnit.name, enteredAmount, actualPaidTotalMicros, excludeCostFromBudget
        ))
        dao.updatePreferredLogUnit(product.product.productId, enteredUnit.name)
        result
    }

    override suspend fun setPreferredLogUnit(productId: String, unit: QuantityUnit) = withContext(Dispatchers.IO) {
        val product = dao.productById(productId) ?: return@withContext
        require(product.quantitySpec().supports(unit)) { "That unit is not available for this product." }
        check(dao.updatePreferredLogUnit(productId, unit.name) == 1) { "Product no longer exists." }
    }

    override suspend fun loadCartDraft(): BulkDraft = withContext(Dispatchers.IO) {
        val encoded = dao.metadata(CART_DRAFT_KEY) ?: return@withContext BulkDraft()
        CartDraftCodec.decode(encoded) { productId -> dao.productById(productId) }
    }

    override suspend fun saveCartDraft(draft: BulkDraft) = withContext(Dispatchers.IO) {
        dao.upsertMetadata(AppMetadataEntity(CART_DRAFT_KEY, CartDraftCodec.encode(draft)))
    }

    override suspend fun loadPendingProductDraft(): PendingProductDraft? = withContext(Dispatchers.IO) {
        val encoded = dao.metadata(PRODUCT_DRAFT_KEY) ?: return@withContext null
        val decoded = ProductDraftCodec.decode(encoded) { productId ->
            dao.productById(productId)?.let { ProductWithExtras(it, dao.extrasForProduct(productId)) }
        }
        if (decoded == null) {
            dao.deleteMetadata(PRODUCT_DRAFT_KEY)
            error("The unfinished product draft was invalid or its product no longer exists, so it was discarded.")
        }
        decoded
    }

    override suspend fun savePendingProductDraft(draft: PendingProductDraft?) = withContext(Dispatchers.IO) {
        if (draft == null || !ProductDraftCodec.isMeaningful(draft.draft)) {
            dao.deleteMetadata(PRODUCT_DRAFT_KEY)
        } else {
            dao.upsertMetadata(AppMetadataEntity(PRODUCT_DRAFT_KEY, ProductDraftCodec.encode(draft)))
        }
    }

    override suspend fun updateFoodLog(edit: FoodQuantityEdit): FoodMutationResult = withContext(Dispatchers.IO) {
        require(edit.quantity > 0.0) { "Quantity must be greater than zero" }
        require(edit.enteredAmount.isFinite() && edit.enteredAmount > 0.0) { "Entered amount must be greater than zero." }
        require(edit.actualPaidTotalMicros == null || edit.actualPaidTotalMicros >= 0L) { "Actual paid cannot be negative." }
        val existing = dao.foodLogById(edit.id) ?: error("Food entry no longer exists.")
        val enteredUnit = QuantityUnit.entries.firstOrNull { it.name == edit.enteredUnit }
            ?: error("Unsupported quantity unit.")
        val spec = existing.toDomainSnapshot().quantitySpec()
        require(spec.supports(enteredUnit)) { "That unit is not available for this entry." }
        require(spec.servingsFor(edit.enteredAmount, enteredUnit)?.let { quantitiesEquivalent(it, edit.quantity) } == true) {
            "Entered amount does not match the normalized quantity."
        }
        val mutation = dao.updateFoodLogQuantitySnapshot(
            edit.id, edit.quantity, edit.enteredUnit, edit.enteredAmount,
            edit.actualPaidTotalMicros, edit.excludeCostFromBudget
        )
            ?: error("Food entry no longer exists.")
        existing.productId?.let { dao.updatePreferredLogUnit(it, edit.enteredUnit) }
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
        validateQuantitySelections(entries)
        require(actualPaidTotalMicros == null || actualPaidTotalMicros >= 0L) { "Actual paid cannot be negative." }
        val result = completeFoodMutation(dao.addBulkPurchaseToDate(
            date = date.toString(),
            groupId = java.util.UUID.randomUUID().toString(),
            groupLabel = label.trim().ifBlank { "Bulk purchase" },
            selections = entries,
            actualPaidTotalMicros = actualPaidTotalMicros,
            excludeCostFromBudget = excludeCostFromBudget
        ))
        entries.forEach { dao.updatePreferredLogUnit(it.productId, it.enteredUnit) }
        result
    }

    override suspend fun addProducts(date: LocalDate, entries: List<BulkLogSelection>): FoodMutationResult = withContext(Dispatchers.IO) {
        require(entries.isNotEmpty()) { "Scan at least one product." }
        require(entries.all { it.quantity.isFinite() && it.quantity > 0.0 }) { "Quantities must be greater than zero." }
        validateQuantitySelections(entries)
        val result = completeFoodMutation(dao.addMultipleProductsToDate(date.toString(), entries))
        entries.forEach { dao.updatePreferredLogUnit(it.productId, it.enteredUnit) }
        result
    }

    private suspend fun validateQuantitySelections(entries: List<BulkLogSelection>) {
        entries.forEach { entry ->
            require(entry.enteredAmount.isFinite() && entry.enteredAmount > 0.0) {
                "Entered amounts must be greater than zero."
            }
            val product = dao.productById(entry.productId) ?: error("Product no longer exists.")
            val unit = QuantityUnit.entries.firstOrNull { it.name == entry.enteredUnit }
                ?: error("Unsupported quantity unit.")
            val spec = product.quantitySpec()
            require(spec.supports(unit)) { "That unit is not available for ${product.name}." }
            require(spec.servingsFor(entry.enteredAmount, unit)?.let { quantitiesEquivalent(it, entry.quantity) } == true) {
                "Entered amount does not match the normalized quantity for ${product.name}."
            }
        }
    }

    override suspend fun deleteFoodLog(id: Long): FoodMutationResult = withContext(Dispatchers.IO) {
        val mutation = dao.deleteFoodLogSnapshot(id) ?: error("Food entry no longer exists.")
        completeFoodMutation(mutation)
    }

    override suspend fun restoreFoodLog(deleted: DeletedFoodLogSnapshot): FoodMutationResult = withContext(Dispatchers.IO) {
        val entity = deleted.log.toEntity()
        completeFoodMutation(dao.restoreFoodLogSnapshot(DeletedFoodLogEntity(entity, deleted.extras)))
    }

    override suspend fun deleteFoodLogGroup(mealId: String): FoodGroupMutationResult = withContext(Dispatchers.IO) {
        completeFoodGroupMutation(dao.deleteFoodLogGroup(mealId) ?: error("Bulk order no longer exists."))
    }

    override suspend fun restoreFoodLogGroup(deleted: DeletedFoodLogGroup): FoodGroupMutationResult = withContext(Dispatchers.IO) {
        completeFoodGroupMutation(dao.restoreFoodLogGroup(deleted.logs.map {
            DeletedFoodLogEntity(it.log.toEntity(), it.extras)
        }))
    }

    override suspend fun exportBackup(uri: android.net.Uri, password: CharArray): Result<Unit> = runCatching {
        backupManager.export(uri, password)
    }

    override suspend fun restoreBackup(uri: android.net.Uri, password: CharArray): Result<Unit> = runCatching {
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
        syncWeights()
        DailyCutWidgetUpdater.updateAll(context)
    }

    override suspend fun healthWeightWritePermissionGranted() = healthConnect.hasWeightWritePermission()
    override suspend fun syncWeights() = withContext(Dispatchers.IO) { weightSync.sync() }
    override suspend fun weightSyncStatus() = withContext(Dispatchers.IO) { dao.metadata(WeightSyncCoordinator.STATUS) }

    override suspend fun nutritionSyncStatus(): String? = nutritionSync.status()

    override suspend fun healthWeightPermissionGranted(): Boolean = healthConnect.hasWeightPermission()

    override suspend fun updateHealthProfile(profile: HealthProfile) = withContext(Dispatchers.IO) {
        require(profile.targetWeightKg == null || profile.targetWeightKg.isFinite() && profile.targetWeightKg > 0.0) {
            "Target weight must be greater than zero."
        }
        dao.upsertHealthProfile(profile.toEntity())
    }

    override suspend fun addManualWeight(date: LocalDate, time: LocalTime, weightKg: Double): Unit = withContext(Dispatchers.IO) {
        require(weightKg.isFinite() && weightKg in 10.0..1_000.0) { "Enter a valid body weight." }
        val instant = date.atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        dao.upsertWeightEntry(
            WeightEntry("manual-${java.util.UUID.randomUUID()}", date, instant, weightKg, WeightSource.MANUAL).toEntity()
        )
        syncScope.launch { syncWeights() }
    }

    override suspend fun deleteManualWeight(entryId: String): Unit = withContext(Dispatchers.IO) {
        require(entryId.startsWith("manual-")) { "Only manual weight entries can be deleted." }
        dao.deleteWeightEntry(entryId)
        syncScope.launch { syncWeights() }
    }

    override suspend fun syncHealthHistory(force: Boolean): Result<Unit> = runCatching {
        historySyncMutex.withLock {
            require(healthConnect.isAvailable()) { healthConnect.availabilityMessage() }
            require(healthConnect.hasCorePermissions()) { "Health Connect activity permission not granted" }
            val today = LocalDate.now()
            if (!force && withContext(Dispatchers.IO) { dao.metadata(HEALTH_HISTORY_SYNC_DAY_KEY) } == today.toString()) {
                return@withLock
            }
            val start = today.minusDays(29)
            val imported = healthConnect.readHealthHistory(start, today)
            withContext(Dispatchers.IO) {
                val reports = imported.dailySummaries.map { (date, summary) ->
                    val existing = dao.dailyReport(date.toString()) ?: DailyReportEntity(date = date.toString())
                    existing.withHealth(summary)
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

    override suspend fun ensureHealthBootstrap(): Result<Unit> = runCatching {
        if (!healthConnect.isAvailable() || !healthConnect.hasCorePermissions()) return@runCatching
        val permissionFingerprint = "29:w${healthConnect.hasWeightPermission()}:n${healthConnect.hasNutritionPermission()}"
        val complete = withContext(Dispatchers.IO) { dao.metadata(HEALTH_BOOTSTRAP_KEY) }
        if (complete == permissionFingerprint) return@runCatching
        syncHealthHistory(force = true).getOrThrow()
        withContext(Dispatchers.IO) {
            dao.upsertMetadata(AppMetadataEntity(HEALTH_BOOTSTRAP_KEY, permissionFingerprint))
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

    private suspend fun completeFoodGroupMutation(mutation: DailyNutritionGroupMutation): FoodGroupMutationResult {
        val date = LocalDate.parse(mutation.date)
        val result = FoodGroupMutationResult(
            date,
            mutation.before.toSummary(emptyMap()),
            mutation.after.toSummary(emptyMap()),
            DeletedFoodLogGroup(mutation.deleted.map {
                DeletedFoodLogSnapshot(it.log.toDomainSnapshot(), it.extras)
            })
        )
        DailyCutWidgetUpdater.updateAll(context)
        nutritionSync.sync(date)
        return result
    }

    private companion object {
        const val CART_DRAFT_KEY = "pending_cart_v1"
        const val PRODUCT_DRAFT_KEY = "pending_product_draft_v1"
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
    quantityMode = quantityMode,
    measurePerServing = measurePerServing,
    enteredUnit = enteredUnit,
    enteredAmount = enteredAmount,
    caloriesPerServing = caloriesPerServing,
    proteinGPerServing = proteinGPerServing,
    sodiumMgPerServing = sodiumMgPerServing,
    carbsGPerServing = carbsGPerServing,
    fatGPerServing = fatGPerServing,
    sugarGPerServing = sugarGPerServing,
    fiberGPerServing = fiberGPerServing,
    saturatedFatGPerServing = saturatedFatGPerServing,
    catalogCostPerServingMicros = catalogCostPerServingMicros,
    catalogEstimatedTotalMicros = catalogEstimatedTotalMicros,
    actualPaidTotalMicros = actualPaidTotalMicros,
    excludeCostFromBudget = excludeCostFromBudget,
    mealId = mealId,
    mealName = mealName,
    loggedAt = loggedAt
)

private fun String.escapeLikePattern(): String = replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private const val HEALTH_BOOTSTRAP_KEY = "health_bootstrap_0_11"

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

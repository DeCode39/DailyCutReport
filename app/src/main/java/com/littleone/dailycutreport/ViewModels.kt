@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.littleone.dailycutreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.time.LocalDate

class ReportDateViewModel : ViewModel() {
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate

    fun select(date: LocalDate) {
        _selectedDate.value = date.coerceAtMost(LocalDate.now())
    }

    fun previous() = select(_selectedDate.value.minusDays(1))
    fun next() = select(_selectedDate.value.plusDays(1))
}

data class TodayUiState(
    val report: DailyReport = DailyReport(LocalDate.now()),
    val logs: List<FoodLogSnapshot> = emptyList(),
    val goals: UserGoals = UserGoals(),
    val spending: DailySpending = DailySpending(),
    val recommendations: RecommendationResult? = null,
    val planning: Boolean = false,
    val message: String? = null
) {
    val calorieAllowance: Double? get() = goals.calorieAllowance(report.projectedBurnCalories)
    val targets: DailyNutritionTargets get() = goals.targetsFor(report.projectedBurnCalories)
    val planningAvailable: Boolean get() = calorieAllowance != null
}

class TodayViewModel(
    private val repository: DailyCutRepository,
    selectedDate: StateFlow<LocalDate>
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val recommendations = MutableStateFlow<RecommendationResult?>(null)
    private val planning = MutableStateFlow(false)
    init {
        viewModelScope.launch {
            runCatching { repository.initialize() }
                .onFailure { message.value = "Could not initialize local data: ${it.message}" }
        }
    }

    val uiState: StateFlow<TodayUiState> = combine(
        selectedDate.flatMapLatest(repository::observeReport),
        selectedDate.flatMapLatest(repository::observeFoodLogs),
        repository.observeGoals(),
        selectedDate.flatMapLatest(repository::observeSpending),
        combine(message, recommendations, planning) { note, plans, busy -> Triple(note, plans, busy) }
    ) { report, logs, goals, spending, transient ->
        TodayUiState(report, logs, goals, spending, transient.second, transient.third, transient.first)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    fun planRemainingDay() {
        if (planning.value) return
        viewModelScope.launch {
            planning.value = true
            runCatching { repository.recommendations(uiState.value.report.date) }
                .onSuccess { recommendations.value = it }
                .onFailure { message.value = it.message ?: "Could not generate suggestions." }
            planning.value = false
        }
    }

    fun clearRecommendations() { recommendations.value = null }

    suspend fun saveReport() = repository.saveReport(uiState.value.report)
    suspend fun writeReport(uri: android.net.Uri) = repository.writeReport(uri, uiState.value.report)
    suspend fun createShareUri() = repository.createShareUri(uiState.value.report)
    fun clearMessage() { message.value = null }
}

sealed interface FoodWorkflowState {
    data object Idle : FoodWorkflowState
    data class ConfirmQuantity(val product: ProductWithExtras) : FoodWorkflowState
    data class EditProduct(val draft: ProductEditorDraft) : FoodWorkflowState
    data class EditQuantity(val log: FoodLogSnapshot) : FoodWorkflowState
    data class ReviewMultiScan(val items: List<MultiScanItem>) : FoodWorkflowState
}

data class ProductEditorDraft(
    val existing: ProductWithExtras? = null,
    val saveTarget: ProductSaveTarget = ProductSaveTarget.STANDALONE_LOG,
    val barcode: String = "",
    val name: String = "",
    val brand: String = "",
    val servingLabel: String = "1 serving",
    val calories: String = "",
    val protein: String = "",
    val sodium: String = "",
    val carbs: String = "",
    val fat: String = "",
    val sugar: String = "",
    val fiber: String = "",
    val saturatedFat: String = "",
    val purchasePrice: String = "",
    val purchaseServings: String = "1",
    val includeInPlanner: Boolean = true,
    val plannerItemType: PlannerItemType = PlannerItemType.FOOD,
    val alwaysIncludeInPlanner: Boolean = false,
    val fixedPurchaseUnits: String = "1",
    val favorite: Boolean = false,
    val extras: String = "",
    val ocrDraft: OcrNutritionDraft? = null
) {
    companion object {
        fun create(initialBarcode: String, existing: ProductWithExtras?, saveTarget: ProductSaveTarget): ProductEditorDraft {
            val product = existing?.product
            return ProductEditorDraft(
                existing = existing,
                saveTarget = saveTarget,
                barcode = product?.barcode ?: initialBarcode,
                name = product?.name.orEmpty(),
                brand = product?.brand.orEmpty(),
                servingLabel = product?.servingLabel ?: "1 serving",
                calories = product?.calories.editableNumber(),
                protein = product?.proteinG.editableNumber(),
                sodium = product?.sodiumMg.editableNumber(),
                carbs = product?.carbsG.editableNumber(),
                fat = product?.fatG.editableNumber(),
                sugar = product?.sugarG.editableNumber(),
                fiber = product?.fiberG.editableNumber(),
                saturatedFat = product?.saturatedFatG.editableNumber(),
                purchasePrice = product?.purchasePriceMicros?.let { it / 1_000_000.0 }?.editableNumber().orEmpty(),
                purchaseServings = product?.purchaseUnitServings?.editableNumber().ifNullOrBlank("1"),
                includeInPlanner = product?.includeInPlanner ?: true,
                plannerItemType = PlannerItemType.entries.firstOrNull { it.name == product?.plannerItemType } ?: PlannerItemType.FOOD,
                alwaysIncludeInPlanner = product?.alwaysIncludeInPlanner ?: false,
                fixedPurchaseUnits = (product?.fixedPurchaseUnits ?: 1).toString(),
                favorite = product?.favorite ?: false,
                extras = existing?.extras?.joinToString("\n") { "${it.name}=${it.value} ${it.unit}" }.orEmpty()
            )
        }
    }
}

private fun Double?.editableNumber(): String = this?.takeUnless { it == 0.0 }?.let(::formatDecimal).orEmpty()
private fun String?.ifNullOrBlank(fallback: String): String = if (isNullOrBlank()) fallback else this

sealed interface FoodUiEvent {
    data class Message(val text: String, val undo: FoodUndo? = null) : FoodUiEvent
    data class Threshold(val text: String) : FoodUiEvent
    data object ScannerNeedsEditor : FoodUiEvent
    data object ResumeScanner : FoodUiEvent
}

sealed interface FoodUndo {
    data class Single(val value: DeletedFoodLogSnapshot) : FoodUndo
    data class Group(val value: DeletedFoodLogGroup) : FoodUndo
}

data class FoodsUiState(
    val date: LocalDate = LocalDate.now(),
    val logs: List<FoodLogSnapshot> = emptyList(),
    val products: List<ProductEntity> = emptyList(),
    val recentProducts: List<ProductEntity> = emptyList(),
    val favoriteProducts: List<ProductEntity> = emptyList(),
    val goals: UserGoals = UserGoals(),
    val query: String = "",
    val workflow: FoodWorkflowState = FoodWorkflowState.Idle,
    val mode: FoodMode = FoodMode.NORMAL,
    val bulkDraft: BulkDraft = BulkDraft()
)

private data class FoodsCatalogState(
    val products: List<ProductEntity>,
    val recent: List<ProductEntity>,
    val favorites: List<ProductEntity>,
    val workflow: FoodWorkflowState,
    val mode: FoodMode,
    val bulkDraft: BulkDraft
)

class FoodsViewModel(
    private val repository: DailyCutRepository,
    selectedDate: StateFlow<LocalDate>
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val workflow = MutableStateFlow<FoodWorkflowState>(FoodWorkflowState.Idle)
    private val mode = MutableStateFlow(FoodMode.NORMAL)
    private val bulkDraft = MutableStateFlow(BulkDraft())
    private val _scannerSession = MutableStateFlow(ScannerSessionState())
    val scannerSession: StateFlow<ScannerSessionState> = _scannerSession
    private val _events = MutableSharedFlow<FoodUiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<FoodUiEvent> = _events.asSharedFlow()
    private val thresholdNotifier = MacroThresholdNotifier()
    private val latestGoals = repository.observeGoals()
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserGoals())
    private val latestTargets = combine(
        latestGoals,
        selectedDate.flatMapLatest(repository::observeReport)
    ) { goals, report -> goals.targetsFor(report.projectedBurnCalories) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UserGoals().targetsFor(null))
    private val productSearch = query.debounce(200).distinctUntilChanged().flatMapLatest { value ->
        if (value.isBlank()) flowOf(emptyList()) else repository.observeProducts(value)
    }

    private val catalogState = combine(
        productSearch, repository.observeRecentProducts(), repository.observeFavoriteProducts(),
        combine(workflow, mode, bulkDraft) { active, foodMode, draft -> Triple(active, foodMode, draft) }
    ) { products, recent, favorites, transient ->
        FoodsCatalogState(products, recent, favorites, transient.first, transient.second, transient.third)
    }
    val uiState: StateFlow<FoodsUiState> = combine(
        selectedDate,
        selectedDate.flatMapLatest(repository::observeFoodLogs),
        catalogState,
        latestGoals
    ) { date, logs, catalog, goals ->
        FoodsUiState(
            date, logs, catalog.products, catalog.recent, catalog.favorites, goals, query.value,
            catalog.workflow, catalog.mode, catalog.bulkDraft
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodsUiState())

    fun setQuery(value: String) { query.value = value }

    fun beginScanner(target: ScanTarget) {
        if (_scannerSession.value.items.isEmpty()) {
            _scannerSession.value = ScannerSessionState(target = target)
        } else {
            _scannerSession.value = _scannerSession.value.copy(target = target)
        }
    }

    fun setMultiScan(enabled: Boolean) {
        _scannerSession.value = _scannerSession.value.copy(
            multiEnabled = enabled,
            status = if (enabled) "Multi-scan on · scan the first item" else "Point the camera at a barcode"
        )
    }

    fun handleMultiScanBarcode(barcode: String) {
        val normalized = barcode.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            val saved = repository.lookupProduct(normalized)
            if (saved == null) {
                workflow.value = FoodWorkflowState.EditProduct(
                    ProductEditorDraft.create(normalized, null, ProductSaveTarget.MULTI_SCAN_QUEUE)
                )
                _scannerSession.value = _scannerSession.value.copy(status = "New item · complete product details")
                _events.emit(FoodUiEvent.ScannerNeedsEditor)
            } else {
                addToScannerQueue(saved.product)
            }
        }
    }

    private fun addToScannerQueue(product: ProductEntity) {
        val current = _scannerSession.value
        val existing = current.items.firstOrNull { it.product.productId == product.productId }
        val items = if (existing == null) {
            current.items + MultiScanItem(product, product.purchaseUnitServings.toDisplay())
        } else {
            current.items.map {
                if (it.product.productId == product.productId) {
                    it.copy(
                        quantityText = ((it.quantity ?: product.purchaseUnitServings) +
                            product.purchaseUnitServings).toDisplay()
                    )
                } else it
            }
        }
        _scannerSession.value = current.copy(items = items, status = "${product.name} · ${items.size} unique queued")
    }

    fun finishMultiScan() {
        val session = _scannerSession.value
        if (session.items.isEmpty()) {
            _scannerSession.value = ScannerSessionState(target = session.target)
            return
        }
        if (session.target == ScanTarget.BULK_CART) {
            var draft = bulkDraft.value
            session.items.forEach { scanned ->
                val existing = draft.items.firstOrNull { it.product.productId == scanned.product.productId }
                draft = if (existing == null) {
                    draft.copy(
                        date = draft.date ?: uiState.value.date,
                        items = draft.items + BulkDraftItem(scanned.product, scanned.quantityText)
                    )
                } else {
                    draft.copy(items = draft.items.map {
                        if (it.product.productId == scanned.product.productId) {
                            it.copy(quantityText = ((it.quantity ?: 0.0) + (scanned.quantity ?: 0.0)).toDisplay())
                        } else it
                    })
                }
            }
            bulkDraft.value = draft
            mode.value = FoodMode.BULK
        } else {
            workflow.value = FoodWorkflowState.ReviewMultiScan(session.items)
        }
        _scannerSession.value = ScannerSessionState(target = session.target)
    }

    fun updateMultiScanQuantity(productId: String, value: String) {
        val review = workflow.value as? FoodWorkflowState.ReviewMultiScan ?: return
        workflow.value = review.copy(items = review.items.map {
            if (it.product.productId == productId) it.copy(quantityText = value) else it
        })
    }

    fun updateMultiScanActualPaid(productId: String, value: String) {
        val review = workflow.value as? FoodWorkflowState.ReviewMultiScan ?: return
        workflow.value = review.copy(items = review.items.map {
            if (it.product.productId == productId) it.copy(actualPaidText = value) else it
        })
    }

    fun updateMultiScanBudgetExclusion(productId: String, excluded: Boolean) {
        val review = workflow.value as? FoodWorkflowState.ReviewMultiScan ?: return
        workflow.value = review.copy(items = review.items.map {
            if (it.product.productId == productId) it.copy(excludeCostFromBudget = excluded) else it
        })
    }

    fun confirmMultiScan() {
        val review = workflow.value as? FoodWorkflowState.ReviewMultiScan ?: return
        if (review.items.any { it.quantity == null || !it.actualPaidValid }) return
        viewModelScope.launch {
            runCatching {
                repository.addProducts(
                    uiState.value.date,
                    review.items.map {
                        BulkLogSelection(
                            productId = it.product.productId,
                            quantity = requireNotNull(it.quantity),
                            actualPaidTotalMicros = it.actualPaidTotalMicros,
                            excludeCostFromBudget = it.excludeCostFromBudget
                        )
                    }
                )
            }.onSuccess {
                workflow.value = FoodWorkflowState.Idle
                afterFoodChange(it)
                _events.emit(FoodUiEvent.Message("Logged ${review.items.size} scanned products."))
            }.onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not log scanned products.")) }
        }
    }

    fun toggleFavorite(product: ProductEntity) {
        viewModelScope.launch {
            runCatching { repository.setProductFavorite(product.productId, !product.favorite) }
                .onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not update favorite.")) }
        }
    }

    fun handleBarcode(barcode: String, target: ScanTarget = ScanTarget.STANDALONE) {
        val normalized = barcode.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            val saved = repository.lookupProduct(normalized)
            if (saved == null) {
                workflow.value = FoodWorkflowState.EditProduct(ProductEditorDraft.create(
                    normalized, null,
                    if (target == ScanTarget.BULK_CART) ProductSaveTarget.BULK_CART else ProductSaveTarget.STANDALONE_LOG
                ))
            } else if (target == ScanTarget.BULK_CART) {
                addProductToBulk(saved.product)
            } else workflow.value = FoodWorkflowState.ConfirmQuantity(saved)
        }
    }

    fun selectProduct(product: ProductEntity) {
        viewModelScope.launch {
            repository.getProduct(product.productId)?.let { workflow.value = FoodWorkflowState.ConfirmQuantity(it) }
        }
    }

    fun createProduct() {
        workflow.value = FoodWorkflowState.EditProduct(ProductEditorDraft.create(
            "", null,
            if (mode.value == FoodMode.BULK) ProductSaveTarget.BULK_CART else ProductSaveTarget.STANDALONE_LOG
        ))
    }

    fun setMode(value: FoodMode) { mode.value = value }
    fun scanTarget(): ScanTarget = if (mode.value == FoodMode.BULK) ScanTarget.BULK_CART else ScanTarget.STANDALONE

    fun addProductToBulk(product: ProductEntity) {
        val current = bulkDraft.value
        if (current.items.any { it.product.productId == product.productId }) {
            viewModelScope.launch { _events.emit(FoodUiEvent.Message("${product.name} is already in the bulk cart.")) }
            return
        }
        bulkDraft.value = current.copy(
            date = current.date ?: uiState.value.date,
            items = current.items + BulkDraftItem(product, product.purchaseUnitServings.toDisplay())
        )
    }

    fun removeBulkProduct(productId: String) {
        val current = bulkDraft.value
        val remaining = current.items.filterNot { it.product.productId == productId }
        bulkDraft.value = current.copy(items = remaining, date = current.date.takeIf { remaining.isNotEmpty() })
    }

    fun updateBulkQuantity(productId: String, value: String) {
        bulkDraft.value = bulkDraft.value.copy(items = bulkDraft.value.items.map {
            if (it.product.productId == productId) it.copy(quantityText = value) else it
        })
    }

    fun updateBulkLabel(value: String) { bulkDraft.value = bulkDraft.value.copy(label = value) }
    fun updateBulkPaid(value: String) { bulkDraft.value = bulkDraft.value.copy(actualPaidText = value) }
    fun updateBulkBudgetExclusion(value: Boolean) { bulkDraft.value = bulkDraft.value.copy(excludeCostFromBudget = value) }
    fun discardBulkDraft() { bulkDraft.value = BulkDraft() }

    fun confirmBulkPurchase() {
        val draft = bulkDraft.value
        val date = draft.date ?: return
        viewModelScope.launch {
            runCatching {
                require(draft.isValid) { "Choose at least two items and enter valid quantities and price." }
                val paid = if (draft.actualPaidText.isBlank()) null
                else requireNotNull(parseMoneyMicros(draft.actualPaidText)) { "Enter a valid final checkout total." }
                repository.addBulkPurchase(
                    date = date,
                    label = draft.label,
                    entries = draft.items.map { BulkLogSelection(it.product.productId, requireNotNull(it.quantity)) },
                    actualPaidTotalMicros = paid,
                    excludeCostFromBudget = draft.excludeCostFromBudget
                )
            }
                .onSuccess { result ->
                    workflow.value = FoodWorkflowState.Idle
                    bulkDraft.value = BulkDraft()
                    mode.value = FoodMode.NORMAL
                    afterFoodChange(result)
                    _events.emit(FoodUiEvent.Message("Bulk logged ${draft.items.size} items with one checkout total."))
                }
                .onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not bulk log items.")) }
        }
    }

    fun editProduct(product: ProductEntity) {
        viewModelScope.launch {
            repository.getProduct(product.productId)?.let {
                workflow.value = FoodWorkflowState.EditProduct(
                    ProductEditorDraft.create(product.barcode.orEmpty(), it, ProductSaveTarget.CATALOG_ONLY)
                )
            }
        }
    }
    fun cancelDialogs() { workflow.value = FoodWorkflowState.Idle }

    fun confirmAdd(quantity: Double, actualPaidTotalMicros: Long?, excludeCostFromBudget: Boolean) {
        val selected = (workflow.value as? FoodWorkflowState.ConfirmQuantity)?.product ?: return
        val date = uiState.value.date
        viewModelScope.launch {
            runCatching { repository.addProduct(date, selected, quantity, actualPaidTotalMicros, excludeCostFromBudget) }
                .onSuccess { result ->
                    workflow.value = FoodWorkflowState.Idle
                    afterFoodChange(result)
                    _events.emit(FoodUiEvent.Message("Added ${selected.product.name}."))
                }
                .onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not add food.")) }
        }
    }

    fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>, _quantity: Double) {
        val editor = workflow.value as? FoodWorkflowState.EditProduct ?: return
        viewModelScope.launch {
            runCatching {
                repository.saveProduct(product, extras)
            }.onSuccess { result ->
                when (editor.draft.saveTarget) {
                    ProductSaveTarget.STANDALONE_LOG -> {
                        workflow.value = FoodWorkflowState.ConfirmQuantity(ProductWithExtras(product, extras))
                        _events.emit(FoodUiEvent.Message("Saved ${product.name}. Confirm quantity to add it."))
                    }
                    ProductSaveTarget.BULK_CART -> {
                        addProductToBulk(product)
                        workflow.value = FoodWorkflowState.Idle
                        _events.emit(FoodUiEvent.Message("Saved ${product.name} and added it to the bulk cart."))
                    }
                    ProductSaveTarget.MULTI_SCAN_QUEUE -> {
                        addToScannerQueue(product)
                        workflow.value = FoodWorkflowState.Idle
                        _events.emit(FoodUiEvent.ResumeScanner)
                    }
                    ProductSaveTarget.CATALOG_ONLY -> {
                        bulkDraft.value = bulkDraft.value.copy(items = bulkDraft.value.items.map { item ->
                            if (item.product.productId == result.product.productId) item.copy(product = result.product) else item
                        })
                        workflow.value = FoodWorkflowState.Idle
                        val linked = if (result.linkedEntriesUpdated == 0) "" else " Updated ${result.linkedEntriesUpdated} linked log entr${if (result.linkedEntriesUpdated == 1) "y" else "ies"}."
                        _events.emit(FoodUiEvent.Message("Updated ${product.name}.$linked"))
                    }
                }
            }.onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not save product.")) }
        }
    }

    fun edit(log: FoodLogSnapshot) { workflow.value = FoodWorkflowState.EditQuantity(log) }

    fun applyOcr(draft: OcrNutritionDraft) {
        val editor = workflow.value as? FoodWorkflowState.EditProduct ?: return
        workflow.value = FoodWorkflowState.EditProduct(editor.draft.mergeOcr(draft))
    }

    fun updateProductDraft(updated: ProductEditorDraft) {
        if (workflow.value is FoodWorkflowState.EditProduct) workflow.value = FoodWorkflowState.EditProduct(updated)
    }

    fun saveLogEdit(edit: FoodQuantityEdit) {
        viewModelScope.launch {
            runCatching { repository.updateFoodLog(edit) }
                .onSuccess { result ->
                    workflow.value = FoodWorkflowState.Idle
                    afterFoodChange(result)
                    _events.emit(FoodUiEvent.Message("Food entry updated."))
                }
                .onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not update food.")) }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteFoodLog(id) }
                .onSuccess { result ->
                    afterFoodChange(result)
                    _events.emit(FoodUiEvent.Message("Food entry deleted.", result.deleted?.let(FoodUndo::Single)))
                }
                .onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not delete food.")) }
        }
    }

    fun undoDelete(deleted: DeletedFoodLogSnapshot) {
        viewModelScope.launch {
            runCatching { repository.restoreFoodLog(deleted) }
                .onSuccess { result ->
                    afterFoodChange(result)
                    _events.emit(FoodUiEvent.Message("Food entry restored."))
                }
                .onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not restore food.")) }
        }
    }

    fun deleteGroup(mealId: String) {
        viewModelScope.launch {
            runCatching { repository.deleteFoodLogGroup(mealId) }
                .onSuccess { result ->
                    thresholdNotifier.crossingMessage(result.date, result.before, result.after, latestTargets.value)
                        ?.let { _events.emit(FoodUiEvent.Threshold(it)) }
                    _events.emit(FoodUiEvent.Message("Bulk order deleted.", FoodUndo.Group(result.deleted)))
                }
                .onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not delete bulk order.")) }
        }
    }

    fun undo(undo: FoodUndo) {
        when (undo) {
            is FoodUndo.Single -> undoDelete(undo.value)
            is FoodUndo.Group -> viewModelScope.launch {
                runCatching { repository.restoreFoodLogGroup(undo.value) }
                    .onSuccess { _events.emit(FoodUiEvent.Message("Bulk order restored.")) }
                    .onFailure { _events.emit(FoodUiEvent.Message(it.message ?: "Could not restore bulk order.")) }
            }
        }
    }

    private suspend fun afterFoodChange(result: FoodMutationResult) {
        thresholdNotifier.crossingMessage(result.date, result.before, result.after, latestTargets.value)
            ?.let { _events.emit(FoodUiEvent.Threshold(it)) }
    }
}

internal fun ProductEditorDraft.mergeOcr(ocr: OcrNutritionDraft): ProductEditorDraft {
    fun accepted(field: OcrField, previous: String): String = ocr.values[field]?.let(::formatDecimal) ?: previous
    return copy(
        servingLabel = ocr.servingLabel?.takeIf(String::isNotBlank) ?: servingLabel,
        calories = accepted(OcrField.CALORIES, calories),
        protein = accepted(OcrField.PROTEIN, protein),
        sodium = accepted(OcrField.SODIUM, sodium),
        carbs = accepted(OcrField.CARBS, carbs),
        fat = accepted(OcrField.FAT, fat),
        sugar = accepted(OcrField.SUGAR, sugar),
        fiber = accepted(OcrField.FIBER, fiber),
        saturatedFat = accepted(OcrField.SATURATED_FAT, saturatedFat),
        ocrDraft = ocr
    )
}

data class OcrUiState(
    val images: List<PreparedOcrImage> = emptyList(),
    val language: OcrLanguage = OcrLanguage.AUTO,
    val preparing: Boolean = false,
    val processing: Boolean = false,
    val review: OcrReview? = null,
    val selections: Map<OcrSelectionKey, String> = emptyMap(),
    val message: String? = null
)

class OcrViewModel(
    private val ocr: NutritionLabelOcr,
    private val preprocessor: NutritionImagePreprocessor
) : ViewModel() {
    private val _uiState = MutableStateFlow(OcrUiState())
    val uiState: StateFlow<OcrUiState> = _uiState
    private var extractionJob: Job? = null

    suspend fun preview(uri: android.net.Uri, rotationDegrees: Int) = preprocessor.preview(uri, rotationDegrees)

    suspend fun prepareImage(uri: android.net.Uri, crop: CropRegion, rotationDegrees: Int): Boolean {
        if (_uiState.value.images.size >= 3 || _uiState.value.preparing) return false
        _uiState.value = _uiState.value.copy(preparing = true, review = null, message = null)
        return runCatching { preprocessor.prepare(uri, crop, rotationDegrees) }
            .onSuccess { prepared ->
                _uiState.value = _uiState.value.copy(
                    images = _uiState.value.images + prepared,
                    preparing = false,
                    review = null
                )
            }
            .onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    preparing = false,
                    message = "Could not prepare image: ${error.message ?: "unknown error"}"
                )
            }.isSuccess
    }

    fun removeImage(image: PreparedOcrImage) {
        preprocessor.delete(image.uri)
        _uiState.value = _uiState.value.copy(images = _uiState.value.images - image, review = null)
    }

    fun setLanguage(language: OcrLanguage) {
        _uiState.value = _uiState.value.copy(language = language, review = null)
    }

    fun extract() {
        val state = _uiState.value
        if (state.processing || state.images.isEmpty()) return
        extractionJob = viewModelScope.launch {
            _uiState.value = state.copy(processing = true, message = null)
            when (val result = ocr.extract(state.images.map { it.uri }, state.language)) {
                is OcrResult.Success -> applyReview(result.review, processing = false)
                is OcrResult.Failed -> _uiState.value = _uiState.value.copy(processing = false, message = result.message)
                OcrResult.Cancelled -> _uiState.value = _uiState.value.copy(processing = false)
            }
        }
    }

    fun selectCandidate(field: OcrField, basis: OcrBasis, candidateId: String?) {
        val key = OcrSelectionKey(field, basis)
        _uiState.value = _uiState.value.copy(
            selections = _uiState.value.selections.toMutableMap().apply {
                if (candidateId == null) remove(key) else put(key, candidateId)
            }
        )
    }

    private fun applyReview(review: OcrReview, processing: Boolean? = null) {
        val automaticSelections = review.proposals.flatMap { proposal ->
            proposal.candidates.map { (basis, candidate) ->
                OcrSelectionKey(proposal.field, basis) to candidate.candidateId
            }
        }.toMap()
        _uiState.value = _uiState.value.copy(
            processing = processing ?: _uiState.value.processing,
            review = review,
            selections = automaticSelections
        )
    }

    fun reset() {
        extractionJob?.cancel()
        extractionJob = null
        _uiState.value = OcrUiState()
        preprocessor.cleanup()
    }
}

data class SettingsUiState(
    val healthAvailable: Boolean = false,
    val corePermissionsGranted: Boolean = false,
    val nutritionPermissionGranted: Boolean = false,
    val nutritionWritePermissionGranted: Boolean = false,
    val weightPermissionGranted: Boolean = false,
    val nutritionSyncStatus: String? = null,
    val healthHistoryStatus: String? = null,
    val goals: UserGoals = UserGoals(),
    val healthProfile: HealthProfile = HealthProfile(),
    val isRefreshingHealth: Boolean = false,
    val message: String? = null
)

class SettingsViewModel(private val repository: DailyCutRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(
        healthAvailable = repository.healthConnectAvailable()
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState

    init {
        viewModelScope.launch {
            repository.observeGoals().collect { goals -> _uiState.value = _uiState.value.copy(goals = goals) }
        }
        viewModelScope.launch {
            repository.observeHealthProfile().collect { profile ->
                _uiState.value = _uiState.value.copy(healthProfile = profile)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState(
                healthAvailable = repository.healthConnectAvailable(),
                corePermissionsGranted = repository.healthCorePermissionsGranted(),
                nutritionPermissionGranted = repository.healthNutritionPermissionGranted(),
                nutritionWritePermissionGranted = repository.healthNutritionWritePermissionGranted(),
                weightPermissionGranted = repository.healthWeightPermissionGranted(),
                nutritionSyncStatus = repository.nutritionSyncStatus(),
                healthHistoryStatus = repository.healthHistoryStatus(),
                goals = _uiState.value.goals,
                healthProfile = _uiState.value.healthProfile,
                isRefreshingHealth = _uiState.value.isRefreshingHealth,
                message = _uiState.value.message
            )
        }
    }

    fun permissionsChanged() {
        viewModelScope.launch {
            repository.ensureHealthBootstrap()
            refresh()
        }
    }

    fun saveGoals(goals: UserGoals) {
        viewModelScope.launch {
            runCatching { repository.updateGoals(goals) }
                .onSuccess { _uiState.value = _uiState.value.copy(goals = goals, message = "Goals and budget saved.") }
                .onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "Could not save goals.") }
        }
    }

    fun saveGoalsAndProfile(goals: UserGoals, profile: HealthProfile) {
        viewModelScope.launch {
            runCatching {
                repository.updateGoals(goals)
                repository.updateHealthProfile(profile)
            }.onSuccess {
                _uiState.value = _uiState.value.copy(
                    goals = goals,
                    healthProfile = profile,
                    message = "Goals, budget, and weight target saved."
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(message = it.message ?: "Could not save goals.")
            }
        }
    }

    fun refreshHealth(date: LocalDate) {
        if (_uiState.value.isRefreshingHealth) return
        viewModelScope.launch {
            if (!repository.healthConnectAvailable() || !repository.healthCorePermissionsGranted()) {
                _uiState.value = _uiState.value.copy(message = "Grant Health Connect activity permissions first.")
                refresh()
                return@launch
            }
            _uiState.value = _uiState.value.copy(isRefreshingHealth = true)
            repository.refreshHealth(date)
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Health Connect refreshed.") }
                .onFailure { _uiState.value = _uiState.value.copy(message = "Health refresh failed: ${it.message ?: "unknown error"}") }
            _uiState.value = _uiState.value.copy(isRefreshingHealth = false)
            refresh()
        }
    }

    fun exportBackup(uri: android.net.Uri, password: String) {
        viewModelScope.launch {
            repository.exportBackup(uri, password.toCharArray())
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Encrypted backup saved.") }
                .onFailure { _uiState.value = _uiState.value.copy(message = "Backup failed: ${it.message}") }
        }
    }

    fun restoreBackup(uri: android.net.Uri, password: String) {
        viewModelScope.launch {
            repository.restoreBackup(uri, password.toCharArray())
                .onSuccess { _uiState.value = _uiState.value.copy(message = "Backup restored.") }
                .onFailure { _uiState.value = _uiState.value.copy(message = "Restore failed: ${it.message}") }
        }
    }

    fun clearMessage() { _uiState.value = _uiState.value.copy(message = null) }
}

data class PlannerSettingsUiState(
    val query: String = "",
    val products: List<ProductEntity> = emptyList(),
    val amountDrafts: Map<String, String> = emptyMap(),
    val errors: Map<String, String> = emptyMap()
) {
    val visibleProducts: List<ProductEntity>
        get() {
            val normalized = query.trim().lowercase()
            return if (normalized.isBlank()) products else products.filter { product ->
                product.name.lowercase().contains(normalized) ||
                    product.brand.lowercase().contains(normalized) ||
                    product.barcode.orEmpty().lowercase().contains(normalized)
            }
        }
}

class PlannerSettingsViewModel(private val repository: DailyCutRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    private val products = repository.observePlannerProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val amountDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val errors = MutableStateFlow<Map<String, String>>(emptyMap())
    private val saveJobs = mutableMapOf<String, Job>()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val events: SharedFlow<String> = _events.asSharedFlow()

    val uiState: StateFlow<PlannerSettingsUiState> =
        combine(query, products, amountDrafts, errors, ::PlannerSettingsUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlannerSettingsUiState())

    fun setQuery(value: String) { query.value = value }

    fun setIncluded(product: ProductEntity, included: Boolean) {
        save(product, included = included, fixed = product.alwaysIncludeInPlanner && included)
    }

    fun setItemType(product: ProductEntity, itemType: PlannerItemType) {
        save(product, itemType = itemType)
    }

    fun setFixed(product: ProductEntity, fixed: Boolean) {
        if (!product.includeInPlanner) return
        save(product, fixed = fixed)
    }

    fun setFixedUnitsText(product: ProductEntity, value: String) {
        amountDrafts.value = amountDrafts.value + (product.productId to value)
        val units = value.toIntOrNull()?.takeIf { it in 1..6 }
        if (units == null) {
            errors.value = errors.value + (product.productId to "Enter a whole number from 1 to 6.")
            saveJobs.remove(product.productId)?.cancel()
            return
        }
        errors.value = errors.value - product.productId
        saveJobs.remove(product.productId)?.cancel()
        saveJobs[product.productId] = viewModelScope.launch {
            delay(300)
            save(product, fixedUnits = units, clearDraftOnSuccess = true)
        }
    }

    private fun save(
        product: ProductEntity,
        included: Boolean = product.includeInPlanner,
        itemType: PlannerItemType = PlannerItemType.entries.firstOrNull { it.name == product.plannerItemType }
            ?: PlannerItemType.FOOD,
        fixed: Boolean = product.alwaysIncludeInPlanner,
        fixedUnits: Int = amountDrafts.value[product.productId]?.toIntOrNull() ?: product.fixedPurchaseUnits,
        clearDraftOnSuccess: Boolean = false
    ) {
        viewModelScope.launch {
            runCatching {
                repository.updatePlannerSettings(
                    PlannerProductSettings(product.productId, included, itemType, fixed && included, fixedUnits)
                )
            }.onSuccess {
                errors.value = errors.value - product.productId
                if (clearDraftOnSuccess) amountDrafts.value = amountDrafts.value - product.productId
            }.onFailure { error ->
                amountDrafts.value = amountDrafts.value - product.productId
                errors.value = errors.value + (product.productId to (error.message ?: "Could not save planner settings."))
                _events.emit(error.message ?: "Could not save planner settings.")
            }
        }
    }
}

data class HealthUiState(
    val dashboard: HealthDashboard? = null,
    val refreshing: Boolean = false,
    val message: String? = null
)

class HealthViewModel(
    private val repository: DailyCutRepository,
    selectedDate: StateFlow<LocalDate>
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    val uiState: StateFlow<HealthUiState> = combine(
        selectedDate.flatMapLatest(repository::observeHealthDashboard), refreshing, message
    ) { dashboard, busy, note -> HealthUiState(dashboard, busy, note) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HealthUiState())

    fun setTargetWeight(displayValue: Double?) {
        val profile = uiState.value.dashboard?.profile ?: return
        val kilograms = displayValue?.let(profile.weightUnit::toKg)
        viewModelScope.launch {
            runCatching { repository.updateHealthProfile(profile.copy(targetWeightKg = kilograms)) }
                .onFailure { message.value = it.message ?: "Could not save target weight." }
        }
    }

    fun saveManualWeight(displayValue: Double, time: java.time.LocalTime) {
        val dashboard = uiState.value.dashboard ?: return
        viewModelScope.launch {
            runCatching {
                repository.addManualWeight(
                    dashboard.selectedDate,
                    time,
                    dashboard.profile.weightUnit.toKg(displayValue)
                )
            }.onSuccess { message.value = "Weight saved." }
                .onFailure { message.value = it.message ?: "Could not save weight." }
        }
    }

    fun deleteManualWeight(entryId: String) {
        viewModelScope.launch {
            repository.deleteManualWeight(entryId)
            message.value = "Manual weight removed."
        }
    }

    fun refreshHistory() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            repository.syncHealthHistory(force = true)
                .onSuccess { message.value = "30-day Health Connect history refreshed." }
                .onFailure { message.value = "History refresh failed: ${it.message ?: "unknown error"}" }
            refreshing.value = false
        }
    }

    fun clearMessage() { message.value = null }
}

class AppViewModelFactory(
    private val repository: DailyCutRepository,
    private val selectedDate: StateFlow<LocalDate>? = null,
    private val ocr: NutritionLabelOcr? = null,
    private val preprocessor: NutritionImagePreprocessor? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ReportDateViewModel::class.java) -> ReportDateViewModel() as T
        modelClass.isAssignableFrom(TodayViewModel::class.java) -> TodayViewModel(repository, requireNotNull(selectedDate)) as T
        modelClass.isAssignableFrom(FoodsViewModel::class.java) -> FoodsViewModel(repository, requireNotNull(selectedDate)) as T
        modelClass.isAssignableFrom(HealthViewModel::class.java) -> HealthViewModel(repository, requireNotNull(selectedDate)) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(repository) as T
        modelClass.isAssignableFrom(PlannerSettingsViewModel::class.java) -> PlannerSettingsViewModel(repository) as T
        modelClass.isAssignableFrom(OcrViewModel::class.java) -> OcrViewModel(requireNotNull(ocr), requireNotNull(preprocessor)) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}

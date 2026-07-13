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
    data class EditProduct(
        val barcode: String,
        val product: ProductWithExtras?,
        val saveTarget: ProductSaveTarget,
        val ocrDraft: OcrNutritionDraft? = null
    ) : FoodWorkflowState
    data class EditQuantity(val log: FoodLogSnapshot) : FoodWorkflowState
}

sealed interface FoodUiEvent {
    data class Message(val text: String, val undo: DeletedFoodLogSnapshot? = null) : FoodUiEvent
    data class Threshold(val text: String) : FoodUiEvent
}

data class FoodsUiState(
    val date: LocalDate = LocalDate.now(),
    val logs: List<FoodLogSnapshot> = emptyList(),
    val products: List<ProductEntity> = emptyList(),
    val recentProducts: List<ProductEntity> = emptyList(),
    val goals: UserGoals = UserGoals(),
    val query: String = "",
    val workflow: FoodWorkflowState = FoodWorkflowState.Idle,
    val mode: FoodMode = FoodMode.NORMAL,
    val bulkDraft: BulkDraft = BulkDraft()
)

private data class FoodsCatalogState(
    val products: List<ProductEntity>,
    val recent: List<ProductEntity>,
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
        productSearch, repository.observeRecentProducts(), workflow, mode, bulkDraft
    ) { products, recent, active, foodMode, draft ->
        FoodsCatalogState(products, recent, active, foodMode, draft)
    }
    val uiState: StateFlow<FoodsUiState> = combine(
        selectedDate,
        selectedDate.flatMapLatest(repository::observeFoodLogs),
        catalogState,
        latestGoals
    ) { date, logs, catalog, goals ->
        FoodsUiState(
            date, logs, catalog.products, catalog.recent, goals, query.value,
            catalog.workflow, catalog.mode, catalog.bulkDraft
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodsUiState())

    fun setQuery(value: String) { query.value = value }

    fun handleBarcode(barcode: String, target: ScanTarget = ScanTarget.STANDALONE) {
        val normalized = barcode.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            val saved = repository.lookupProduct(normalized)
            if (saved == null) {
                workflow.value = FoodWorkflowState.EditProduct(
                    normalized, null,
                    if (target == ScanTarget.BULK_CART) ProductSaveTarget.BULK_CART else ProductSaveTarget.STANDALONE_LOG
                )
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
        workflow.value = FoodWorkflowState.EditProduct(
            "", null,
            if (mode.value == FoodMode.BULK) ProductSaveTarget.BULK_CART else ProductSaveTarget.STANDALONE_LOG
        )
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
            items = current.items + BulkDraftItem(product)
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
                workflow.value = FoodWorkflowState.EditProduct(product.barcode.orEmpty(), it, ProductSaveTarget.CATALOG_ONLY)
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
                when (editor.saveTarget) {
                    ProductSaveTarget.STANDALONE_LOG -> {
                        workflow.value = FoodWorkflowState.ConfirmQuantity(ProductWithExtras(product, extras))
                        _events.emit(FoodUiEvent.Message("Saved ${product.name}. Confirm quantity to add it."))
                    }
                    ProductSaveTarget.BULK_CART -> {
                        addProductToBulk(product)
                        workflow.value = FoodWorkflowState.Idle
                        _events.emit(FoodUiEvent.Message("Saved ${product.name} and added it to the bulk cart."))
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
        workflow.value = editor.copy(ocrDraft = draft)
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
                    _events.emit(FoodUiEvent.Message("Food entry deleted.", result.deleted))
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

    private suspend fun afterFoodChange(result: FoodMutationResult) {
        thresholdNotifier.crossingMessage(result.date, result.before, result.after, latestTargets.value)
            ?.let { _events.emit(FoodUiEvent.Threshold(it)) }
    }
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
    val nutritionSyncStatus: String? = null,
    val goals: UserGoals = UserGoals(),
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
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState(
                healthAvailable = repository.healthConnectAvailable(),
                corePermissionsGranted = repository.healthCorePermissionsGranted(),
                nutritionPermissionGranted = repository.healthNutritionPermissionGranted(),
                nutritionWritePermissionGranted = repository.healthNutritionWritePermissionGranted(),
                nutritionSyncStatus = repository.nutritionSyncStatus(),
                goals = _uiState.value.goals,
                isRefreshingHealth = _uiState.value.isRefreshingHealth,
                message = _uiState.value.message
            )
        }
    }

    fun saveGoals(goals: UserGoals) {
        viewModelScope.launch {
            runCatching { repository.updateGoals(goals) }
                .onSuccess { _uiState.value = _uiState.value.copy(goals = goals, message = "Goals and budget saved.") }
                .onFailure { _uiState.value = _uiState.value.copy(message = it.message ?: "Could not save goals.") }
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
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(repository) as T
        modelClass.isAssignableFrom(OcrViewModel::class.java) -> OcrViewModel(requireNotNull(ocr), requireNotNull(preprocessor)) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}

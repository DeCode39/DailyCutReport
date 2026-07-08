@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)

package com.littleone.dailycutreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    val targets: DailyNutritionTargets = DailyNutritionTargets(),
    val message: String? = null
)

class TodayViewModel(
    private val repository: DailyCutRepository,
    selectedDate: StateFlow<LocalDate>
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private var appOpenRefreshRunning = false

    val uiState: StateFlow<TodayUiState> = combine(
        selectedDate.flatMapLatest(repository::observeReport),
        selectedDate.flatMapLatest(repository::observeFoodLogs),
        message
    ) { report, logs, note -> TodayUiState(report, logs, DailyNutritionTargets(), note) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        viewModelScope.launch {
            runCatching { repository.initialize() }
                .onFailure { message.value = "Could not import saved reports: ${it.message}" }
        }
    }

    fun refreshTodayOnAppOpen() {
        if (appOpenRefreshRunning) return
        appOpenRefreshRunning = true
        viewModelScope.launch {
            try {
                if (repository.healthConnectAvailable() && repository.healthCorePermissionsGranted()) {
                    repository.refreshHealth(LocalDate.now())
                }
            } finally {
                appOpenRefreshRunning = false
            }
        }
    }

    suspend fun saveReport() = repository.saveReport(uiState.value.report)
    suspend fun writeReport(uri: android.net.Uri) = repository.writeReport(uri, uiState.value.report)
    suspend fun createShareUri() = repository.createShareUri(uiState.value.report)
    fun clearMessage() { message.value = null }
}

data class FoodsUiState(
    val date: LocalDate = LocalDate.now(),
    val nutrition: NutritionSummary = NutritionSummary(),
    val logs: List<FoodLogSnapshot> = emptyList(),
    val products: List<ProductEntity> = emptyList(),
    val query: String = "",
    val pendingProduct: ProductWithExtras? = null,
    val editorBarcode: String? = null,
    val editorProduct: ProductWithExtras? = null,
    val editorAddsAfterSave: Boolean = true,
    val ocrDraft: OcrNutritionDraft? = null,
    val editingLog: FoodLogSnapshot? = null,
    val thresholdMessage: String? = null,
    val message: String? = null
)

class FoodsViewModel(
    private val repository: DailyCutRepository,
    selectedDate: StateFlow<LocalDate>
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val pendingProduct = MutableStateFlow<ProductWithExtras?>(null)
    private val editorBarcode = MutableStateFlow<String?>(null)
    private val editorProduct = MutableStateFlow<ProductWithExtras?>(null)
    private val editorAddsAfterSave = MutableStateFlow(true)
    private val ocrDraft = MutableStateFlow<OcrNutritionDraft?>(null)
    private val editingLog = MutableStateFlow<FoodLogSnapshot?>(null)
    private val thresholdMessage = MutableStateFlow<String?>(null)
    private val message = MutableStateFlow<String?>(null)
    private val thresholdNotifier = MacroThresholdNotifier()
    private val productSearch = query.debounce(200).distinctUntilChanged().flatMapLatest(repository::observeProducts)

    val uiState: StateFlow<FoodsUiState> = combine(
        selectedDate,
        selectedDate.flatMapLatest(repository::observeReport),
        selectedDate.flatMapLatest(repository::observeFoodLogs),
        productSearch,
        combine(
            pendingProduct,
            combine(editorBarcode, editorProduct, editorAddsAfterSave, ocrDraft) { barcode, product, add, ocr -> EditorState(barcode, product, add, ocr) },
            editingLog,
            thresholdMessage,
            message
        ) { product, editor, log, threshold, note ->
            DialogState(product, editor, log, threshold, note)
        }
    ) { date, report, logs, products, dialog ->
        FoodsUiState(
            date, report.nutrition, logs, products, query.value, dialog.product,
            dialog.editor.barcode, dialog.editor.product, dialog.editor.addAfterSave,
            dialog.editor.ocrDraft, dialog.log, dialog.thresholdMessage, dialog.message
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FoodsUiState())

    fun setQuery(value: String) { query.value = value }

    fun handleBarcode(barcode: String) {
        val normalized = barcode.trim()
        if (normalized.isBlank()) return
        viewModelScope.launch {
            val saved = repository.lookupProduct(normalized)
            if (saved == null) {
                editorProduct.value = null
                editorAddsAfterSave.value = true
                editorBarcode.value = normalized
            } else pendingProduct.value = saved
        }
    }

    fun selectProduct(product: ProductEntity) {
        viewModelScope.launch {
            pendingProduct.value = repository.getProduct(product.productId)
        }
    }

    fun createProduct() {
        editorProduct.value = null
        editorAddsAfterSave.value = true
        editorBarcode.value = ""
        ocrDraft.value = null
    }

    fun editProduct(product: ProductEntity) {
        viewModelScope.launch {
            editorProduct.value = repository.getProduct(product.productId)
            editorAddsAfterSave.value = false
            editorBarcode.value = product.barcode.orEmpty()
            ocrDraft.value = null
        }
    }
    fun cancelDialogs() {
        pendingProduct.value = null
        editorBarcode.value = null
        editorProduct.value = null
        ocrDraft.value = null
        editingLog.value = null
    }

    fun confirmAdd(quantity: Double) {
        val selected = pendingProduct.value ?: return
        val date = uiState.value.date
        viewModelScope.launch {
            val before = repository.nutritionForDate(date)
            runCatching { repository.addProduct(date, selected, quantity) }
                .onSuccess {
                    pendingProduct.value = null
                    afterFoodChange(date, before)
                    message.value = "Added ${selected.product.name}."
                }
                .onFailure { message.value = it.message }
        }
    }

    fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>, _quantity: Double) {
        val addAfterSave = editorAddsAfterSave.value
        viewModelScope.launch {
            runCatching {
                repository.saveProduct(product, extras)
            }.onSuccess {
                editorBarcode.value = null
                editorProduct.value = null
                ocrDraft.value = null
                if (addAfterSave) {
                    pendingProduct.value = ProductWithExtras(product, extras)
                    message.value = "Saved ${product.name}. Confirm quantity to add it."
                } else message.value = "Updated ${product.name}."
            }.onFailure { message.value = it.message }
        }
    }

    fun edit(log: FoodLogSnapshot) { editingLog.value = log }

    fun applyOcr(draft: OcrNutritionDraft) { ocrDraft.value = draft }

    fun saveLogEdit(edit: FoodLogEdit) {
        val date = editingLog.value?.date ?: uiState.value.date
        viewModelScope.launch {
            val before = repository.nutritionForDate(date)
            runCatching { repository.updateFoodLog(edit) }
                .onSuccess {
                    editingLog.value = null
                    afterFoodChange(date, before)
                    message.value = "Food entry updated."
                }
                .onFailure { message.value = it.message }
        }
    }

    fun delete(id: Long) {
        val date = uiState.value.date
        viewModelScope.launch {
            val before = repository.nutritionForDate(date)
            runCatching { repository.deleteFoodLog(id) }
                .onSuccess {
                    afterFoodChange(date, before)
                    message.value = "Food entry deleted."
                }
                .onFailure { message.value = it.message }
        }
    }

    fun clearMessage() { message.value = null }
    fun clearThresholdMessage() { thresholdMessage.value = null }

    private suspend fun afterFoodChange(date: LocalDate, before: NutritionSummary) {
        val after = repository.nutritionForDate(date)
        thresholdNotifier.crossingMessage(date, before, after)?.let { thresholdMessage.value = it }
        repository.syncNutritionToHealthConnect(date)
    }

    private data class DialogState(
        val product: ProductWithExtras?,
        val editor: EditorState,
        val log: FoodLogSnapshot?,
        val thresholdMessage: String?,
        val message: String?
    )

    private data class EditorState(
        val barcode: String?,
        val product: ProductWithExtras?,
        val addAfterSave: Boolean,
        val ocrDraft: OcrNutritionDraft?
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
        if (image.fullFrameUri != image.uri) preprocessor.delete(image.fullFrameUri)
        _uiState.value = _uiState.value.copy(images = _uiState.value.images - image, review = null)
    }

    fun setLanguage(language: OcrLanguage) {
        _uiState.value = _uiState.value.copy(language = language, review = null)
    }

    fun extract() {
        val state = _uiState.value
        if (state.processing || state.images.isEmpty()) return
        viewModelScope.launch {
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
    val isRefreshingHealth: Boolean = false,
    val message: String? = null
)

class SettingsViewModel(private val repository: DailyCutRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(
        healthAvailable = repository.healthConnectAvailable()
    ))
    val uiState: StateFlow<SettingsUiState> = _uiState

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState(
                healthAvailable = repository.healthConnectAvailable(),
                corePermissionsGranted = repository.healthCorePermissionsGranted(),
                nutritionPermissionGranted = repository.healthNutritionPermissionGranted(),
                nutritionWritePermissionGranted = repository.healthNutritionWritePermissionGranted(),
                nutritionSyncStatus = repository.nutritionSyncStatus(),
                isRefreshingHealth = _uiState.value.isRefreshingHealth,
                message = _uiState.value.message
            )
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

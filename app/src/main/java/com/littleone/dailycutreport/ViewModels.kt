@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.littleone.dailycutreport

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val isRefreshing: Boolean = false,
    val message: String? = null
)

class TodayViewModel(
    private val repository: DailyCutRepository,
    selectedDate: StateFlow<LocalDate>
) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<TodayUiState> = combine(
        selectedDate.flatMapLatest(repository::observeReport),
        refreshing,
        message
    ) { report, busy, note -> TodayUiState(report, busy, note) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    init {
        viewModelScope.launch {
            runCatching { repository.initialize() }
                .onFailure { message.value = "Could not import saved reports: ${it.message}" }
        }
    }

    fun onForeground(date: LocalDate) {
        if (date == LocalDate.now()) refreshHealth(date, silentIfUnavailable = true)
    }

    fun refreshHealth(date: LocalDate, silentIfUnavailable: Boolean = false) {
        if (refreshing.value) return
        viewModelScope.launch {
            if (!repository.healthConnectAvailable() || !repository.healthPermissionsGranted()) {
                if (!silentIfUnavailable) message.value = "Grant Health Connect permissions first."
                return@launch
            }
            refreshing.value = true
            repository.refreshHealth(date)
                .onSuccess { message.value = "Health Connect refreshed." }
                .onFailure { message.value = "Health refresh failed: ${it.message ?: "unknown error"}" }
            refreshing.value = false
        }
    }

    fun saveOverrides(date: LocalDate, overrides: ManualOverrides) {
        viewModelScope.launch {
            repository.saveManualOverrides(date, overrides)
            message.value = "Overrides saved."
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
    val editingLog: FoodLogSnapshot? = null,
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
    private val editingLog = MutableStateFlow<FoodLogSnapshot?>(null)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<FoodsUiState> = combine(
        selectedDate,
        selectedDate.flatMapLatest(repository::observeReport),
        selectedDate.flatMapLatest(repository::observeFoodLogs),
        query.flatMapLatest(repository::observeProducts),
        combine(
            pendingProduct,
            combine(editorBarcode, editorProduct, editorAddsAfterSave) { barcode, product, add -> EditorState(barcode, product, add) },
            editingLog,
            message
        ) { product, editor, log, note ->
            DialogState(product, editor, log, note)
        }
    ) { date, report, logs, products, dialog ->
        FoodsUiState(
            date, report.nutrition, logs, products, query.value, dialog.product,
            dialog.editor.barcode, dialog.editor.product, dialog.editor.addAfterSave,
            dialog.log, dialog.message
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
            pendingProduct.value = repository.lookupProduct(product.barcode)
        }
    }

    fun createProduct() {
        editorProduct.value = null
        editorAddsAfterSave.value = true
        editorBarcode.value = ""
    }

    fun editProduct(product: ProductEntity) {
        viewModelScope.launch {
            editorProduct.value = repository.lookupProduct(product.barcode)
            editorAddsAfterSave.value = false
            editorBarcode.value = product.barcode
        }
    }
    fun cancelDialogs() {
        pendingProduct.value = null
        editorBarcode.value = null
        editorProduct.value = null
        editingLog.value = null
    }

    fun confirmAdd(quantity: Double) {
        val selected = pendingProduct.value ?: return
        val date = uiState.value.date
        viewModelScope.launch {
            runCatching { repository.addProduct(date, selected, quantity) }
                .onSuccess {
                    pendingProduct.value = null
                    message.value = "Added ${selected.product.name}."
                }
                .onFailure { message.value = it.message }
        }
    }

    fun saveProduct(product: ProductEntity, extras: List<ProductExtraNutrientEntity>, quantity: Double) {
        val date = uiState.value.date
        val addAfterSave = editorAddsAfterSave.value
        viewModelScope.launch {
            runCatching {
                repository.saveProduct(product, extras)
                if (addAfterSave) repository.addProduct(date, ProductWithExtras(product, extras), quantity)
            }.onSuccess {
                editorBarcode.value = null
                editorProduct.value = null
                message.value = if (addAfterSave) "Saved and added ${product.name}." else "Updated ${product.name}."
            }.onFailure { message.value = it.message }
        }
    }

    fun edit(log: FoodLogSnapshot) { editingLog.value = log }

    fun saveLogEdit(edit: FoodLogEdit) {
        viewModelScope.launch {
            runCatching { repository.updateFoodLog(edit) }
                .onSuccess { editingLog.value = null; message.value = "Food entry updated." }
                .onFailure { message.value = it.message }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.deleteFoodLog(id)
            message.value = "Food entry deleted."
        }
    }

    fun clearMessage() { message.value = null }

    private data class DialogState(
        val product: ProductWithExtras?,
        val editor: EditorState,
        val log: FoodLogSnapshot?,
        val message: String?
    )

    private data class EditorState(
        val barcode: String?,
        val product: ProductWithExtras?,
        val addAfterSave: Boolean
    )
}

data class SettingsUiState(
    val healthAvailable: Boolean = false,
    val permissionsGranted: Boolean = false
)

class SettingsViewModel(private val repository: DailyCutRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState(repository.healthConnectAvailable(), false))
    val uiState: StateFlow<SettingsUiState> = _uiState

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = SettingsUiState(repository.healthConnectAvailable(), repository.healthPermissionsGranted())
        }
    }
}

class AppViewModelFactory(
    private val repository: DailyCutRepository,
    private val selectedDate: StateFlow<LocalDate>? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ReportDateViewModel::class.java) -> ReportDateViewModel() as T
        modelClass.isAssignableFrom(TodayViewModel::class.java) -> TodayViewModel(repository, requireNotNull(selectedDate)) as T
        modelClass.isAssignableFrom(FoodsViewModel::class.java) -> FoodsViewModel(repository, requireNotNull(selectedDate)) as T
        modelClass.isAssignableFrom(SettingsViewModel::class.java) -> SettingsViewModel(repository) as T
        else -> error("Unknown ViewModel: ${modelClass.name}")
    }
}

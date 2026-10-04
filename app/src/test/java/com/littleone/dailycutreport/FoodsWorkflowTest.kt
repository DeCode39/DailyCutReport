package com.littleone.dailycutreport

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class FoodsWorkflowTest {
    private val date = LocalDate.of(2026, 10, 4)
    private val product = ProductEntity("food", name = "Food", quantityMode = QuantityMode.SERVING_AND_WEIGHT.name,
        measurePerServing = 40.0, purchaseUnitServings = 2.0, purchasePriceMicros = 10_000_000L)
    private val log = FoodLogSnapshot(date = date.minusDays(1), productId = "food", productName = "Old food",
        quantity = 2.0, enteredUnit = QuantityUnit.GRAMS.name, enteredAmount = 80.0, actualPaidTotalMicros = 0)

    private class Fake(val product: ProductEntity, val log: FoodLogSnapshot, var pending: PendingProductDraft? = null) {
        val writes = mutableListOf<PendingProductDraft?>()
        val repo = Proxy.newProxyInstance(DailyCutRepository::class.java.classLoader,
            arrayOf(DailyCutRepository::class.java)) { _, method, args ->
            when (method.name) {
                "observeGoals" -> flowOf(UserGoals())
                "observeReport" -> flowOf(DailyReport(args!![0] as LocalDate))
                "observeFoodLogs", "observeProducts", "observeTemporaryMeals", "observeRecentProducts", "observeFavoriteProducts" -> flowOf(emptyList<Any>())
                "loadCartDraft" -> BulkDraft()
                "saveCartDraft" -> Unit
                "loadPendingProductDraft" -> pending
                "savePendingProductDraft" -> { pending = args!![0] as PendingProductDraft?; writes += pending; Unit }
                "getProduct" -> ProductWithExtras(product)
                "lastLoggedAmount" -> log
                "foodLogsForDate" -> listOf(log)
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as DailyCutRepository
    }

    @Test fun existingEditDoesNotPersistOrRemoveRecoverableNewDraft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val pending = PendingProductDraft(ProductEditorDraft(name = "Unsaved new food"), date)
        val fake = Fake(product, log, pending)
        val vm = FoodsViewModel(fake.repo, MutableStateFlow(date))
        val store = ViewModelStore().apply { put("foods", vm) }
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
            runCurrent(); vm.editProduct(product); runCurrent()
            val editor = vm.uiState.value.workflow as FoodWorkflowState.EditProduct
            vm.updateProductDraft(editor.draft.copy(name = "Changed"))
            advanceTimeBy(300); runCurrent(); vm.leaveProductEditor(); runCurrent()
            assertTrue(fake.writes.isEmpty())
            assertEquals(pending, vm.uiState.value.recoverableDraft)
            assertEquals(FoodWorkflowState.Idle, vm.uiState.value.workflow)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun copyUsesPhysicalAmountAndNeverCopiesCheckoutPayment() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = Fake(product, log)
        val vm = FoodsViewModel(fake.repo, MutableStateFlow(date))
        val store = ViewModelStore().apply { put("foods", vm) }
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
            runCurrent(); vm.copyDayToCart(date.minusDays(1)); runCurrent()
            val cart = vm.uiState.value.bulkDraft
            assertEquals(date, cart.date)
            assertEquals("", cart.actualPaidText)
            assertEquals(2.0, cart.items.single().quantity!!, 0.0)
            assertEquals(QuantityUnit.GRAMS, cart.items.single().quantityInput.activeUnit)
            assertEquals(80.0, cart.items.single().quantityInput.enteredAmount!!, 0.0)
            assertTrue(vm.uiState.value.cartVisible)
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun lastAmountAndCartUndoKeepPurchaseDefaultsAndAreOneShot() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fake = Fake(product, log.copy(enteredAmount = 60.0))
        val vm = FoodsViewModel(fake.repo, MutableStateFlow(date))
        val store = ViewModelStore().apply { put("foods", vm) }
        var undo: FoodUndo? = null
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { if (it is FoodUiEvent.Message) undo = it.undo } }
            runCurrent(); vm.addProductToCart(product); runCurrent()
            assertEquals(2.0, vm.uiState.value.bulkDraft.items.single().quantity!!, 0.0)
            vm.useLastAmount("food"); runCurrent()
            assertEquals(1.5, vm.uiState.value.bulkDraft.items.single().quantity!!, 0.0)
            vm.removeBulkProduct("food"); runCurrent()
            assertTrue(vm.uiState.value.bulkDraft.items.isEmpty())
            val action = requireNotNull(undo)
            vm.undo(action); vm.undo(action); runCurrent()
            assertEquals(1.5, vm.uiState.value.bulkDraft.items.single().quantity!!, 0.0)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}

package com.littleone.dailycutreport

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@OptIn(ExperimentalCoroutinesApi::class)
class CartWorkflowTest {
    @After fun resetMain() = Dispatchers.resetMain()
    private val date = LocalDate.of(2026, 10, 4)
    private val food = ProductEntity("food", name = "Food", quantityMode = "SERVING_AND_WEIGHT", measurePerServing = 40.0)
    private fun cart(day: LocalDate = date) = BulkDraft(day, listOf(BulkDraftItem(food)), "Store", "25", true)

    private class Fake(val food: ProductEntity, var stored: BulkDraft) {
        var logs = listOf(FoodLogSnapshot(id = 1, date = LocalDate.of(2026, 10, 3), productId = food.productId,
            productName = food.name, quantity = 2.0, enteredUnit = "GRAMS", enteredAmount = 80.0))
        var gate: CompletableDeferred<FoodMutationResult>? = null
        var loadGate: CompletableDeferred<BulkDraft>? = null
        var checkoutFailure = false
        var checkouts = 0
        var undos = 0
        val repo = Proxy.newProxyInstance(DailyCutRepository::class.java.classLoader, arrayOf(DailyCutRepository::class.java)) { _, method, args ->
            when (method.name) {
                "observeGoals" -> flowOf(UserGoals())
                "observeReport" -> flowOf(DailyReport(args!![0] as LocalDate))
                "observeFoodLogs", "observeProducts", "observeTemporaryMeals", "observeRecentProducts", "observeFavoriteProducts" -> flowOf(emptyList<Any>())
                "loadCartDraft" -> loadGate?.let { suspendFor(it, args!!) } ?: stored
                "saveCartDraft" -> { stored = args!![0] as BulkDraft; Unit }
                "loadPendingProductDraft" -> null
                "getProduct" -> if (args!![0] == food.productId) ProductWithExtras(food) else null
                "foodLogsForDate" -> logs
                "checkoutCart" -> {
                    checkouts++
                    if (checkoutFailure) error("Disk full")
                    gate?.let { suspendFor(it, args!!) } ?: result(args!![0] as BulkDraft)
                }
                "undoCheckout" -> { undos++; stored = args!![1] as BulkDraft; result(stored).copy(addedLogs = emptyList()) }
                else -> error("Unexpected call ${method.name}")
            }
        } as DailyCutRepository
        private fun <T> suspendFor(gate: CompletableDeferred<T>, args: Array<out Any?>): Any {
            @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<T>
            gate.invokeOnCompletion { error -> if (error == null) continuation.resume(gate.getCompleted()) else continuation.resumeWithException(error) }
            return COROUTINE_SUSPENDED
        }
        fun result(cart: BulkDraft) = FoodMutationResult(requireNotNull(cart.date), NutritionSummary(), NutritionSummary(),
            addedLogs = listOf(logs.first().copy(date = requireNotNull(cart.date))))
    }

    private suspend fun TestScope.withVm(fake: Fake, block: suspend TestScope.(FoodsViewModel, MutableStateFlow<LocalDate>) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val selected = MutableStateFlow(date)
        val vm = FoodsViewModel(fake.repo, selected)
        val store = ViewModelStore().apply { put("food", vm) }
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
            advanceTimeBy(300); runCurrent()
            block(vm, selected)
        } finally { store.clear(); runCurrent() }
    }

    @Test fun historicalCopyDefaultsToTodayAndReportsIndividualSkippedFoods() = runTest {
        val fake = Fake(food, BulkDraft())
        fake.logs = fake.logs + fake.logs.first().copy(id = 2, productId = null, productName = "Deleted food") +
            fake.logs.first().copy(id = 3, enteredUnit = "UNKNOWN", productName = "Bad units")
        withVm(fake) { vm, selected ->
            vm.copyDayToCart(date.minusDays(1)); vm.copyDayToCart(date.minusDays(1))
            selected.value = date.minusDays(10); runCurrent()
            val copied = vm.uiState.value.bulkDraft
            assertEquals(LocalDate.now(), copied.date)
            assertEquals(2.0, copied.items.single().quantity!!, 0.0)
            assertEquals("", copied.actualPaidText)
            assertTrue(copied.copySummary!!.contains("Deleted food: deleted or detached"))
            assertTrue(copied.copySummary!!.contains("Bad units: incompatible"))
        }
    }

    @Test fun cartDateChangesPreserveAllInputsAndRejectFutureDays() = runTest {
        val original = cart()
        withVm(Fake(food, original)) { vm, _ ->
            assertTrue(vm.changeCartDate(date.minusDays(1))); runCurrent()
            assertEquals(original.copy(date = date.minusDays(1)), vm.uiState.value.bulkDraft)
            assertFalse(vm.changeCartDate(LocalDate.now().plusDays(1)))
            vm.closeCart(); runCurrent()
            assertEquals(date.minusDays(1), vm.uiState.value.bulkDraft.date)
        }
    }

    @Test fun copyConflictsCancelKeepOrExplicitlyReplaceWithoutChangingScreenDate() = runTest {
        val original = cart()
        withVm(Fake(food, original)) { vm, selected ->
            val destination = date.minusDays(2)
            vm.copyDayToCart(date.minusDays(1), destinationDate = destination); runCurrent()
            assertEquals(original, vm.uiState.value.bulkDraft)
            assertEquals(destination, vm.uiState.value.cartDateConflict!!.requestedDate)
            vm.resolveCartDateConflict(null); runCurrent()
            assertEquals(original, vm.uiState.value.bulkDraft)
            vm.copyDayToCart(date.minusDays(1), destinationDate = destination); runCurrent()
            vm.resolveCartDateConflict(false); runCurrent()
            assertEquals(date, vm.uiState.value.bulkDraft.date)
            assertEquals("25", vm.uiState.value.bulkDraft.actualPaidText)
            assertEquals(3.0, vm.uiState.value.bulkDraft.items.single().quantity!!, 0.0)
            vm.copyDayToCart(date.minusDays(1), destinationDate = destination); runCurrent()
            vm.resolveCartDateConflict(true); runCurrent()
            assertEquals(destination, vm.uiState.value.bulkDraft.date)
            assertEquals("", vm.uiState.value.bulkDraft.actualPaidText)
            assertEquals(date, selected.value)
        }
    }

    @Test fun checkoutFreezesInputsRejectsDoubleTapAndUndoIsOneShot() = runTest {
        val original = cart()
        val fake = Fake(food, original).apply { gate = CompletableDeferred() }
        withVm(fake) { vm, _ ->
            var receipt: FoodUndo? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { if (it is FoodUiEvent.Message && it.undo != null) receipt = it.undo } }
            vm.confirmBulkPurchase(); vm.confirmBulkPurchase(); runCurrent()
            assertEquals(1, fake.checkouts)
            vm.discardBulkDraft(); vm.incrementCartProduct("food"); vm.updateBulkPaid("0")
            assertFalse(vm.changeCartDate(date.minusDays(1)))
            assertEquals(original, vm.uiState.value.bulkDraft)
            fake.gate!!.complete(fake.result(original)); runCurrent()
            assertTrue(vm.uiState.value.bulkDraft.items.isEmpty())
            vm.undo(requireNotNull(receipt)); vm.undo(requireNotNull(receipt)); runCurrent()
            assertEquals(1, fake.undos)
            assertEquals(original, vm.uiState.value.bulkDraft)
            assertTrue(vm.uiState.value.cartVisible)
        }
    }

    @Test fun failedCheckoutKeepsCartAndAllowsExplicitRetry() = runTest {
        val original = cart()
        val fake = Fake(food, original).apply { checkoutFailure = true }
        withVm(fake) { vm, _ ->
            vm.openCart(); vm.confirmBulkPurchase(); runCurrent()
            assertEquals(original, vm.uiState.value.bulkDraft)
            assertTrue(vm.uiState.value.cartVisible)
            assertFalse(vm.uiState.value.cartSubmitting)
            fake.checkoutFailure = false; vm.confirmBulkPurchase(); runCurrent()
            assertEquals(2, fake.checkouts)
            assertTrue(vm.uiState.value.bulkDraft.items.isEmpty())
        }
    }

    @Test fun additionsWaitForCartRestorationAndCannotOverwriteSavedCart() = runTest {
        val fake = Fake(food, BulkDraft()).apply { loadGate = CompletableDeferred() }
        withVm(fake) { vm, _ ->
            vm.addProductToCart(food)
            fake.loadGate!!.complete(cart(date.minusDays(1))); runCurrent()
            assertEquals(date.minusDays(1), vm.uiState.value.bulkDraft.date)
            assertNotNull(vm.uiState.value.cartDateConflict)
            vm.resolveCartDateConflict(null); runCurrent()
            assertEquals(1.0, vm.uiState.value.bulkDraft.items.single().quantity!!, 0.0)
        }
    }

    @Test fun invalidMeasuredInputCannotSubmitAndUndoCannotOverwriteANewCart() = runTest {
        val original = cart()
        val fake = Fake(food, original)
        withVm(fake) { vm, _ ->
            vm.updateBulkQuantity("food", QuantityUnit.GRAMS, "-"); runCurrent()
            assertFalse(vm.uiState.value.bulkDraft.isValid)
            vm.confirmBulkPurchase(); runCurrent(); assertEquals(0, fake.checkouts)
            vm.resetBulkQuantity("food"); runCurrent()
            vm.undo(FoodUndo.Checkout(original, fake.result(original).addedLogs)); runCurrent()
            assertEquals(0, fake.undos)
            assertEquals(1, vm.uiState.value.bulkDraft.items.size)
        }
    }
}

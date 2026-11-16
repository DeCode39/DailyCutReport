package com.littleone.dailycutreport

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.time.LocalDate
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

@OptIn(ExperimentalCoroutinesApi::class)
class TodayRefreshViewModelTest {
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun manualRefreshUsesWholeWindowEvenOnHistoricalDateAndGuardsRapidTaps() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var calls = 0
        var result = Result.success(Unit)
        val repository = Proxy.newProxyInstance(DailyCutRepository::class.java.classLoader, arrayOf(DailyCutRepository::class.java)) { _, method, args ->
            when (method.name.substringBefore('-')) {
                "initialize" -> Unit
                "observeReport" -> flowOf(DailyReport(args!![0] as LocalDate))
                "observeBurnForecast", "observeInternalBurn" -> flowOf(null)
                "observeFoodLogs" -> flowOf(emptyList<FoodLogSnapshot>())
                "observeGoals" -> flowOf(UserGoals())
                "observeSpending" -> flowOf(DailySpending())
                "refreshRecentHealth" -> {
                    calls++
                    val outcome = result
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<Result<Unit>>
                    backgroundScope.launch { continuation.resume(outcome) }
                    COROUTINE_SUSPENDED
                }
                else -> error("Unexpected ${method.name}")
            }
        } as DailyCutRepository
        val selected = MutableStateFlow(LocalDate.now().minusDays(40))
        val vm = TodayViewModel(repository, selected)
        val store = ViewModelStore().apply { put("today", vm) }
        try {
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect() }
            runCurrent()
            vm.refreshHealth(); vm.refreshHealth()
            runCurrent()
            assertEquals(1, calls)
            assertFalse(vm.uiState.value.refreshing)
            assertEquals(selected.value, vm.uiState.value.report.date)
            assertTrue(vm.uiState.value.message!!.contains("28 days"))
            result = Result.failure(IllegalStateException("Provider busy"))
            vm.refreshHealth(); runCurrent()
            assertEquals("Provider busy", vm.uiState.value.message)
            assertFalse(vm.uiState.value.refreshing)
            result = Result.success(Unit)
            vm.refreshHealth(); runCurrent()
            assertEquals(3, calls)
            assertTrue(vm.uiState.value.message!!.contains("28 days"))
        } finally { store.clear(); runCurrent() }
    }
}

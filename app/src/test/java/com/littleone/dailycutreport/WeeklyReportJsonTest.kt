package com.littleone.dailycutreport

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class WeeklyReportJsonTest {
    private val end = LocalDate.of(2026, 1, 3)

    @Test fun weekIsInclusiveChronologicalAndCrossesYearBoundary() {
        assertEquals((28..31).map { "2025-12-$it" } + (1..3).map { "2026-01-0$it" },
            WeeklyReportJson.datesEndingOn(end).map(LocalDate::toString))
        assertEquals("2024-02-29", WeeklyReportJson.datesEndingOn(LocalDate.of(2024, 3, 6)).first().toString())
    }

    @Test fun envelopeRetainsDailyContractEmptyDaysRoundingAndPrivacy() {
        val days = WeeklyReportJson.datesEndingOn(end).mapIndexed { index, date ->
            TodayUiState(report = DailyReport(date, nutrition = NutritionSummary(calories = if (index == 6) 97.999999999996 else 0.0)),
                logs = if (index == 6) listOf(FoodLogSnapshot(date = date, productName = "Sample", barcode = "private-code")) else emptyList())
        }
        val generated = Instant.parse("2026-01-04T00:00:00Z")
        val encoded = WeeklyReportJson.encode(days, generated)
        val json = JSONObject(encoded)
        assertEquals(1, json.getInt("schemaVersion"))
        assertEquals(3, json.getInt("dailyReportSchemaVersion"))
        assertEquals("2025-12-28", json.getString("startDate"))
        assertEquals(end.toString(), json.getString("endDate"))
        val reports = json.getJSONArray("days")
        assertEquals(7, reports.length())
        repeat(7) { index ->
            val report = reports.getJSONObject(index)
            assertEquals(3, report.getInt("schemaVersion"))
            assertEquals(generated.toString(), report.getString("generatedAt"))
            assertTrue(report.getJSONObject("energy").isNull("projectedFinalBurnKcal"))
            assertEquals(DailyReportJson.encode(days[index], generated), report.toString(2))
        }
        assertEquals(0, reports.getJSONObject(0).getJSONArray("foodGroups").length())
        assertEquals(98, reports.getJSONObject(6).getJSONObject("nutrition").getInt("caloriesKcal"))
        assertFalse(encoded.contains("private-code"))
    }

    @Test fun missingDuplicateOrUnorderedDatesAreRejected() {
        val days = WeeklyReportJson.datesEndingOn(end).map { TodayUiState(report = DailyReport(it)) }
        for (invalid in listOf(days.drop(1), days.reversed(), days.dropLast(1) + days.first())) {
            assertTrue(runCatching { WeeklyReportJson.encode(invalid) }.isFailure)
        }
    }

    @Test fun exporterReadsEachDatesOwnLocalGoalsLogsAndSpendingWithoutRefreshingHealth() = runBlocking {
        val reads = mutableListOf<LocalDate>()
        var initialized = false
        val repo = fake { name, date ->
            if (name == "initialize") { initialized = true; return@fake Unit }
            check(initialized)
            when (name) {
                "observeReport" -> { reads += date!!; flowOf(DailyReport(date)) }
                "observeBurnForecast" -> flowOf(null)
                "observeInternalBurn" -> flowOf(null)
                "observeFoodLogs" -> flowOf(listOf(FoodLogSnapshot(date = date!!, productName = date.toString())))
                "observeGoals" -> flowOf(UserGoals(calories = 1000.0 + date!!.dayOfMonth))
                "observeSpending" -> flowOf(DailySpending(knownTotalMicros = date!!.dayOfMonth * 1000000L))
                else -> error("Unexpected call $name")
            }
        }
        val reports = JSONObject(ReportJsonExporter(repo).export(end, true)).getJSONArray("days")
        assertEquals(WeeklyReportJson.datesEndingOn(end), reads)
        repeat(7) { index ->
            val date = reads[index]
            val report = reports.getJSONObject(index)
            assertEquals(1000 + date.dayOfMonth, report.getJSONObject("targets").getInt("caloriesKcal"))
            assertEquals(date.dayOfMonth * 1000000L, report.getJSONObject("spending").getLong("knownTotalMicros"))
            assertEquals(date.toString(), report.getJSONArray("foodGroups").getJSONObject(0).getJSONArray("items").getJSONObject(0).getString("name"))
        }
    }

    @Test fun dailyExportStillEmitsOneDayAndFailuresDoNotReturnPartialWeeks() = runBlocking {
        val repo = fake { name, date -> when (name) {
            "initialize" -> Unit
            "observeReport" -> flowOf(DailyReport(date!!))
            "observeBurnForecast", "observeInternalBurn" -> flowOf(null)
            "observeFoodLogs" -> flowOf(emptyList<FoodLogSnapshot>())
            "observeGoals" -> flowOf(UserGoals())
            "observeSpending" -> flowOf(DailySpending())
            else -> error("Unexpected $name")
        } }
        val day = JSONObject(ReportJsonExporter(repo).export(end, false))
        assertEquals(end.toString(), day.getString("date"))
        assertFalse(day.has("days"))
        assertTrue(runCatching { ReportJsonExporter(repo).export(LocalDate.now().plusDays(1), true) }.isFailure)
        val failing = fake { name, date -> when (name) {
            "initialize" -> Unit
            "observeReport" -> {
                check(date != end.minusDays(4)) { "Local read failed mid-week" }
                flowOf(DailyReport(date!!))
            }
            "observeBurnForecast", "observeInternalBurn" -> flowOf(null)
            "observeFoodLogs" -> flowOf(emptyList<FoodLogSnapshot>())
            "observeGoals" -> flowOf(UserGoals())
            "observeSpending" -> flowOf(DailySpending())
            else -> error("Unexpected $name")
        } }
        assertTrue(runCatching { ReportJsonExporter(failing).export(end, true) }.isFailure)
    }

    private fun fake(call: (String, LocalDate?) -> Any?): DailyCutRepository =
        Proxy.newProxyInstance(DailyCutRepository::class.java.classLoader, arrayOf(DailyCutRepository::class.java)) { _, method, args ->
            call(method.name, args?.firstOrNull() as? LocalDate)
        } as DailyCutRepository
}

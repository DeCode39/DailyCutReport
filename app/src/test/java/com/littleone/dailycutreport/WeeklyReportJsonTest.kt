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

    @Test fun compactWeekRetainsLastItemOfLargeUnicodeLog() {
        val dates = WeeklyReportJson.datesEndingOn(end)
        val days = dates.map { date ->
            TodayUiState(report = DailyReport(date), logs = (1..100).map { i ->
                FoodLogSnapshot(id = i.toLong(), date = date, productName = "測試 • $i")
            })
        }
        val encoded = WeeklyReportJson.encode(days)
        assertFalse(encoded.contains('\n'))
        val reports = JSONObject(encoded).getJSONArray("days")
        repeat(7) {
            val groups = reports.getJSONObject(it).getJSONArray("foodGroups")
            assertEquals(100, groups.length())
            assertEquals((1..100).map { n -> "測試 • $n" }.toSet(), (0 until groups.length()).map { n ->
                groups.getJSONObject(n).getJSONArray("items").getJSONObject(0).getString("name")
            }.toSet())
        }
    }

    @Test fun exporterUsesOneSnapshotAndRefusesIncompleteResults() = runBlocking {
        val requested = mutableListOf<List<LocalDate>>()
        var dropDay = false
        val repo = Proxy.newProxyInstance(DailyCutRepository::class.java.classLoader, arrayOf(DailyCutRepository::class.java)) { _, method, args ->
            when (method.name) {
                "initialize" -> Unit
                "reportSnapshot" -> {
                    @Suppress("UNCHECKED_CAST") val dates = args!![0] as List<LocalDate>
                    requested += dates
                    (if (dropDay) dates.dropLast(1) else dates).map { TodayUiState(report = DailyReport(it)) }
                }
                else -> error("Unexpected call ${method.name}")
            }
        } as DailyCutRepository
        val exporter = ReportJsonExporter(repo)
        val week = JSONObject(exporter.export(end, true))
        assertEquals(listOf(WeeklyReportJson.datesEndingOn(end)), requested)
        assertEquals(7, week.getJSONArray("datesWithoutBurn").length())
        assertEquals(0, week.getInt("daysWithFoodLogs"))
        assertEquals(end.toString(), JSONObject(exporter.export(end, false)).getString("date"))
        dropDay = true
        assertTrue(runCatching { exporter.export(end, true) }.isFailure)
        assertTrue(runCatching { exporter.export(LocalDate.now().plusDays(1), true) }.isFailure)
    }
}

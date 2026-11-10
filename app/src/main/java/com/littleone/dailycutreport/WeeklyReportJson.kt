package com.littleone.dailycutreport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate

/** A rolling, inclusive seven-day envelope. Daily documents retain their existing contract. */
object WeeklyReportJson {
    const val SCHEMA_VERSION = 1

    fun datesEndingOn(endDate: LocalDate): List<LocalDate> = (6L downTo 0L).map(endDate::minusDays)

    fun encode(days: List<TodayUiState>, generatedAt: Instant = Instant.now()): String {
        require(days.size == 7) { "A weekly report requires seven days." }
        val end = days.last().report.date
        require(days.map { it.report.date } == datesEndingOn(end)) { "Report dates must be consecutive and ascending." }
        return JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("dailyReportSchemaVersion", DailyReportJson.SCHEMA_VERSION)
            .put("app", "DailyCutReport")
            .put("period", "week")
            .put("startDate", days.first().report.date.toString())
            .put("endDate", end.toString())
            .put("generatedAt", generatedAt.toString())
            .put("days", JSONArray().apply {
                days.forEach { put(JSONObject(DailyReportJson.encode(it, generatedAt))) }
            })
            .toString(2)
    }
}

/** Reads each day's local data, never the visible screen's possibly stale state or Health Connect. */
internal class ReportJsonExporter(private val repository: DailyCutRepository) {
    suspend fun export(endDate: LocalDate, weekly: Boolean): String = withContext(Dispatchers.IO) {
        require(!endDate.isAfter(LocalDate.now())) { "Future dates cannot be exported." }
        repository.initialize()
        val dates = if (weekly) WeeklyReportJson.datesEndingOn(endDate) else listOf(endDate)
        val days = dates.map { date ->
            TodayUiState(
                report = repository.observeReport(date).first(),
                burnForecast = repository.observeBurnForecast(date).first(),
                internalBurn = repository.observeInternalBurn(date).first(),
                logs = repository.observeFoodLogs(date).first(),
                goals = repository.observeGoals(date).first(),
                spending = repository.observeSpending(date).first()
            )
        }
        if (weekly) WeeklyReportJson.encode(days) else DailyReportJson.encode(days.single())
    }
}

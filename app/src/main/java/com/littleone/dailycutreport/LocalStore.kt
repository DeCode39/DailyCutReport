package com.littleone.dailycutreport

import android.content.Context
import java.time.LocalDate

class LocalStore(context: Context) {
    private val prefs = context.getSharedPreferences("daily_reports", Context.MODE_PRIVATE)

    fun load(date: LocalDate): DailyReport? {
        val raw = prefs.getString(date.toString(), null) ?: return null
        return runCatching { DailyReport.fromJson(org.json.JSONObject(raw)) }.getOrNull()
    }

    fun save(report: DailyReport) {
        prefs.edit().putString(report.date.toString(), report.toJson().toString()).apply()
    }

    fun mergeHealth(date: LocalDate, health: HealthSummary): DailyReport {
        val existing = load(date)
        return if (existing == null) {
            DailyReport(date = date, health = health)
        } else {
            existing.copy(health = health, savedAtEpochMs = System.currentTimeMillis())
        }.also { save(it) }
    }

    fun mergeManual(date: LocalDate, manual: ManualEntry, currentHealth: HealthSummary): DailyReport {
        val existing = load(date)
        return if (existing == null) {
            DailyReport(date = date, health = currentHealth, manual = manual)
        } else {
            existing.copy(manual = manual, health = currentHealth, savedAtEpochMs = System.currentTimeMillis())
        }.also { save(it) }
    }
}

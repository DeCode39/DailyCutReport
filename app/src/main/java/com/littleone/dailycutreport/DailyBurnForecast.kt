package com.littleone.dailycutreport

import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

enum class BurnForecastSource { ACTUAL, HISTORICAL_REMAINDER, PROVIDER_FALLBACK, UNAVAILABLE }
enum class BurnForecastConfidence { HIGH, MEDIUM, LOW, UNAVAILABLE }

data class BurnForecast(
    val date: LocalDate,
    val liveBurnCalories: Double?,
    val estimatedFinalCalories: Double?,
    val lowerBoundCalories: Double?,
    val upperBoundCalories: Double?,
    val source: BurnForecastSource,
    val confidence: BurnForecastConfidence,
    val sampleDays: Int,
    val refreshedAtEpochMs: Long
) {
    val isEstimate: Boolean get() = source == BurnForecastSource.HISTORICAL_REMAINDER ||
        source == BurnForecastSource.PROVIDER_FALLBACK
}

data class CompletedBurnDay(val date: LocalDate, val totalCalories: Double)

class DailyBurnForecastEngine {
    fun forecast(
        date: LocalDate,
        today: LocalDate,
        refreshedAt: Instant,
        zone: ZoneId,
        liveBurnCalories: Double?,
        providerFullDayCalories: Double?,
        completedDays: List<CompletedBurnDay>
    ): BurnForecast {
        val live = liveBurnCalories?.takeIf { it.isFinite() && it > 0.0 }
        if (date != today) {
            return BurnForecast(
                date, live, live, live, live,
                if (live == null) BurnForecastSource.UNAVAILABLE else BurnForecastSource.ACTUAL,
                if (live == null) BurnForecastConfidence.UNAVAILABLE else BurnForecastConfidence.HIGH,
                0, refreshedAt.toEpochMilli()
            )
        }

        val rates = completedDays.asSequence()
            .filter { it.date < today && it.date >= today.minusDays(28) }
            .mapNotNull { day ->
                val hours = Duration.between(
                    day.date.atStartOfDay(zone).toInstant(),
                    day.date.plusDays(1).atStartOfDay(zone).toInstant()
                ).toMinutes() / 60.0
                day.totalCalories.takeIf { it.isFinite() && it > 0.0 && hours > 0.0 }?.div(hours)
            }
            .toList()
            .robustRates()

        val now = refreshedAt.atZone(zone)
        val end = today.plusDays(1).atStartOfDay(zone)
        val remainingHours = Duration.between(now, end).toMinutes().coerceAtLeast(0) / 60.0
        if (live != null && rates.isNotEmpty()) {
            val rate = rates.median()
            val lowerRate: Double
            val upperRate: Double
            if (rates.size >= 7) {
                lowerRate = rates.percentile(0.25)
                upperRate = rates.percentile(0.75)
            } else {
                lowerRate = rate * 0.85
                upperRate = rate * 1.15
            }
            val estimate = live + rate * remainingHours
            return BurnForecast(
                date = date,
                liveBurnCalories = live,
                estimatedFinalCalories = estimate,
                lowerBoundCalories = live + lowerRate * remainingHours,
                upperBoundCalories = live + upperRate * remainingHours,
                source = BurnForecastSource.HISTORICAL_REMAINDER,
                confidence = when {
                    rates.size >= 14 -> BurnForecastConfidence.HIGH
                    rates.size >= 7 -> BurnForecastConfidence.MEDIUM
                    else -> BurnForecastConfidence.LOW
                },
                sampleDays = rates.size,
                refreshedAtEpochMs = refreshedAt.toEpochMilli()
            )
        }

        val provider = providerFullDayCalories?.takeIf { it.isFinite() && it > 0.0 }
        if (provider != null) {
            val estimate = maxOf(provider, live ?: 0.0)
            return BurnForecast(
                date, live, estimate, estimate * 0.85, estimate * 1.15,
                BurnForecastSource.PROVIDER_FALLBACK, BurnForecastConfidence.LOW,
                0, refreshedAt.toEpochMilli()
            )
        }
        return BurnForecast(
            date, live, null, null, null, BurnForecastSource.UNAVAILABLE,
            BurnForecastConfidence.UNAVAILABLE, 0, refreshedAt.toEpochMilli()
        )
    }

    private fun List<Double>.robustRates(): List<Double> {
        if (size < 5) return sorted()
        val middle = median()
        val mad = map { abs(it - middle) }.median()
        if (mad <= 0.0) return filter { it == middle }.ifEmpty { this }.sorted()
        val limit = 3.0 * 1.4826 * mad
        return filter { abs(it - middle) <= limit }.sorted()
    }
}

private fun List<Double>.median(): Double = percentile(0.5)

private fun List<Double>.percentile(fraction: Double): Double {
    require(isNotEmpty())
    val sorted = sorted()
    val index = (sorted.lastIndex * fraction.coerceIn(0.0, 1.0))
    val low = index.toInt()
    val high = kotlin.math.ceil(index).toInt()
    if (low == high) return sorted[low]
    return sorted[low] + (sorted[high] - sorted[low]) * (index - low)
}

object BurnForecastCodec {
    fun encode(value: BurnForecast): String = JSONObject()
        .put("date", value.date.toString())
        .put("live", value.liveBurnCalories ?: JSONObject.NULL)
        .put("estimate", value.estimatedFinalCalories ?: JSONObject.NULL)
        .put("lower", value.lowerBoundCalories ?: JSONObject.NULL)
        .put("upper", value.upperBoundCalories ?: JSONObject.NULL)
        .put("source", value.source.name)
        .put("confidence", value.confidence.name)
        .put("sampleDays", value.sampleDays)
        .put("refreshedAt", value.refreshedAtEpochMs)
        .toString()

    fun decode(value: String): BurnForecast? = runCatching {
        val json = JSONObject(value)
        fun nullableDouble(key: String) = if (json.isNull(key)) null else json.getDouble(key)
        BurnForecast(
            date = LocalDate.parse(json.getString("date")),
            liveBurnCalories = nullableDouble("live"),
            estimatedFinalCalories = nullableDouble("estimate"),
            lowerBoundCalories = nullableDouble("lower"),
            upperBoundCalories = nullableDouble("upper"),
            source = BurnForecastSource.valueOf(json.getString("source")),
            confidence = BurnForecastConfidence.valueOf(json.getString("confidence")),
            sampleDays = json.getInt("sampleDays"),
            refreshedAtEpochMs = json.getLong("refreshedAt")
        )
    }.getOrNull()
}

internal fun burnForecastMetadataKey(date: LocalDate) = "burn_forecast_v1:$date"

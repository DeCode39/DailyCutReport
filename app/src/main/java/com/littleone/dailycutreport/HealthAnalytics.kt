package com.littleone.dailycutreport

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt

enum class WeightUnit(val label: String) {
    KG("kg"), LB("lb");

    fun fromKg(value: Double): Double = if (this == KG) value else value * POUNDS_PER_KILOGRAM
    fun toKg(value: Double): Double = if (this == KG) value else value / POUNDS_PER_KILOGRAM

    private companion object { const val POUNDS_PER_KILOGRAM = 2.2046226218 }
}

enum class WeightSource { MANUAL, HEALTH_CONNECT }
enum class HealthDataQuality { LOW, MEDIUM, HIGH }
enum class WalkingEstimateSource { PERSONAL, HYBRID, WEIGHT_FALLBACK }

data class HealthProfile(
    val weightUnit: WeightUnit = WeightUnit.KG,
    val targetWeightKg: Double? = null
)

data class WeightEntry(
    val entryId: String,
    val date: LocalDate,
    val recordedAtEpochMs: Long,
    val weightKg: Double,
    val source: WeightSource
)

data class WalkingSessionSample(
    val sessionId: String,
    val date: LocalDate,
    val startEpochMs: Long,
    val durationMinutes: Double,
    val steps: Long,
    val distanceKm: Double,
    val activeCalories: Double
)

data class DeficitHistoryDay(
    val date: LocalDate,
    val burnCalories: Double,
    val intakeCalories: Double,
    val nutritionPresent: Boolean
) {
    val valid: Boolean get() = burnCalories.isFinite() && burnCalories > 0.0 && nutritionPresent
    val deficitCalories: Double get() = burnCalories - intakeCalories
}

data class HealthTrendPoint(
    val date: LocalDate,
    val deficitCalories: Double?,
    val weightKg: Double?
)

data class WeightProjection(
    val currentWeightKg: Double?,
    val currentWeightSource: WeightSource?,
    val currentWeightDate: LocalDate?,
    val weeklyChangeKg: Double?,
    val weeklyLowKg: Double?,
    val weeklyHighKg: Double?,
    val fourWeekChangeKg: Double?,
    val fourWeekLowKg: Double?,
    val fourWeekHighKg: Double?,
    val targetDateEarly: LocalDate? = null,
    val targetDateLate: LocalDate? = null,
    val targetReached: Boolean = false,
    val validDeficitDays: Int = 0,
    val weightDays: Int = 0,
    val quality: HealthDataQuality = HealthDataQuality.LOW
)

data class WalkingEstimate(
    val additionalBurnNeeded: Double,
    val distanceKm: Double,
    val steps: Long,
    val minutes: Double,
    val estimatedBurn: Double,
    val remainingGap: Double,
    val source: WalkingEstimateSource,
    val sampleCount: Int,
    val capped: Boolean
)

data class HealthDashboard(
    val selectedDate: LocalDate,
    val projectedBurnCalories: Double?,
    val intakeCalories: Double,
    val intakePresent: Boolean,
    val desiredDeficitCalories: Double,
    val projectedDeficitCalories: Double?,
    val remainingDeficitGap: Double?,
    val profile: HealthProfile,
    val projection: WeightProjection,
    val walkingEstimate: WalkingEstimate?,
    val trends: List<HealthTrendPoint>,
    val selectedDateWeights: List<WeightEntry> = emptyList(),
    val historyLastSynced: String? = null,
    val burnForecast: BurnForecast? = null
) {
    val latestWeight: WeightEntry? get() = selectedDateWeights.maxByOrNull(WeightEntry::recordedAtEpochMs)
    val selectedDateMedianKg: Double? get() = selectedDateWeights
        .map(WeightEntry::weightKg).filter(Double::isFinite).takeIf(List<Double>::isNotEmpty)?.medianValue()
}

class HealthAnalyticsEngine {
    fun dashboard(
        selectedDate: LocalDate,
        today: LocalDate,
        report: DailyReportEntity?,
        nutrition: DailyNutritionTotals,
        goals: UserGoals,
        profile: HealthProfile,
        history: List<DeficitHistoryDay>,
        weights: List<WeightEntry>,
        walkingSamples: List<WalkingSessionSample>,
        historyLastSynced: String?,
        burnForecast: BurnForecast? = null
    ): HealthDashboard {
        val burn = report?.totalCalories?.takeIf { it.isFinite() && it > 0.0 }
        val localNutritionPresent = nutrition.entries > 0
        val hcNutritionPresent = (report?.nutritionRecords ?: 0) > 0
        val intake = if (localNutritionPresent) nutrition.calories else report?.nutritionCalories ?: 0.0
        val intakePresent = localNutritionPresent || hcNutritionPresent
        val projectedDeficit = if (burn != null && intakePresent) burn - intake else null
        val gap = projectedDeficit?.let { (goals.desiredDeficitCalories - it).coerceAtLeast(0.0) }
        val dailyWeights = representativeWeights(weights)
        val completedHistory = if (selectedDate == today) history.filterNot { it.date == today } else history
        val projection = projectWeight(selectedDate, goals.desiredDeficitCalories, completedHistory, dailyWeights, profile.targetWeightKg)
        val walking = if (selectedDate == today && gap != null && gap > 0.0) {
            walkingEstimate(gap, projection.currentWeightKg, walkingSamples)
        } else null
        val historyByDate = history.associateBy(DeficitHistoryDay::date)
        val weightsByDate = dailyWeights.mapIndexed { index, entry ->
            val window = dailyWeights.subList((index - 1).coerceAtLeast(0), (index + 2).coerceAtMost(dailyWeights.size))
            entry.copy(weightKg = window.map(WeightEntry::weightKg).median())
        }.associateBy(WeightEntry::date)
        val start = selectedDate.minusDays(27)
        val trends = generateSequence(start) { date -> date.plusDays(1).takeIf { it <= selectedDate } }
            .map { date ->
                HealthTrendPoint(
                    date,
                    historyByDate[date]?.takeIf(DeficitHistoryDay::valid)?.deficitCalories,
                    weightsByDate[date]?.weightKg
                )
            }.toList()
        return HealthDashboard(
            selectedDate, burn, intake, intakePresent, goals.desiredDeficitCalories,
            projectedDeficit, gap, profile, projection, walking, trends,
            weights.filter { it.date == selectedDate }.sortedByDescending(WeightEntry::recordedAtEpochMs),
            historyLastSynced,
            burnForecast
        )
    }

    fun projectWeight(
        endDate: LocalDate,
        desiredDeficitCalories: Double,
        history: List<DeficitHistoryDay>,
        weights: List<WeightEntry>,
        targetWeightKg: Double?
    ): WeightProjection {
        val validDays = history.filter(DeficitHistoryDay::valid).sortedBy(DeficitHistoryDay::date)
        val dailyDeficits = validDays.map(DeficitHistoryDay::deficitCalories)
        val goalRate = desiredDeficitCalories.coerceAtLeast(0.0) * 7.0 / KCAL_PER_KILOGRAM
        val observedRate = trimmedMean(dailyDeficits)?.times(7.0)?.div(KCAL_PER_KILOGRAM)
        val historyWeight = (validDays.size / 14.0).coerceIn(0.0, 1.0)
        val energyRate = observedRate?.let { goalRate * (1.0 - historyWeight) + it * historyWeight } ?: goalRate

        val dailyWeights = representativeWeights(weights).sortedBy(WeightEntry::date)
        val current = dailyWeights.lastOrNull()
        val spanDays = dailyWeights.firstOrNull()?.let { first ->
            java.time.temporal.ChronoUnit.DAYS.between(first.date, dailyWeights.last().date).toDouble()
        } ?: 0.0
        val scaleRate = if (dailyWeights.size >= 3 && spanDays >= 7.0) {
            -theilSenSlope(dailyWeights) * 7.0
        } else null
        val scaleReliability = if (scaleRate == null) 0.0 else {
            0.35 * (dailyWeights.size / 7.0).coerceAtMost(1.0) * (spanDays / 21.0).coerceAtMost(1.0)
        }
        val expected = energyRate * (1.0 - scaleReliability) + (scaleRate ?: energyRate) * scaleReliability
        val robustDailyVariation = medianAbsoluteDeviation(dailyDeficits)?.times(1.4826) ?: 0.0
        val weeklyVariation = robustDailyVariation * sqrt(7.0) / KCAL_PER_KILOGRAM
        val disagreement = scaleRate?.let { abs(energyRate - it) / 2.0 } ?: 0.0
        val margin = maxOf(0.05, abs(expected) * 0.15, weeklyVariation, disagreement)
        val low = expected - margin
        val high = expected + margin
        val targetReached = current != null && targetWeightKg != null && current.weightKg <= targetWeightKg
        var early: LocalDate? = null
        var late: LocalDate? = null
        if (!targetReached && current != null && targetWeightKg != null && targetWeightKg < current.weightKg && low > 0.0) {
            val remaining = current.weightKg - targetWeightKg
            early = endDate.plusDays(ceil(remaining / high * 7.0).toLong())
            late = endDate.plusDays(ceil(remaining / low * 7.0).toLong())
        }
        val quality = when {
            validDays.size >= 14 && dailyWeights.size >= 5 && spanDays >= 14.0 -> HealthDataQuality.HIGH
            validDays.size >= 7 || (dailyWeights.size >= 3 && spanDays >= 7.0) -> HealthDataQuality.MEDIUM
            else -> HealthDataQuality.LOW
        }
        return WeightProjection(
            current?.weightKg, current?.source, current?.date,
            expected, low, high,
            expected * 4.0, expected * 4.0 - margin * 2.0, expected * 4.0 + margin * 2.0,
            early, late, targetReached, validDays.size, dailyWeights.size, quality
        )
    }

    fun walkingEstimate(
        additionalBurnNeeded: Double,
        weightKg: Double?,
        samples: List<WalkingSessionSample>
    ): WalkingEstimate? {
        if (!additionalBurnNeeded.isFinite() || additionalBurnNeeded <= 0.0) return null
        val plausible = samples.filter(::plausibleSession).sortedByDescending(WalkingSessionSample::startEpochMs).take(50)
        val personalCalories = plausible.mapNotNull { sample ->
            (sample.activeCalories / sample.distanceKm).takeIf { it in 10.0..200.0 }
        }.takeIf { it.size >= 3 }?.median()
        val personalSteps = plausible.mapNotNull { sample ->
            (sample.steps / sample.distanceKm).takeIf { it in 500.0..2_500.0 }
        }.takeIf { it.size >= 3 }?.median()
        val personalSpeed = plausible.mapNotNull { sample ->
            (sample.distanceKm / (sample.durationMinutes / 60.0)).takeIf { it in 1.5..8.0 }
        }.takeIf { it.size >= 3 }?.median()
        val kcalPerKm = personalCalories ?: weightKg?.takeIf { it > 0.0 }?.times(0.5) ?: return null
        val stepsPerKm = personalSteps ?: 1_300.0
        val speedKmh = personalSpeed ?: 5.0
        val fullDistance = additionalBurnNeeded / kcalPerKm
        val fullMinutes = fullDistance / speedKmh * 60.0
        val capped = fullMinutes > MAX_WALK_MINUTES
        val minutes = fullMinutes.coerceAtMost(MAX_WALK_MINUTES)
        val distance = speedKmh * minutes / 60.0
        val burn = (distance * kcalPerKm).coerceAtMost(additionalBurnNeeded)
        val source = when {
            personalCalories != null && personalSteps != null && personalSpeed != null -> WalkingEstimateSource.PERSONAL
            personalCalories != null || personalSteps != null || personalSpeed != null -> WalkingEstimateSource.HYBRID
            else -> WalkingEstimateSource.WEIGHT_FALLBACK
        }
        return WalkingEstimate(
            additionalBurnNeeded, distance, (distance * stepsPerKm).toLong(), minutes,
            burn, (additionalBurnNeeded - burn).coerceAtLeast(0.0), source, plausible.size, capped
        )
    }

    fun representativeWeights(entries: List<WeightEntry>): List<WeightEntry> = entries
        .filter { it.weightKg.isFinite() && it.weightKg > 0.0 }
        .groupBy(WeightEntry::date)
        .mapNotNull { (date, values) ->
            val latest = values.maxByOrNull(WeightEntry::recordedAtEpochMs) ?: return@mapNotNull null
            latest.copy(date = date, weightKg = values.map(WeightEntry::weightKg).medianValue())
        }.sortedBy(WeightEntry::date)

    private fun plausibleSession(sample: WalkingSessionSample): Boolean =
        sample.durationMinutes in 5.0..240.0 && sample.distanceKm in 0.2..30.0

    private fun trimmedMean(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.filter(Double::isFinite).sorted()
        if (sorted.isEmpty()) return null
        val trim = if (sorted.size >= 10) (sorted.size * 0.1).toInt() else 0
        val kept = sorted.subList(trim, sorted.size - trim)
        return kept.average()
    }

    private fun theilSenSlope(weights: List<WeightEntry>): Double {
        val slopes = buildList {
            weights.indices.forEach { left ->
                for (right in left + 1 until weights.size) {
                    val days = java.time.temporal.ChronoUnit.DAYS.between(weights[left].date, weights[right].date)
                    if (days > 0) add((weights[right].weightKg - weights[left].weightKg) / days.toDouble())
                }
            }
        }
        return slopes.median()
    }

    private fun medianAbsoluteDeviation(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val median = values.median()
        return values.map { abs(it - median) }.median()
    }

    private fun List<Double>.median(): Double {
        val sorted = filter(Double::isFinite).sorted()
        require(sorted.isNotEmpty())
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    private companion object {
        const val KCAL_PER_KILOGRAM = 7_700.0
        const val MAX_WALK_MINUTES = 90.0
    }
}

private fun List<Double>.medianValue(): Double {
    val sorted = filter(Double::isFinite).sorted()
    require(sorted.isNotEmpty())
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
}

fun HealthProfileEntity.toDomain() = HealthProfile(
    WeightUnit.entries.firstOrNull { it.name == weightUnit } ?: WeightUnit.KG,
    targetWeightKg
)

fun HealthProfile.toEntity() = HealthProfileEntity(weightUnit = weightUnit.name, targetWeightKg = targetWeightKg)

fun WeightEntryEntity.toDomain() = WeightEntry(
    entryId,
    LocalDate.parse(date),
    recordedAtEpochMs,
    weightKg,
    WeightSource.entries.firstOrNull { it.name == source } ?: WeightSource.HEALTH_CONNECT
)

fun WeightEntry.toEntity() = WeightEntryEntity(entryId, date.toString(), recordedAtEpochMs, weightKg, source.name)

fun WalkingSessionSampleEntity.toDomain() = WalkingSessionSample(
    sessionId, LocalDate.parse(date), startEpochMs, durationMinutes, steps, distanceKm, activeCalories
)

fun WalkingSessionSample.toEntity() = WalkingSessionSampleEntity(
    sessionId, date.toString(), startEpochMs, durationMinutes, steps, distanceKm, activeCalories
)

fun WeightEntry.displayInstant(zone: ZoneId = ZoneId.systemDefault()) =
    Instant.ofEpochMilli(recordedAtEpochMs).atZone(zone)

package com.littleone.dailycutreport

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONObject
import kotlin.math.max

/** Movement summaries contain no provider calorie values, routes, or identifying session metadata. */
data class InternalActivity(
    val outsideDistanceKm: Double? = null,
    val outsideSteps: Long? = null,
    val exerciseNetMetHours: Double = 0.0,
    val unsupportedSessions: Int = 0,
    val overlapDetected: Boolean = false
)

internal data class ExerciseInterval(val start: Instant, val end: Instant, val met: Double?)
internal data class ActivitySlice(val start: Instant, val end: Instant, val sessions: Int, val met: Double?)

/** Partition the union of clipped sessions so duplicate/overlapping providers never add energy twice. */
internal fun activitySlices(start: Instant, end: Instant, sessions: List<ExerciseInterval>): List<ActivitySlice> {
    if (end <= start) return emptyList()
    val clipped = sessions.mapNotNull {
        val a = maxOf(start, it.start); val b = minOf(end, it.end)
        if (a < b) it.copy(start = a, end = b) else null
    }
    val points = (listOf(start, end) + clipped.flatMap { listOf(it.start, it.end) }).distinct().sorted()
    return points.zipWithNext().map { (a, b) ->
        val active = clipped.filter { it.start < b && it.end > a }
        ActivitySlice(a, b, active.size, active.mapNotNull { it.met?.takeIf { m -> m.isFinite() && m >= 1 } }.maxOrNull())
    }
}

data class InternalBurnEstimate(
    val date: LocalDate,
    val refreshedAtEpochMs: Long,
    val restingDailyKcal: Double,
    val movementKcal: Double,
    val exerciseKcal: Double,
    val backgroundDailyKcal: Double,
    val burnSoFarKcal: Double,
    val finalKcal: Double,
    val lowerKcal: Double,
    val upperKcal: Double,
    val sampleDays: Int,
    val explanation: String
) {
    fun toJson() = JSONObject().apply {
        put("modelVersion", 1); put("date", date.toString()); put("refreshedAtEpochMs", refreshedAtEpochMs)
        put("restingDailyKcal", restingDailyKcal); put("movementKcal", movementKcal)
        put("exerciseKcal", exerciseKcal); put("backgroundDailyKcal", backgroundDailyKcal)
        put("burnSoFarKcal", burnSoFarKcal); put("finalKcal", finalKcal)
        put("lowerKcal", lowerKcal); put("upperKcal", upperKcal); put("sampleDays", sampleDays)
        put("explanation", explanation)
    }
    companion object {
        fun decode(raw: String?): InternalBurnEstimate? = runCatching {
            val o = JSONObject(requireNotNull(raw)); require(o.getInt("modelVersion") == 1)
            InternalBurnEstimate(LocalDate.parse(o.getString("date")), o.getLong("refreshedAtEpochMs"),
                o.getDouble("restingDailyKcal"), o.getDouble("movementKcal"), o.getDouble("exerciseKcal"),
                o.getDouble("backgroundDailyKcal"), o.getDouble("burnSoFarKcal"), o.getDouble("finalKcal"),
                o.getDouble("lowerKcal"), o.getDouble("upperKcal"), o.getInt("sampleDays"), o.getString("explanation"))
        }.getOrNull()
    }
}

object RestingEnergyEngine {
    /** Mifflin–St Jeor estimated resting expenditure in kcal per 24 hours. */
    fun calculate(profile: GoalAssistantProfile, weightKg: Double = profile.weightKg): Double {
        profile.copy(weightKg = weightKg).validateBody()
        return 10 * weightKg + 6.25 * profile.heightCm - 5 * profile.age +
            if (profile.equationSex == GoalEquationSex.MALE) 5 else -161
    }
}

object InternalBurnEngine {
    fun estimate(date: LocalDate, now: Instant, zone: ZoneId, profile: GoalAssistantProfile,
                 weightKg: Double, activity: InternalActivity,
                 completedMovementKcalPerHour: List<Double> = emptyList(),
                 weightSource: String = "body-profile weight"): InternalBurnEstimate {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        require(now >= start) { "Cannot forecast a future date" }
        val through = minOf(now, end)
        val hours = Duration.between(start, end).seconds / 3600.0
        val elapsed = Duration.between(start, through).seconds / 3600.0
        val remaining = max(0.0, hours - elapsed)
        val rest = RestingEnergyEngine.calculate(profile, weightKg) * hours / 24.0
        // A deliberately modest explicit modeling allowance, not a measured value or a PAL multiplier.
        val background = rest * 0.10
        val distance = activity.outsideDistanceKm?.takeIf { it.isFinite() && it >= 0 }
            ?: activity.outsideSteps?.takeIf { it >= 0 }?.let { it / 1300.0 }
        val movement = (distance ?: 0.0) * weightKg * 0.5
        val exercise = activity.exerciseNetMetHours.takeIf { it.isFinite() && it >= 0 }?.times(weightKg * 1.05) ?: 0.0
        val rates = completedMovementKcalPerHour.filter { it.isFinite() && it in 0.0..200.0 }.sorted()
        val medianRate = if (rates.isEmpty()) 0.0 else (rates[(rates.size - 1) / 2] + rates[rates.size / 2]) / 2
        val soFar = (rest + background) * elapsed / hours + movement + exercise
        val final = soFar + (rest + background) * remaining / hours + medianRate * remaining
        val warnings = buildList {
            add("Comparison only · low confidence · $weightSource")
            if (distance == null) add("Movement data missing; activity estimate incomplete")
            else if (activity.outsideDistanceKm == null) add("Steps converted at 1,300/km")
            if (activity.unsupportedSessions > 0) add("${activity.unsupportedSessions} unsupported exercise session(s) omitted")
            if (activity.overlapDetected) add("Overlapping sessions counted once")
            if (rates.isEmpty() && remaining > 0) add("No completed internal history; remaining day includes resting/background only")
            add("Background allowance 10% of resting burn; range ±25% is a model sensitivity band, not a medical confidence interval")
        }
        return InternalBurnEstimate(date, through.toEpochMilli(), rest, movement, exercise, background,
            soFar, final, final * 0.75, final * 1.25, rates.size, warnings.joinToString(". "))
    }
}

internal fun internalBurnKey(date: LocalDate) = "internal_burn_v1_$date"

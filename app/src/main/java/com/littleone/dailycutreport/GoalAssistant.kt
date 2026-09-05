package com.littleone.dailycutreport

import java.time.LocalDate
import org.json.JSONObject

enum class GoalEquationSex { FEMALE, MALE }
enum class GoalActivity(val multiplier: Double) { LOW(1.2), LIGHT(1.375), MODERATE(1.55), HIGH(1.725) }
enum class SuggestedTarget { CALORIES, PROTEIN, FAT, CARBS, FIBER, SATURATED_FAT }

data class GoalAssistantProfile(
    val age: Int,
    val heightCm: Double,
    val weightKg: Double,
    val equationSex: GoalEquationSex,
    val activity: GoalActivity,
    val adaptive: Boolean = false,
    val locks: Set<SuggestedTarget> = emptySet(),
    val reviewedOn: LocalDate = LocalDate.now()
) {
    fun validate() = apply {
        require(age in 18..100) { "Goal suggestions are for adults aged 18–100." }
        require(heightCm.isFinite() && heightCm in 120.0..230.0) { "Enter height between 120 and 230 cm." }
        require(weightKg.isFinite() && weightKg in 35.0..250.0) { "Enter weight between 35 and 250 kg." }
        require(weightKg / (heightCm * heightCm / 10000) >= 18.5) { "Weight-loss suggestions are unavailable below BMI 18.5." }
    }
}

data class GoalSuggestion(val goals: UserGoals, val explanation: String)

/** Deterministic estimates for a reviewed adult profile, not prescriptions. */
object NutritionGoalEngine {
    fun suggest(profile: GoalAssistantProfile, current: UserGoals, recentWeightKg: Double? = null,
                historicalBurn: Double? = null): GoalSuggestion {
        profile.validate()
        current.requireValid()
        val weight = recentWeightKg?.takeIf { it.isFinite() && it in 35.0..250.0 } ?: profile.weightKg
        profile.copy(weightKg = weight).validate()
        val resting = 10 * weight + 6.25 * profile.heightCm - 5 * profile.age +
            if (profile.equationSex == GoalEquationSex.MALE) 5 else -161
        val burn = historicalBurn?.takeIf { it.isFinite() && it > 0 } ?: resting * profile.activity.multiplier
        val allowance = if (SuggestedTarget.CALORIES in profile.locks) current.calories else burn - current.desiredDeficitCalories
        require(allowance >= 1200 && current.desiredDeficitCalories <= burn * 0.3) {
            "This deficit is outside the assistant's conservative range. Reduce it or use manually reviewed goals."
        }
        fun value(key: SuggestedTarget, old: Double, suggested: Double) = if (key in profile.locks) old else suggested
        val protein = value(SuggestedTarget.PROTEIN, current.proteinG, weight * 1.6)
        val fat = value(SuggestedTarget.FAT, current.fatG, allowance * 0.3 / 9)
        val carbs = value(SuggestedTarget.CARBS, current.carbsG, (allowance - protein * 4 - fat * 9) / 4)
        require(carbs >= 0 && protein * 4 + fat * 9 <= allowance) {
            "Locked protein/fat targets leave no carbohydrate allowance. Review your locks or calorie target."
        }
        return GoalSuggestion(current.copy(
            calories = allowance, proteinG = protein, fatG = fat, carbsG = carbs,
            fiberG = value(SuggestedTarget.FIBER, current.fiberG, 25.0),
            saturatedFatG = value(SuggestedTarget.SATURATED_FAT, current.saturatedFatG, minOf(current.saturatedFatG, allowance * 0.1 / 9))
        ), "${if (historicalBurn != null) "Completed-day burn history" else "Mifflin–St Jeor × activity estimate"}; " +
            "${if (recentWeightKg != null) "recent median weight" else "setup weight"}. Protein 1.6 g/kg, fat 30% of energy, carbs remainder, fiber 25 g. " +
            "Sodium and total-sugar limits stay unchanged. Deficit mode still uses the live burn forecast for calories.")
    }
}

/** Effective-date ledger. Dates before the first change retain the baseline; no per-day backfill needed. */
data class GoalAssistantState(
    val profile: GoalAssistantProfile? = null,
    val baseline: UserGoals = UserGoals(),
    val history: Map<LocalDate, UserGoals> = emptyMap(),
    val previous: UserGoals? = null,
    val lastAdapted: LocalDate? = null,
    val status: String = "Not configured"
) {
    fun goalsFor(date: LocalDate, current: UserGoals): UserGoals {
        val historical = history.filterKeys { it <= date }.maxByOrNull { it.key }?.value ?: baseline
        return historical.copy(currencyCode = current.currencyCode, dailyBudgetMicros = current.dailyBudgetMicros)
    }
    companion object { const val KEY = "goal_assistant_v1" }
}

object GoalAssistantCodec {
    fun encode(state: GoalAssistantState): String = JSONObject().apply {
        put("version", 1); put("baseline", BackupJson.encodeGoals(state.baseline))
        state.previous?.let { put("previous", BackupJson.encodeGoals(it)) }
        put("status", state.status); put("lastAdapted", state.lastAdapted?.toString())
        put("history", JSONObject().apply { state.history.forEach { (date, goals) -> put(date.toString(), BackupJson.encodeGoals(goals)) } })
        state.profile?.let { p -> put("profile", JSONObject().apply {
            put("age", p.age); put("heightCm", p.heightCm); put("weightKg", p.weightKg)
            put("sex", p.equationSex.name); put("activity", p.activity.name); put("adaptive", p.adaptive)
            put("locks", org.json.JSONArray(p.locks.map { it.name }.sorted())); put("reviewedOn", p.reviewedOn.toString())
        }) }
    }.toString()

    fun decode(raw: String): GoalAssistantState {
        val o = JSONObject(raw)
        require(o.getInt("version") == 1) { "Unsupported goal assistant data." }
        val p = o.optJSONObject("profile")
        val h = o.getJSONObject("history")
        return GoalAssistantState(
            profile = p?.let { GoalAssistantProfile(it.getInt("age"), it.getDouble("heightCm"), it.getDouble("weightKg"),
                GoalEquationSex.valueOf(it.getString("sex")), GoalActivity.valueOf(it.getString("activity")), it.getBoolean("adaptive"),
                it.getJSONArray("locks").let { a -> (0 until a.length()).map { n -> SuggestedTarget.valueOf(a.getString(n)) }.toSet() },
                LocalDate.parse(it.getString("reviewedOn"))).validate() },
            baseline = BackupJson.decodeGoals(o.getJSONObject("baseline")),
            history = h.keys().asSequence().associate { LocalDate.parse(it) to BackupJson.decodeGoals(h.getJSONObject(it)) },
            previous = o.optJSONObject("previous")?.let(BackupJson::decodeGoals),
            lastAdapted = o.optString("lastAdapted").takeIf { it.isNotBlank() }?.let(LocalDate::parse),
            status = o.getString("status")
        )
    }
}

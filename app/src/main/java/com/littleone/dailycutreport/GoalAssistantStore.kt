package com.littleone.dailycutreport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import java.time.Duration

internal class GoalAssistantStore(private val dao: NutritionDao) {
    private val mutex = Mutex()
    private suspend fun current() = (dao.userGoals() ?: UserGoalsEntity()).toDomain()
    suspend fun state(): GoalAssistantState = dao.metadata(GoalAssistantState.KEY)?.let(GoalAssistantCodec::decode)
        ?: GoalAssistantState(baseline = current())

    private suspend fun commit(goals: UserGoals, state: GoalAssistantState, today: LocalDate) {
        dao.saveGoalAssistant(goals.requireValid().toEntity(), GoalAssistantCodec.encode(state.copy(
            history = state.history + (today to goals)
        )))
    }

    suspend fun manual(goals: UserGoals) = mutex.withLock {
        val before = current()
        val state = state()
        val changed = buildSet {
            if (before.calories != goals.calories) add(SuggestedTarget.CALORIES)
            if (before.proteinG != goals.proteinG) add(SuggestedTarget.PROTEIN)
            if (before.fatG != goals.fatG) add(SuggestedTarget.FAT)
            if (before.carbsG != goals.carbsG) add(SuggestedTarget.CARBS)
            if (before.fiberG != goals.fiberG) add(SuggestedTarget.FIBER)
            if (before.saturatedFatG != goals.saturatedFatG) add(SuggestedTarget.SATURATED_FAT)
        }
        commit(goals, state.copy(profile = state.profile?.copy(locks = state.profile.locks + changed)), LocalDate.now())
    }

    suspend fun preview(profile: GoalAssistantProfile): GoalSuggestion = suggestion(profile, current(), LocalDate.now())

    private suspend fun suggestion(profile: GoalAssistantProfile, current: UserGoals, today: LocalDate): GoalSuggestion {
        fun median(values: List<Double>): Double? = values.sorted().takeIf { it.isNotEmpty() }?.let {
            (it[(it.size - 1) / 2] + it[it.size / 2]) / 2
        }
        val dailyWeights = dao.allWeightEntries().filter { it.date >= today.minusDays(6).toString() && it.date <= today.toString() }
            .groupBy { it.date }.values.mapNotNull { values -> median(values.map { it.weightKg }.filter { it.isFinite() && it > 0 }) }
        val zone = ZoneId.systemDefault()
        val burns = dao.allDailyReports().filter { it.date >= today.minusDays(28).toString() && it.date < today.toString() }
            .mapNotNull { report ->
                val date = LocalDate.parse(report.date)
                val hours = Duration.between(date.atStartOfDay(zone), date.plusDays(1).atStartOfDay(zone)).toMinutes() / 60.0
                (report.totalCalories * 24 / hours).takeIf { it.isFinite() && it in 500.0..10000.0 }
            }
        return NutritionGoalEngine.suggest(profile, current, median(dailyWeights), median(burns).takeIf { burns.size >= 7 })
    }

    suspend fun apply(profile: GoalAssistantProfile) = mutex.withLock {
        val today = LocalDate.now()
        val before = current()
        val suggestion = suggestion(profile, before, today)
        commit(suggestion.goals, state().copy(profile = profile, previous = before, lastAdapted = today,
            status = "${if (profile.adaptive) "Daily adaptation" else "Applied once"} · $today. ${suggestion.explanation}"), today)
    }

    suspend fun adapt() = mutex.withLock {
        val state = state()
        val profile = state.profile ?: return@withLock
        val today = LocalDate.now()
        if (!profile.adaptive || state.lastAdapted == today) return@withLock
        try {
            val before = current()
            val suggestion = suggestion(profile, before, today)
            commit(suggestion.goals, state.copy(previous = before, lastAdapted = today,
                status = "Adapted $today. ${suggestion.explanation}"), today)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: IllegalArgumentException) {
            dao.upsertMetadata(AppMetadataEntity(GoalAssistantState.KEY,
                GoalAssistantCodec.encode(state.copy(status = "Goals unchanged: ${error.message}"))))
        }
    }

    suspend fun stop(restorePrevious: Boolean) = mutex.withLock {
        val state = state()
        val goals = if (restorePrevious) state.previous ?: current() else current()
        commit(goals, state.copy(profile = state.profile?.copy(adaptive = false), previous = null,
            status = if (restorePrevious) "Previous targets restored · adaptation off" else "Adaptation off · targets retained"), LocalDate.now())
    }
}

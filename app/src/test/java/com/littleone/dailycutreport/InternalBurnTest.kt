package com.littleone.dailycutreport

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class InternalBurnTest {
    @Test fun overlappingSessionsAndDayClippingDoNotDoubleCount() {
        val start = Instant.parse("2026-09-23T00:00:00Z"); val end = start.plusSeconds(3600)
        val session = ExerciseInterval(start.minusSeconds(600), start.plusSeconds(1800), 3.8)
        val slices = activitySlices(start, end, listOf(session, session))
        assertEquals(2, slices.size)
        assertEquals(2, slices.first().sessions)
        assertEquals(0, slices.last().sessions)
        assertEquals(1.4, slices.sumOf { (it.met?.minus(1) ?: 0.0) * Duration.between(it.start, it.end).seconds / 3600.0 }, .0001)
        assertEquals(start, slices.first().start)
        assertEquals(end, slices.last().end)
    }
    private val profile = GoalAssistantProfile(30, 180.0, 80.0, GoalEquationSex.MALE, GoalActivity.LIGHT)
    private val date = LocalDate.of(2026, 9, 23)
    private val zone = ZoneId.of("UTC")
    private fun estimate(activity: InternalActivity = InternalActivity(0.0, 0), history: List<Double> = emptyList()) =
        InternalBurnEngine.estimate(date, date.atTime(12, 0).toInstant(ZoneOffset.UTC), zone, profile, 80.0, activity, history)

    @Test fun restingEquationAndNoActivityMultiplierStacking() {
        assertEquals(1780.0, RestingEnergyEngine.calculate(profile), .001)
        assertEquals(estimate().finalKcal, InternalBurnEngine.estimate(date, date.atTime(12, 0).toInstant(ZoneOffset.UTC), zone,
            profile.copy(activity = GoalActivity.HIGH), 80.0, InternalActivity(0.0, 0)).finalKcal, .001)
        assertEquals(1958.0, estimate().finalKcal, .001)
    }
    @Test fun netExerciseAndDistanceAreSeparateFromProviderCalories() {
        val e = estimate(InternalActivity(2.0, 99999, 2.8))
        assertEquals(80.0, e.movementKcal, .001)
        assertEquals(235.2, e.exerciseKcal, .001)
        assertEquals(1958.0 + 80 + 235.2, e.finalKcal, .001)
    }
    @Test fun missingMovementIsNotTreatedAsConfirmedZero() {
        assertTrue(estimate(InternalActivity()).explanation.contains("Movement data missing"))
        assertFalse(estimate().explanation.contains("Movement data missing"))
        assertEquals(80.0, estimate(InternalActivity(outsideSteps = 2600)).movementKcal, .001)
    }
    @Test fun historyOnlyForecastsRemainingHoursAndInvalidRatesAreIgnored() {
        assertEquals(estimate().finalKcal + 120, estimate(history = listOf(10.0, 10.0, Double.NaN, -2.0)).finalKcal, .001)
        assertEquals(2, estimate(history = listOf(10.0, 10.0, Double.NaN)).sampleDays)
    }
    @Test fun actualLocalDayLengthIsUsedAcrossDst() {
        val z = ZoneId.of("America/New_York")
        for ((day, hours) in listOf(LocalDate.of(2026, 3, 8) to 23, LocalDate.of(2026, 11, 1) to 25)) {
            val e = InternalBurnEngine.estimate(day, day.plusDays(1).atStartOfDay(z).toInstant(), z, profile, 80.0, InternalActivity(0.0, 0))
            assertEquals(1780 * hours / 24.0, e.restingDailyKcal, .001)
            assertEquals(e.finalKcal, e.burnSoFarKcal, .001)
        }
    }
    @Test fun bodyOnlyValidationDoesNotApplyWeightLossEligibility() {
        val lowWeight = profile.copy(weightKg = 50.0)
        assertTrue(RestingEnergyEngine.calculate(lowWeight) > 0)
        assertThrows(IllegalArgumentException::class.java) { lowWeight.validate() }
    }
}

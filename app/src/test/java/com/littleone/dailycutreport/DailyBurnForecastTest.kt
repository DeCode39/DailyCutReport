package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DailyBurnForecastTest {
    private val engine = DailyBurnForecastEngine()
    private val utc = ZoneId.of("UTC")

    @Test fun `live burn plus historical remainder reacts to completed activity`() {
        val today = LocalDate.of(2026, 8, 14)
        val history = (1L..14L).map { CompletedBurnDay(today.minusDays(it), 2_400.0) }
        val ordinary = engine.forecast(
            today, today, Instant.parse("2026-08-14T12:00:00Z"), utc,
            1_200.0, 2_300.0, history
        )
        val active = engine.forecast(
            today, today, Instant.parse("2026-08-14T12:00:00Z"), utc,
            1_800.0, 2_300.0, history
        )
        assertEquals(2_400.0, ordinary.estimatedFinalCalories!!, 0.01)
        assertEquals(3_000.0, active.estimatedFinalCalories!!, 0.01)
        assertEquals(BurnForecastConfidence.HIGH, active.confidence)
        assertEquals(BurnForecastSource.HISTORICAL_REMAINDER, active.source)
    }

    @Test fun `provider projection is only the no-history fallback`() {
        val today = LocalDate.of(2026, 8, 14)
        val fallback = engine.forecast(
            today, today, Instant.parse("2026-08-14T08:00:00Z"), utc,
            700.0, 2_250.0, emptyList()
        )
        assertEquals(2_250.0, fallback.estimatedFinalCalories!!, 0.01)
        assertEquals(BurnForecastSource.PROVIDER_FALLBACK, fallback.source)
        assertEquals(BurnForecastConfidence.LOW, fallback.confidence)

        val unavailable = engine.forecast(
            today, today, Instant.parse("2026-08-14T08:00:00Z"), utc,
            700.0, null, emptyList()
        )
        assertNull(unavailable.estimatedFinalCalories)
        assertEquals(BurnForecastSource.UNAVAILABLE, unavailable.source)
    }

    @Test fun `robust history rejects a single extreme burn day`() {
        val today = LocalDate.of(2026, 8, 14)
        val history = (1L..9L).map { CompletedBurnDay(today.minusDays(it), 2_400.0) } +
            CompletedBurnDay(today.minusDays(10), 24_000.0)
        val result = engine.forecast(
            today, today, Instant.parse("2026-08-14T12:00:00Z"), utc,
            1_200.0, null, history
        )
        assertEquals(2_400.0, result.estimatedFinalCalories!!, 0.01)
        assertEquals(9, result.sampleDays)
    }

    @Test fun `completed DST days are normalized by their real duration`() {
        val zone = ZoneId.of("America/New_York")
        val today = LocalDate.of(2026, 3, 10)
        val history = listOf(
            CompletedBurnDay(LocalDate.of(2026, 3, 8), 2_300.0), // 23-hour spring-forward day
            CompletedBurnDay(LocalDate.of(2026, 3, 9), 2_400.0)
        )
        val result = engine.forecast(
            today, today, today.atTime(12, 0).atZone(zone).toInstant(), zone,
            1_200.0, null, history
        )
        assertEquals(2_400.0, result.estimatedFinalCalories!!, 0.01)
    }

    @Test fun `forecast metadata round trips`() {
        val value = BurnForecast(
            LocalDate.of(2026, 8, 14), 1_100.0, 2_300.0, 2_100.0, 2_500.0,
            BurnForecastSource.HISTORICAL_REMAINDER, BurnForecastConfidence.MEDIUM, 9, 1234L
        )
        assertEquals(value, BurnForecastCodec.decode(BurnForecastCodec.encode(value)))
        assertTrue(BurnForecastCodec.decode("not-json") == null)
    }
}

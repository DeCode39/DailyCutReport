package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class HealthRecordWindowTest {
    @Test fun preservesLocalTimeWhenLogAlreadyBelongsToSelectedDate() {
        val zone = ZoneId.of("Asia/Taipei")
        val date = LocalDate.of(2026, 7, 10)
        val loggedAt = date.atTime(8, 35).atZone(zone).toInstant().toEpochMilli()
        val window = log(date, loggedAt).healthRecordWindow(zone)

        assertEquals(date, window.start.toLocalDate())
        assertEquals(LocalTime.of(8, 35), window.start.toLocalTime())
        assertEquals(window.start.offset, window.end.offset)
    }

    @Test fun historicalMismatchUsesNoonAndStaysInsideDstDate() {
        val zone = ZoneId.of("America/New_York")
        val selectedDate = LocalDate.of(2026, 3, 8)
        val unrelatedTimestamp = LocalDate.of(2026, 3, 7).atTime(23, 55).atZone(zone).toInstant().toEpochMilli()
        val window = log(selectedDate, unrelatedTimestamp).healthRecordWindow(zone)

        assertEquals(selectedDate, window.start.toLocalDate())
        assertEquals(LocalTime.NOON, window.start.toLocalTime())
        assertEquals(selectedDate, window.end.toLocalDate())
        assertEquals(60L, java.time.Duration.between(window.start, window.end).seconds)
    }

    private fun log(date: LocalDate, loggedAt: Long) = FoodLogSnapshot(
        date = date,
        productName = "Meal",
        loggedAt = loggedAt
    )
}

package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class ReportDateViewModelTest {
    @Test fun futureDatesAreClampedToToday() {
        val viewModel = ReportDateViewModel()
        viewModel.select(LocalDate.now().plusDays(20))
        assertEquals(LocalDate.now(), viewModel.selectedDate.value)
    }
}


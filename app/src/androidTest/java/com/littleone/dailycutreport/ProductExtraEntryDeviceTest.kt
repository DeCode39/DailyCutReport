package com.littleone.dailycutreport

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class ProductExtraEntryDeviceTest {
    @Test fun extrasParserInitializesOnAndroidAndKeepsGroupedNumbers() {
        assertTrue(parseProductExtras("food", "").isEmpty())
        val english = parseProductExtras("food", "Potassium=1,140 mg", Locale.US).single()
        assertEquals(1140.0, english.value, 0.0)
        assertEquals("mg", english.unit)
        val french = parseProductExtras("food", "Potassium=1\u202f140,25 mg", Locale.FRANCE).single()
        assertEquals(1140.25, french.value, 0.0)
        assertEquals("mg", french.unit)
        val grouped = parseProductExtras("food", "Potassium=1 140,25 mg", Locale.FRANCE).single()
        assertEquals(1140.25, grouped.value, 0.0)
    }
}

package com.littleone.dailycutreport

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class NumericEntryTest {
    @Test fun groupedNumbersAreNotChangedIntoDecimals() {
        assertEquals("1140", parseEntryDecimal("1,140", Locale.US)!!.toPlainString())
        assertEquals("1140.25", parseEntryDecimal("1,140.25", Locale.TAIWAN)!!.toPlainString())
        assertEquals("1140.25", parseEntryDecimal("1.140,25", Locale.GERMANY)!!.toPlainString())
        assertEquals("1140.25", parseEntryDecimal("1\u202f140,25", Locale.FRANCE)!!.toPlainString())
    }
    @Test fun malformedAndAmbiguousEntriesAreRejected() {
        for (text in listOf("1,14", "1,14,0", "1,140,", "1.2.3", "NaN", "Infinity", "12 kcal", "-", "1.")) {
            assertNull(text, parseEntryDecimal(text, Locale.US))
        }
        assertNull(parseEntryDecimal("1,140.25", Locale.GERMANY))
    }
    @Test fun editorTextIsExactUngroupedAndRoundTrips() {
        for (locale in listOf(Locale.US, Locale.GERMANY, Locale.FRANCE)) {
            for (value in listOf(1140.0, 0.123456789, 97.999999999996, -0.0)) {
                val text = value.toEntryText(locale)
                assertEquals(value, parseEntryDecimal(text, locale)!!.toDouble(), 0.0)
            }
            assertEquals("0", (-0.0).toEntryText(locale))
        }
    }
}

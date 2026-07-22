package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuantityModelsTest {
    @Test fun servingAndWeightConvertsInBothDirections() {
        val spec = ProductQuantitySpec(QuantityMode.SERVING_AND_WEIGHT, 40.0)
        assertEquals(2.0, spec.servingsFor(80.0, QuantityUnit.GRAMS)!!, 1e-9)
        assertEquals(60.0, spec.amountFor(1.5, QuantityUnit.GRAMS)!!, 1e-9)
        assertTrue(spec.supports(QuantityUnit.SERVINGS))
        assertTrue(spec.supports(QuantityUnit.GRAMS))
        assertFalse(spec.supports(QuantityUnit.MILLILITERS))
    }

    @Test fun measuredOnlyDisablesServingInput() {
        val spec = ProductQuantitySpec(QuantityMode.VOLUME_ONLY, 100.0)
        assertFalse(spec.supports(QuantityUnit.SERVINGS))
        assertTrue(spec.supports(QuantityUnit.MILLILITERS))
        assertNull(spec.servingsFor(1.0, QuantityUnit.SERVINGS))
        assertEquals(2.5, spec.servingsFor(250.0, QuantityUnit.MILLILITERS)!!, 1e-9)
    }

    @Test fun inputDraftKeepsInvalidActiveTextWithoutReplacingCounterpart() {
        val original = QuantityInputState("1", "40", QuantityUnit.SERVINGS,
            ProductQuantitySpec(QuantityMode.SERVING_AND_WEIGHT, 40.0))
        val partial = original.edit(QuantityUnit.GRAMS, ".")
        assertEquals(".", partial.measureText)
        assertEquals("1", partial.servingsText)
        assertFalse(partial.valid)
        val valid = partial.edit(QuantityUnit.GRAMS, "80")
        assertEquals("2", valid.servingsText)
        assertEquals(QuantityUnit.GRAMS, valid.activeUnit)
    }

    @Test fun legacyInferenceIsConservative() {
        val both = inferQuantitySpec("1 serving (300 ml)")
        assertEquals(QuantityMode.SERVING_AND_VOLUME, both.spec.mode)
        assertEquals(300.0, both.spec.measurePerServing!!, 0.0)
        assertFalse(both.exactMeasuredOnly)

        val measured = inferQuantitySpec("100 g")
        assertEquals(QuantityMode.WEIGHT_ONLY, measured.spec.mode)
        assertTrue(measured.exactMeasuredOnly)

        assertEquals(QuantityMode.SERVING_AND_VOLUME, inferQuantitySpec("1 bottle (300 ml)").spec.mode)
    }
}

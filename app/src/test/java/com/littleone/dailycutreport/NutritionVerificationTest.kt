package com.littleone.dailycutreport

import org.junit.Assert.*
import org.junit.Test

class NutritionVerificationTest {
    private val food = ProductEntity(productId = "food", name = "Food", calories = 100.0)
    @Test fun invalidNumbersAndMissingBasisAreHardErrors() {
        assertTrue(NutritionVerifier.review(food.copy(calories = Double.NaN)).errors.isNotEmpty())
        assertTrue(NutritionVerifier.review(food.copy(proteinG = -1.0)).errors.isNotEmpty())
        assertTrue(NutritionVerifier.review(food.copy(quantityMode = QuantityMode.WEIGHT_ONLY.name)).errors.isNotEmpty())
    }
    @Test fun improbableRelationshipsWarnWithoutChangingValues() {
        val product = food.copy(saturatedFatG = 9.0, fatG = 2.0, sugarG = 30.0, carbsG = 4.0)
        val review = NutritionVerifier.review(product)
        assertTrue(review.errors.isEmpty())
        assertTrue(review.warnings.any { it.startsWith("Saturated") })
        assertTrue(review.warnings.any { it.startsWith("Sugar") })
        assertEquals(9.0, product.saturatedFatG, 0.0)
    }
    @Test fun massCheckNeverGuessesVolumeDensity() {
        val product = food.copy(quantityMode = QuantityMode.WEIGHT_ONLY.name, measurePerServing = 10.0, proteinG = 20.0)
        assertTrue(NutritionVerifier.review(product).warnings.any { it.startsWith("Macro weight") })
        assertFalse(NutritionVerifier.review(product.copy(quantityMode = QuantityMode.VOLUME_ONLY.name)).warnings.any { it.startsWith("Macro weight") })
    }
}

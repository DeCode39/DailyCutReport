package com.littleone.dailycutreport

import kotlin.math.abs

data class NutritionVerification(val errors: List<String>, val warnings: List<String>)

/** Local label checks. Warnings are review prompts, never inferred corrections. */
object NutritionVerifier {
    fun review(product: ProductEntity, extras: List<ProductExtraNutrientEntity> = emptyList()): NutritionVerification {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val values = listOf(product.calories, product.proteinG, product.sodiumMg, product.carbsG,
            product.fatG, product.sugarG, product.fiberG, product.saturatedFatG) + extras.map { it.value }
        if (values.any { !it.isFinite() || it < 0 }) errors += "Nutrients must be finite, non-negative numbers."
        if (product.name.isBlank()) errors += "Enter a product name."
        if (!product.purchaseUnitServings.isFinite() || product.purchaseUnitServings <= 0) errors += "Enter a positive purchase quantity."
        val spec = product.quantitySpec()
        if (spec.mode.measureAvailable && !spec.measureAvailable) errors += "Enter a positive nutrition weight or volume basis."
        if (errors.isNotEmpty()) return NutritionVerification(errors, warnings)
        if (product.saturatedFatG > product.fatG + 0.1) warnings += "Saturated fat exceeds total fat. Check both values and their basis."
        if (product.sugarG > product.carbsG + 0.1) warnings += "Sugar exceeds carbohydrates. Check the label and units."
        val macroEnergy = 4 * product.proteinG + 4 * product.carbsG + 9 * product.fatG
        if (product.calories > 0 && macroEnergy > 0 && abs(product.calories - macroEnergy) > maxOf(50.0, product.calories * 0.3)) {
            warnings += "Calories differ substantially from the macros. Check kcal/kJ and serving basis; fiber, alcohol and sugar substitutes can explain differences."
        }
        if (spec.measureUnit == QuantityUnit.GRAMS && product.proteinG + product.carbsG + product.fatG > requireNotNull(spec.measurePerServing) * 1.1) {
            warnings += "Macro weight exceeds the gram-based nutrition portion. Check per-serving versus per-100-g values."
        }
        if (product.sodiumMg > 5000) warnings += "Sodium is unusually high for one nutrition unit. Check mg versus g and serving size."
        return NutritionVerification(errors, warnings)
    }
}

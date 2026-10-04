package com.littleone.dailycutreport

import java.util.Locale

private val extraAmountAndUnit = Regex("(?U)^(.+?)(?:\\s+([^\\d\\s].*))?$")

internal fun parseProductExtras(productId: String, text: String, locale: Locale = Locale.getDefault()): List<ProductExtraNutrientEntity> =
    text.lineSequence().filter(String::isNotBlank).map { line ->
        val parts = line.split('=', limit = 2)
        require(parts.size == 2) { "Extra nutrients must use Name=value unit, one per line." }
        val name = parts[0].trim()
        require(name.isNotBlank()) { "Extra nutrient names cannot be blank." }
        val match = extraAmountAndUnit.matchEntire(parts[1].trim()) ?: error("Enter a value for $name.")
        val value = parseEntryDecimal(match.groupValues[1], locale)?.toDouble()?.takeIf(Double::isFinite)
            ?: error("Enter a valid number for $name; check decimal and grouping separators.")
        require(value >= 0) { "Extra nutrients cannot be negative." }
        ProductExtraNutrientEntity(productId, name, value, match.groupValues[2].trim())
    }.toList()

package com.littleone.dailycutreport

import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

fun formatDecimal(value: Double, maximumFractionDigits: Int = 2): String {
    if (!value.isFinite()) return "Unavailable"
    val normalized = if (abs(value) < 0.5 * Math.pow(10.0, -maximumFractionDigits.toDouble())) 0.0 else value
    return NumberFormat.getNumberInstance(Locale.getDefault()).apply {
        minimumFractionDigits = 0
        this.maximumFractionDigits = maximumFractionDigits
        roundingMode = RoundingMode.HALF_UP
        isGroupingUsed = true
    }.format(normalized)
}

fun formatInteger(value: Long): String = NumberFormat.getIntegerInstance(Locale.getDefault()).format(value)

fun formatCalories(value: Double): String {
    if (!value.isFinite()) return "Unavailable"
    return formatInteger(kotlin.math.round(value).toLong()).replace("-0", "0")
}

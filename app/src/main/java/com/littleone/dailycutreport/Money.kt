package com.littleone.dailycutreport

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

const val MONEY_MICROS_PER_UNIT = 1_000_000L

fun parseMoneyMicros(value: String): Long? {
    if (value.isBlank()) return null
    val decimal = parseEntryDecimal(value) ?: throw IllegalArgumentException("Enter a valid price using your local decimal separator.")
    require(decimal.signum() >= 0) { "Price cannot be negative." }
    return decimal.multiply(BigDecimal(MONEY_MICROS_PER_UNIT))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}

fun Long.toMoneyInput(): String = BigDecimal(this)
    .divide(BigDecimal(MONEY_MICROS_PER_UNIT))
    .stripTrailingZeros()
    .toPlainString().replace('.', java.text.DecimalFormatSymbols.getInstance().decimalSeparator)

fun formatMoney(micros: Long, currencyCode: String): String {
    val normalizedCode = currencyCode.trim().uppercase()
    val currency = runCatching { Currency.getInstance(normalizedCode) }.getOrElse { Currency.getInstance("TWD") }
    val digits = currency.defaultFractionDigits.coerceAtLeast(0)
    val amount = BigDecimal(micros).divide(BigDecimal(MONEY_MICROS_PER_UNIT))
        .setScale(digits, RoundingMode.HALF_UP)
    return "${currency.currencyCode} ${amount.toPlainString()}"
}

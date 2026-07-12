package com.littleone.dailycutreport

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

const val MONEY_MICROS_PER_UNIT = 1_000_000L

fun parseMoneyMicros(value: String): Long? {
    val normalized = value.trim().replace(',', '.')
    if (normalized.isEmpty()) return null
    val decimal = normalized.toBigDecimalOrNull() ?: throw IllegalArgumentException("Enter a valid price.")
    require(decimal.signum() >= 0) { "Price cannot be negative." }
    return decimal.multiply(BigDecimal(MONEY_MICROS_PER_UNIT))
        .setScale(0, RoundingMode.HALF_UP)
        .longValueExact()
}

fun Long.toMoneyInput(): String = BigDecimal(this)
    .divide(BigDecimal(MONEY_MICROS_PER_UNIT))
    .stripTrailingZeros()
    .toPlainString()

fun formatMoney(micros: Long, currencyCode: String): String {
    val currency = Currency.getInstance(currencyCode)
    val digits = currency.defaultFractionDigits.coerceAtLeast(0)
    val amount = BigDecimal(micros).divide(BigDecimal(MONEY_MICROS_PER_UNIT))
        .setScale(digits, RoundingMode.HALF_UP)
    return "$currencyCode ${amount.toPlainString()}"
}

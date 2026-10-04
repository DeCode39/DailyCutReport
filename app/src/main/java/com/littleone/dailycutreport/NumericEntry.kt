package com.littleone.dailycutreport

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.text.Normalizer
import java.util.Locale

/** Parse the whole entry. Grouping is accepted only in complete groups of three. */
fun parseEntryDecimal(text: String, locale: Locale = Locale.getDefault()): BigDecimal? {
    val raw = Normalizer.normalize(text.trim(), Normalizer.Form.NFKC)
        .replace('\u2212', '-').replace('\u00a0', ' ').replace('\u202f', ' ')
    if (raw.isEmpty()) return null
    val symbols = DecimalFormatSymbols.getInstance(locale)
    val decimal = symbols.decimalSeparator
    val grouping = if (symbols.groupingSeparator.isWhitespace() || symbols.groupingSeparator == '\u00a0' || symbols.groupingSeparator == '\u202f') ' ' else symbols.groupingSeparator
    val parts = raw.split(decimal)
    if (parts.size > 2 || (parts.size == 2 && (parts[1].isEmpty() || !parts[1].all(Char::isDigit)))) return null
    val signed = parts[0]
    val sign = signed.take(1).takeIf { it == "-" || it == "+" }.orEmpty()
    val integer = signed.removePrefix(sign)
    val groups = integer.split(grouping)
    val validInteger = if (groups.size == 1) integer.all(Char::isDigit) &&
        (integer.isNotEmpty() || parts.size == 2)
    else groups.first().length in 1..3 && groups.first().all(Char::isDigit) &&
        groups.drop(1).all { it.length == 3 && it.all(Char::isDigit) }
    if (!validInteger) return null
    val normalized = sign + groups.joinToString("").ifEmpty { "0" } +
        if (parts.size == 2) ".${parts[1]}" else ""
    return normalized.toBigDecimalOrNull()
}

fun parseEntryNumber(text: String): Double? = parseEntryDecimal(text)?.toDouble()?.takeIf(Double::isFinite)

/** Exact, ungrouped text for an editor; display rounding must never alter saved values. */
fun Double.toEntryText(locale: Locale = Locale.getDefault()): String =
    if (isFinite()) BigDecimal.valueOf(if (this == 0.0) 0.0 else this).stripTrailingZeros().toPlainString()
        .replace('.', DecimalFormatSymbols.getInstance(locale).decimalSeparator) else ""

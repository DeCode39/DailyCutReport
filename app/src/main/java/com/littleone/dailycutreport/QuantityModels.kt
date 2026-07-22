package com.littleone.dailycutreport

import kotlin.math.abs

enum class QuantityMode {
    SERVING_ONLY,
    WEIGHT_ONLY,
    VOLUME_ONLY,
    SERVING_AND_WEIGHT,
    SERVING_AND_VOLUME;

    val servingAvailable: Boolean
        get() = this == SERVING_ONLY || this == SERVING_AND_WEIGHT || this == SERVING_AND_VOLUME

    val measureUnit: QuantityUnit?
        get() = when (this) {
            WEIGHT_ONLY, SERVING_AND_WEIGHT -> QuantityUnit.GRAMS
            VOLUME_ONLY, SERVING_AND_VOLUME -> QuantityUnit.MILLILITERS
            SERVING_ONLY -> null
        }

    val measureAvailable: Boolean get() = measureUnit != null
}

val QuantityMode.editorLabel: String
    get() = when (this) {
        QuantityMode.SERVING_ONLY -> "Servings only"
        QuantityMode.WEIGHT_ONLY -> "Weight only"
        QuantityMode.VOLUME_ONLY -> "Volume only"
        QuantityMode.SERVING_AND_WEIGHT -> "Servings + weight"
        QuantityMode.SERVING_AND_VOLUME -> "Servings + volume"
    }

enum class QuantityUnit(val shortLabel: String) {
    SERVINGS("servings"),
    GRAMS("g"),
    MILLILITERS("ml")
}

data class ProductQuantitySpec(
    val mode: QuantityMode = QuantityMode.SERVING_ONLY,
    val measurePerServing: Double? = null
) {
    val measureUnit: QuantityUnit? get() = mode.measureUnit
    val servingAvailable: Boolean get() = mode.servingAvailable
    val measureAvailable: Boolean get() = mode.measureAvailable && validMeasure != null
    private val validMeasure: Double?
        get() = measurePerServing?.takeIf { it.isFinite() && it > 0.0 }

    fun supports(unit: QuantityUnit): Boolean = when (unit) {
        QuantityUnit.SERVINGS -> servingAvailable
        QuantityUnit.GRAMS, QuantityUnit.MILLILITERS -> measureAvailable && measureUnit == unit
    }

    fun servingsFor(amount: Double, unit: QuantityUnit): Double? = when {
        !amount.isFinite() || amount <= 0.0 || !supports(unit) -> null
        unit == QuantityUnit.SERVINGS -> amount
        else -> validMeasure?.let { amount / it }
    }

    fun amountFor(servings: Double, unit: QuantityUnit): Double? = when {
        !servings.isFinite() || servings <= 0.0 || !supports(unit) -> null
        unit == QuantityUnit.SERVINGS -> servings
        else -> validMeasure?.let { servings * it }
    }

    fun preferredOrFallback(preferred: QuantityUnit): QuantityUnit = when {
        supports(preferred) -> preferred
        servingAvailable -> QuantityUnit.SERVINGS
        measureAvailable -> requireNotNull(measureUnit)
        else -> QuantityUnit.SERVINGS
    }
}

data class LoggedQuantity(
    val servings: Double,
    val enteredUnit: QuantityUnit,
    val enteredAmount: Double,
    val spec: ProductQuantitySpec
) {
    init {
        require(servings.isFinite() && servings > 0.0)
        require(enteredAmount.isFinite() && enteredAmount > 0.0)
    }

    fun displayParts(): List<String> {
        val primary = "${enteredAmount.toDisplay()} ${enteredUnit.shortLabel}"
        val secondaryUnit = when (enteredUnit) {
            QuantityUnit.SERVINGS -> spec.measureUnit
            else -> QuantityUnit.SERVINGS.takeIf { spec.servingAvailable }
        }
        val secondary = secondaryUnit?.let { unit ->
            spec.amountFor(servings, unit)?.let { "${it.toDisplay()} ${unit.shortLabel}" }
        }
        return listOfNotNull(primary, secondary?.takeUnless { it == primary })
    }
}

data class QuantityInputState(
    val servingsText: String,
    val measureText: String,
    val activeUnit: QuantityUnit,
    val spec: ProductQuantitySpec
) {
    val servings: Double?
        get() = servingsText.quantityNumber()?.takeIf { it > 0.0 }
    val enteredAmount: Double?
        get() = when (activeUnit) {
            QuantityUnit.SERVINGS -> servings
            else -> measureText.quantityNumber()?.takeIf { it > 0.0 }
        }
    val valid: Boolean get() = servings != null && enteredAmount != null && spec.supports(activeUnit)

    fun edit(unit: QuantityUnit, value: String): QuantityInputState {
        if (!spec.supports(unit)) return this
        val parsed = value.quantityNumber()?.takeIf { it > 0.0 }
        return when (unit) {
            QuantityUnit.SERVINGS -> copy(
                servingsText = value,
                measureText = parsed?.let { amount -> spec.measureUnit?.let { spec.amountFor(amount, it)?.toDisplay() } }
                    ?: measureText,
                activeUnit = unit
            )
            else -> copy(
                measureText = value,
                servingsText = parsed?.let { spec.servingsFor(it, unit)?.toDisplay() } ?: servingsText,
                activeUnit = unit
            )
        }
    }

    fun withServings(servings: Double, active: QuantityUnit = activeUnit): QuantityInputState {
        val safeActive = spec.preferredOrFallback(active)
        return copy(
            servingsText = servings.toDisplay(),
            measureText = spec.measureUnit?.let { spec.amountFor(servings, it)?.toDisplay() }.orEmpty(),
            activeUnit = safeActive
        )
    }

    companion object {
        fun forProduct(product: ProductEntity, servings: Double = product.purchaseUnitServings): QuantityInputState {
            val spec = product.quantitySpec()
            return QuantityInputState(
                servingsText = servings.toDisplay(),
                measureText = spec.measureUnit?.let { spec.amountFor(servings, it)?.toDisplay() }.orEmpty(),
                activeUnit = spec.preferredOrFallback(product.preferredQuantityUnit()),
                spec = spec
            )
        }

        fun forLog(log: FoodLogSnapshot): QuantityInputState {
            val spec = log.quantitySpec()
            val unit = QuantityUnit.entries.firstOrNull { it.name == log.enteredUnit } ?: QuantityUnit.SERVINGS
            val measureText = when {
                unit != QuantityUnit.SERVINGS && spec.supports(unit) -> log.enteredAmount.toDisplay()
                else -> spec.measureUnit?.let { spec.amountFor(log.quantity, it)?.toDisplay() }.orEmpty()
            }
            return QuantityInputState(
                servingsText = log.quantity.toDisplay(),
                measureText = measureText,
                activeUnit = spec.preferredOrFallback(unit),
                spec = spec
            )
        }
    }
}

private fun String.quantityNumber(): Double? = trim().replace(',', '.').toDoubleOrNull()?.takeIf(Double::isFinite)

internal data class InferredQuantitySpec(
    val spec: ProductQuantitySpec,
    val exactMeasuredOnly: Boolean
)

/** Conservative legacy parsing. Free-form package labels deliberately remain serving-only. */
internal fun inferQuantitySpec(servingLabel: String): InferredQuantitySpec {
    val normalized = servingLabel.trim().lowercase().replace("millilitres", "ml").replace("milliliters", "ml")
    val convertible = Regex("^1\\s*(?:serving|bottle|can|package)\\s*\\(\\s*(\\d+(?:[.,]\\d+)?)\\s*(g|ml)\\s*\\)$")
        .matchEntire(normalized)
    if (convertible != null) {
        val amount = convertible.groupValues[1].replace(',', '.').toDoubleOrNull()
        val unit = convertible.groupValues[2]
        if (amount != null && amount.isFinite() && amount > 0.0) {
            val mode = if (unit == "g") QuantityMode.SERVING_AND_WEIGHT else QuantityMode.SERVING_AND_VOLUME
            return InferredQuantitySpec(ProductQuantitySpec(mode, amount), false)
        }
    }
    val measured = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(g|ml)$").matchEntire(normalized)
    if (measured != null) {
        val amount = measured.groupValues[1].replace(',', '.').toDoubleOrNull()
        val unit = measured.groupValues[2]
        if (amount != null && amount.isFinite() && amount > 0.0) {
            val mode = if (unit == "g") QuantityMode.WEIGHT_ONLY else QuantityMode.VOLUME_ONLY
            return InferredQuantitySpec(ProductQuantitySpec(mode, amount), true)
        }
    }
    return InferredQuantitySpec(ProductQuantitySpec(), false)
}

internal fun quantitiesEquivalent(first: Double, second: Double): Boolean =
    abs(first - second) <= maxOf(abs(first), abs(second), 1.0) * 1e-9

fun ProductEntity.quantitySpec(): ProductQuantitySpec = ProductQuantitySpec(
    mode = QuantityMode.entries.firstOrNull { it.name == quantityMode } ?: QuantityMode.SERVING_ONLY,
    measurePerServing = measurePerServing
)

fun ProductEntity.preferredQuantityUnit(): QuantityUnit = quantitySpec().preferredOrFallback(
    QuantityUnit.entries.firstOrNull { it.name == preferredLogUnit } ?: QuantityUnit.SERVINGS
)

fun FoodLogSnapshot.quantitySpec(): ProductQuantitySpec = ProductQuantitySpec(
    mode = QuantityMode.entries.firstOrNull { it.name == quantityMode } ?: QuantityMode.SERVING_ONLY,
    measurePerServing = measurePerServing
)

fun FoodLogSnapshot.loggedQuantity(): LoggedQuantity = LoggedQuantity(
    servings = quantity,
    enteredUnit = QuantityUnit.entries.firstOrNull { it.name == enteredUnit } ?: QuantityUnit.SERVINGS,
    enteredAmount = enteredAmount.takeIf { it.isFinite() && it > 0.0 } ?: quantity,
    spec = quantitySpec()
)

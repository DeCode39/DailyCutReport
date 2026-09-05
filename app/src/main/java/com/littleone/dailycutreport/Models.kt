package com.littleone.dailycutreport

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class HealthSummary(
    val steps: Long = 0L,
    val distanceKm: Double = 0.0,
    val activeCalories: Double = 0.0,
    val totalCalories: Double = 0.0,
    val exerciseSessions: Int = 0,
    val exerciseMinutes: Long = 0L,
    val nutritionCalories: Double = 0.0,
    val nutritionProteinG: Double = 0.0,
    val nutritionSodiumMg: Double = 0.0,
    val nutritionRecords: Int = 0,
    val healthConnectStatus: String = "Not loaded",
    /** Transient values used while refreshing; they are not persisted in daily_reports. */
    val providerFullDayCalories: Double? = null,
    val recordedThroughEpochMs: Long? = null
)

data class NutritionSummary(
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val sugarG: Double = 0.0,
    val fiberG: Double = 0.0,
    val saturatedFatG: Double = 0.0,
    val entries: Int = 0,
    val extras: Map<String, NutrientAmount> = emptyMap()
)

data class NutrientAmount(val value: Double, val unit: String)

data class DailyNutritionTargets(
    val calories: Double = 1850.0,
    val proteinG: Double = 120.0,
    val sodiumMg: Double = 2000.0,
    val carbsG: Double = 150.0,
    val fatG: Double = 60.0,
    val sugarG: Double = 50.0,
    val fiberG: Double = 15.0,
    val saturatedFatG: Double = 15.0
)

fun targetProgress(value: Double, target: Double): Float =
    if (!value.isFinite() || !target.isFinite() || target <= 0.0) 0f
    else (value.coerceAtLeast(0.0) / target).toFloat().coerceIn(0f, 1f)

enum class GoalMode { CALORIE, DEFICIT }

enum class PlannerItemType { FOOD, DRINK }

data class UserGoals(
    val mode: GoalMode = GoalMode.CALORIE,
    val calories: Double = 1850.0,
    val expectedBurnCalories: Double = 2300.0,
    val desiredDeficitCalories: Double = 450.0,
    val proteinG: Double = 120.0,
    val sodiumMg: Double = 2000.0,
    val carbsG: Double = 150.0,
    val fatG: Double = 60.0,
    val sugarG: Double = 50.0,
    val fiberG: Double = 15.0,
    val saturatedFatG: Double = 15.0,
    val currencyCode: String = "TWD",
    val dailyBudgetMicros: Long = 0L
) {
    fun calorieAllowance(projectedBurnCalories: Double?): Double? = when (mode) {
        GoalMode.CALORIE -> calories
        GoalMode.DEFICIT -> projectedBurnCalories
            ?.takeIf { it.isFinite() && it > desiredDeficitCalories }
            ?.minus(desiredDeficitCalories)
    }

    fun targetsFor(projectedBurnCalories: Double?): DailyNutritionTargets = DailyNutritionTargets(
        calorieAllowance(projectedBurnCalories) ?: 0.0,
        proteinG, sodiumMg, carbsG, fatG, sugarG, fiberG, saturatedFatG
    )

    fun forPlanning(projectedBurnCalories: Double?): UserGoals? = calorieAllowance(projectedBurnCalories)?.let { allowance ->
        copy(mode = GoalMode.CALORIE, calories = allowance)
    }

    fun requireValid(): UserGoals = apply {
        require(listOf(calories, expectedBurnCalories, desiredDeficitCalories, proteinG, sodiumMg, carbsG, fatG, sugarG, fiberG, saturatedFatG).all { it.isFinite() }) {
            "Goals must be finite numbers."
        }
        require(calories > 0.0) { "Calorie target must be greater than zero." }
        require(expectedBurnCalories > 0.0) { "Expected burn must be greater than zero." }
        require(desiredDeficitCalories >= 0.0) { "Desired deficit cannot be negative." }
        require(listOf(proteinG, sodiumMg, carbsG, fatG, sugarG, fiberG, saturatedFatG).all { it >= 0.0 }) {
            "Nutrient goals cannot be negative."
        }
        require(runCatching { java.util.Currency.getInstance(currencyCode) }.isSuccess) { "Choose a valid currency code." }
        require(dailyBudgetMicros >= 0L) { "Daily budget cannot be negative." }
    }

    fun sanitized(): UserGoals {
        fun finiteOr(value: Double, fallback: Double, positive: Boolean = false): Double =
            value.takeIf { it.isFinite() && (!positive || it > 0.0) } ?: fallback
        val burn = finiteOr(expectedBurnCalories, 2300.0, positive = true)
        val deficit = finiteOr(desiredDeficitCalories, 450.0).coerceAtLeast(0.0)
        val currency = currencyCode.trim().uppercase().takeIf {
            runCatching { java.util.Currency.getInstance(it) }.isSuccess
        } ?: "TWD"
        return copy(
            calories = finiteOr(calories, 1850.0, positive = true),
            expectedBurnCalories = burn,
            desiredDeficitCalories = deficit,
            proteinG = finiteOr(proteinG, 120.0).coerceAtLeast(0.0),
            sodiumMg = finiteOr(sodiumMg, 2000.0).coerceAtLeast(0.0),
            carbsG = finiteOr(carbsG, 150.0).coerceAtLeast(0.0),
            fatG = finiteOr(fatG, 60.0).coerceAtLeast(0.0),
            sugarG = finiteOr(sugarG, 50.0).coerceAtLeast(0.0),
            fiberG = finiteOr(fiberG, 15.0).coerceAtLeast(0.0),
            saturatedFatG = finiteOr(saturatedFatG, 15.0).coerceAtLeast(0.0),
            currencyCode = currency,
            dailyBudgetMicros = dailyBudgetMicros.coerceAtLeast(0L)
        )
    }
}

data class ProductPricing(
    val purchasePriceMicros: Long?,
    val purchaseUnitServings: Double = 1.0
) {
    val costPerServingMicros: Long?
        get() = purchasePriceMicros?.let { (it / purchaseUnitServings).toLong() }
}

data class LoggedCost(
    val catalogCostPerServingMicros: Long? = null,
    val actualPaidTotalMicros: Long? = null,
    val excludedFromBudget: Boolean = false
) {
    fun recordedTotalMicros(quantity: Double): Long? = actualPaidTotalMicros
        ?: catalogCostPerServingMicros?.let { (it * quantity).toLong() }

    fun effectiveTotalMicros(quantity: Double): Long? = if (excludedFromBudget) 0L else recordedTotalMicros(quantity)
}

data class DailySpending(
    val knownTotalMicros: Long = 0L,
    val unknownEntries: Int = 0,
    val budgetMicros: Long = 0L,
    val catalogEstimatedMicros: Long = 0L,
    val actualPaidMicros: Long = 0L,
    val actualPaidEntries: Int = 0
) {
    val remainingMicros: Long get() = budgetMicros - knownTotalMicros
    val isComplete: Boolean get() = unknownEntries == 0
}

data class HealthWriteSummary(val recordsWritten: Int, val date: LocalDate)

data class TodayWidgetState(
    val balance: String,
    val intakeProgress: String,
    val intakePercent: Int,
    val proteinProgress: String,
    val proteinPercent: Int,
    val spendingProgress: String,
    val spendingPercent: Int
)

data class ManualOverrides(
    val foodCalories: Double? = null,
    val proteinG: Double? = null,
    val sodiumMg: Double? = null,
    val burnCalories: Double? = null,
    val notes: String = ""
)

enum class DayVerdict(val label: String) {
    UNAVAILABLE("Burn data unavailable"),
    CUT("Cut day"),
    SURPLUS("Surplus day"),
    MAINTENANCE("Maintenance-ish")
}

sealed interface EnergyBalance {
    data object Unavailable : EnergyBalance
    data class Cut(val calories: Double) : EnergyBalance
    data class Surplus(val calories: Double) : EnergyBalance
    data class Maintenance(val calories: Double) : EnergyBalance
}

fun calculateEnergyBalance(burnCalories: Double, foodCalories: Double): EnergyBalance {
    if (burnCalories <= 0.0) return EnergyBalance.Unavailable
    val deficit = burnCalories - foodCalories
    return when {
        deficit >= 300.0 -> EnergyBalance.Cut(deficit)
        deficit <= -200.0 -> EnergyBalance.Surplus(-deficit)
        else -> EnergyBalance.Maintenance(deficit)
    }
}

data class DailyReport(
    val date: LocalDate,
    val health: HealthSummary = HealthSummary(),
    val nutrition: NutritionSummary = NutritionSummary(),
    val manual: ManualOverrides = ManualOverrides(),
    val savedAtEpochMs: Long = System.currentTimeMillis()
) {
    val projectedBurnCalories: Double?
        get() = health.totalCalories.takeIf { it.isFinite() && it > 0.0 }

    val finalBurnCalories: Double
        get() = manual.burnCalories ?: health.totalCalories.takeIf { it > 0.0 }
            ?: health.activeCalories.takeIf { it > 0.0 }
            ?: 0.0

    val finalFoodCalories: Double
        get() = manual.foodCalories
            ?: nutrition.calories.takeIf { nutrition.entries > 0 }
            ?: health.nutritionCalories.takeIf { health.nutritionRecords > 0 }
            ?: 0.0

    val finalProteinG: Double
        get() = manual.proteinG
            ?: nutrition.proteinG.takeIf { nutrition.entries > 0 }
            ?: health.nutritionProteinG.takeIf { health.nutritionRecords > 0 }
            ?: 0.0

    val finalSodiumMg: Double
        get() = manual.sodiumMg
            ?: nutrition.sodiumMg.takeIf { nutrition.entries > 0 }
            ?: health.nutritionSodiumMg.takeIf { health.nutritionRecords > 0 }
            ?: 0.0

    val deficitCalories: Double get() = finalBurnCalories - finalFoodCalories

    val energyBalance: EnergyBalance get() = calculateEnergyBalance(finalBurnCalories, finalFoodCalories)

    val verdict: DayVerdict
        get() = when {
            energyBalance is EnergyBalance.Unavailable -> DayVerdict.UNAVAILABLE
            energyBalance is EnergyBalance.Cut -> DayVerdict.CUT
            energyBalance is EnergyBalance.Surplus -> DayVerdict.SURPLUS
            else -> DayVerdict.MAINTENANCE
        }

    val burnSource: String
        get() = when {
            manual.burnCalories != null -> "Manual override"
            health.totalCalories > 0.0 -> "Health Connect total calories"
            health.activeCalories > 0.0 -> "Health Connect active calories"
            else -> "Missing"
        }

    val nutritionSource: String
        get() = when {
            manual.foodCalories != null || manual.proteinG != null || manual.sodiumMg != null -> "Manual override"
            nutrition.entries > 0 -> "Offline food log"
            health.nutritionRecords > 0 -> "Health Connect nutrition"
            else -> "Missing"
        }
}

data class FoodLogSnapshot(
    val id: Long = 0,
    val date: LocalDate,
    val productId: String? = null,
    val barcode: String? = null,
    val productName: String,
    val brand: String = "",
    val servingLabel: String = "1 serving",
    val quantity: Double = 1.0,
    val quantityMode: String = QuantityMode.SERVING_ONLY.name,
    val measurePerServing: Double? = null,
    val enteredUnit: String = QuantityUnit.SERVINGS.name,
    val enteredAmount: Double = quantity,
    val caloriesPerServing: Double = 0.0,
    val proteinGPerServing: Double = 0.0,
    val sodiumMgPerServing: Double = 0.0,
    val carbsGPerServing: Double = 0.0,
    val fatGPerServing: Double = 0.0,
    val sugarGPerServing: Double = 0.0,
    val fiberGPerServing: Double = 0.0,
    val saturatedFatGPerServing: Double = 0.0,
    val catalogCostPerServingMicros: Long? = null,
    val catalogEstimatedTotalMicros: Long? = null,
    val actualPaidTotalMicros: Long? = null,
    val excludeCostFromBudget: Boolean = false,
    val mealId: String? = null,
    val mealName: String? = null,
    val loggedAt: Long = System.currentTimeMillis()
) {
    val calories: Double get() = caloriesPerServing * quantity
    val proteinG: Double get() = proteinGPerServing * quantity
    val sodiumMg: Double get() = sodiumMgPerServing * quantity
    val carbsG: Double get() = carbsGPerServing * quantity
    val fatG: Double get() = fatGPerServing * quantity
    val sugarG: Double get() = sugarGPerServing * quantity
    val fiberG: Double get() = fiberGPerServing * quantity
    val saturatedFatG: Double get() = saturatedFatGPerServing * quantity
    val effectiveCostMicros: Long? get() = when {
        excludeCostFromBudget -> 0L
        actualPaidTotalMicros != null -> actualPaidTotalMicros
        catalogEstimatedTotalMicros != null -> catalogEstimatedTotalMicros
        else -> catalogCostPerServingMicros?.let { (it.toDouble() * quantity).toLong() }
    }
    val recordedCostMicros: Long? get() = actualPaidTotalMicros ?: catalogEstimatedTotalMicros
        ?: catalogCostPerServingMicros?.let { (it.toDouble() * quantity).toLong() }
}

data class ProductWithExtras(
    val product: ProductEntity,
    val extras: List<ProductExtraNutrientEntity> = emptyList()
)

data class BulkLogEntryInput(
    val product: ProductWithExtras,
    val quantity: Double
)

data class BulkLogSelection(
    val productId: String,
    val quantity: Double,
    val enteredUnit: String = QuantityUnit.SERVINGS.name,
    val enteredAmount: Double = quantity,
    val actualPaidTotalMicros: Long? = null,
    val excludeCostFromBudget: Boolean = false
)

data class MultiScanItem(
    val product: ProductEntity,
    val quantityInput: QuantityInputState = QuantityInputState.forProduct(product),
    val actualPaidText: String = "",
    val excludeCostFromBudget: Boolean = false
) {
    val quantity: Double? get() = quantityInput.servings
    val actualPaidTotalMicros: Long?
        get() = if (actualPaidText.isBlank()) null else runCatching { parseMoneyMicros(actualPaidText) }.getOrNull()
    val actualPaidValid: Boolean
        get() = actualPaidText.isBlank() || actualPaidTotalMicros != null
}

data class ScannerSessionState(
    val multiEnabled: Boolean = false,
    val target: ScanTarget = ScanTarget.STANDALONE,
    val destinationDate: LocalDate? = null,
    val items: List<MultiScanItem> = emptyList(),
    val status: String = "Point the camera at a barcode"
)

enum class ProductSaveTarget { STANDALONE_LOG, BULK_CART, MULTI_SCAN_QUEUE, CATALOG_ONLY }
enum class ScanTarget { STANDALONE, BULK_CART, PRODUCT_DRAFT_BARCODE }

data class ScanLaunchContext(
    val target: ScanTarget,
    val destinationDate: LocalDate,
    val externalLaunch: Boolean = false
)

data class PlannerProductSettings(
    val productId: String,
    val includeInPlanner: Boolean,
    val itemType: PlannerItemType,
    val fixedInPlanner: Boolean,
    val fixedPurchaseUnits: Int
)

data class BulkDraftItem(
    val product: ProductEntity,
    val quantityInput: QuantityInputState = QuantityInputState.forProduct(product)
) {
    val quantity: Double? get() = quantityInput.servings
}

data class BulkDraft(
    val date: LocalDate? = null,
    val items: List<BulkDraftItem> = emptyList(),
    val label: String = "",
    val actualPaidText: String = "",
    val excludeCostFromBudget: Boolean = false
) {
    val isValid: Boolean get() = items.isNotEmpty() && items.all { it.quantity != null } &&
        (actualPaidText.isBlank() || runCatching { parseMoneyMicros(actualPaidText) != null }.getOrDefault(false))
}

data class PendingCartAddition(
    val requestedDate: LocalDate,
    val items: List<BulkDraftItem>
)

data class CartDateConflict(
    val existingDate: LocalDate,
    val requestedDate: LocalDate,
    val pendingItemCount: Int
)

enum class CartDateResolution { KEEP_EXISTING, START_REQUESTED }

fun resolveCartAddition(
    current: BulkDraft,
    pending: PendingCartAddition,
    resolution: CartDateResolution
): BulkDraft {
    val base = when (resolution) {
        CartDateResolution.KEEP_EXISTING -> current.copy(date = current.date ?: pending.requestedDate)
        CartDateResolution.START_REQUESTED -> BulkDraft(date = pending.requestedDate)
    }
    var result = base
    pending.items.forEach { addition ->
        val existing = result.items.firstOrNull { it.product.productId == addition.product.productId }
        result = if (existing == null) result.copy(items = result.items + addition)
        else result.copy(items = result.items.map { item ->
            if (item.product.productId == addition.product.productId) item.copy(
                product = addition.product,
                quantityInput = item.quantityInput.withServings(
                    (item.quantity ?: 0.0) + (addition.quantity ?: 0.0)
                )
            ) else item
        })
    }
    return result
}

/**
 * Allocates one checkout total across ordinary food-log rows while preserving the exact total.
 * Catalog estimates are used as weights when every item has one; otherwise quantities are used.
 * The allocation is internal bookkeeping—the user only enters the checkout total once.
 */
fun allocateBulkPaidTotal(totalMicros: Long?, entries: List<BulkLogEntryInput>): List<Long?> {
    require(totalMicros == null || totalMicros >= 0L)
    if (entries.isEmpty()) return emptyList()
    if (totalMicros == null) return List(entries.size) { null }
    if (totalMicros == 0L) return List(entries.size) { 0L }

    val catalogWeights = entries.map { entry ->
        val product = entry.product.product
        product.purchasePriceMicros?.takeIf { it >= 0L }
            ?.takeIf { product.purchaseUnitServings > 0.0 }
            ?.let { it.toDouble() * entry.quantity / product.purchaseUnitServings }
            ?.takeIf { it.isFinite() && it >= 0.0 }
    }
    val weights = if (catalogWeights.all { it != null } && catalogWeights.sumOf { it ?: 0.0 } > 0.0) {
        catalogWeights.map { requireNotNull(it) }
    } else {
        entries.map { it.quantity.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0 }
    }
    val effectiveWeights = if (weights.any { it > 0.0 }) weights else List(entries.size) { 1.0 }
    val decimalWeights = effectiveWeights.map(BigDecimal::valueOf)
    val totalWeight = decimalWeights.fold(BigDecimal.ZERO, BigDecimal::add)
    val total = BigDecimal.valueOf(totalMicros)
    val raw = decimalWeights.map { total.multiply(it).divide(totalWeight, 24, RoundingMode.DOWN) }
    val allocated = raw.map { it.setScale(0, RoundingMode.DOWN).longValueExact() }.toMutableList()
    var remainder = totalMicros - allocated.sum()
    val order = raw.indices.sortedWith(
        compareByDescending<Int> { raw[it].subtract(BigDecimal.valueOf(allocated[it])) }.thenBy { it }
    )
    if (remainder > 0L) {
        val completeRounds = remainder / order.size
        if (completeRounds > 0L) allocated.indices.forEach { allocated[it] += completeRounds }
        remainder %= order.size
        repeat(remainder.toInt()) { allocated[order[it]]++ }
    }
    return allocated
}

data class FoodQuantityEdit(
    val id: Long,
    val quantity: Double,
    val enteredUnit: String = QuantityUnit.SERVINGS.name,
    val enteredAmount: Double = quantity,
    val actualPaidTotalMicros: Long? = null,
    val excludeCostFromBudget: Boolean = false
)

fun FoodLogSnapshot.quantityEdit(
    quantity: Double,
    enteredUnit: QuantityUnit = QuantityUnit.entries.firstOrNull { it.name == this.enteredUnit } ?: QuantityUnit.SERVINGS,
    enteredAmount: Double = quantity,
    actualPaidTotalMicros: Long? = this.actualPaidTotalMicros,
    excludeCostFromBudget: Boolean = this.excludeCostFromBudget
) = FoodQuantityEdit(
    id = id,
    quantity = quantity,
    enteredUnit = enteredUnit.name,
    enteredAmount = enteredAmount,
    actualPaidTotalMicros = actualPaidTotalMicros,
    excludeCostFromBudget = excludeCostFromBudget
)

data class DeletedFoodLogSnapshot(
    val log: FoodLogSnapshot,
    val extras: List<DailyExtraNutrientLogEntity>
)

data class DeletedFoodLogGroup(
    val logs: List<DeletedFoodLogSnapshot>
)

data class FoodMutationResult(
    val date: LocalDate,
    val before: NutritionSummary,
    val after: NutritionSummary,
    val deleted: DeletedFoodLogSnapshot? = null
)

data class FoodGroupMutationResult(
    val date: LocalDate,
    val before: NutritionSummary,
    val after: NutritionSummary,
    val deleted: DeletedFoodLogGroup
)

sealed interface FoodLogGroup {
    val key: String
    val logs: List<FoodLogSnapshot>

    data class Single(val log: FoodLogSnapshot) : FoodLogGroup {
        override val key: String = "log-${log.id}"
        override val logs: List<FoodLogSnapshot> = listOf(log)
    }

    data class Bulk(
        val mealId: String,
        val label: String,
        override val logs: List<FoodLogSnapshot>
    ) : FoodLogGroup {
        override val key: String = "meal-$mealId"
        val calories: Double get() = logs.sumOf(FoodLogSnapshot::calories)
        val proteinG: Double get() = logs.sumOf(FoodLogSnapshot::proteinG)
        val recordedCostMicros: Long? get() =
            if (logs.all { it.recordedCostMicros != null }) logs.sumOf { requireNotNull(it.recordedCostMicros) } else null
    }
}

fun List<FoodLogSnapshot>.groupForDisplay(): List<FoodLogGroup> {
    val bulk = filter { it.mealId != null }.groupBy { requireNotNull(it.mealId) }
    val emitted = mutableSetOf<String>()
    return sortedByDescending(FoodLogSnapshot::loggedAt).mapNotNull { log ->
        val mealId = log.mealId ?: return@mapNotNull FoodLogGroup.Single(log)
        if (!emitted.add(mealId)) return@mapNotNull null
        val rows = bulk.getValue(mealId).sortedWith(compareBy(FoodLogSnapshot::loggedAt, FoodLogSnapshot::id))
        FoodLogGroup.Bulk(mealId, rows.firstNotNullOfOrNull(FoodLogSnapshot::mealName) ?: "Bulk purchase", rows)
    }
}

data class ProductMutationResult(
    val product: ProductEntity,
    val linkedEntriesUpdated: Int,
    val affectedDates: Set<LocalDate>
)

data class PlannerDayContext(
    val consumed: NutritionSummary,
    val spending: DailySpending,
    val loggedServingsByProductId: Map<String, Double> = emptyMap()
)

data class ConstraintImpact(
    val label: String,
    val baseline: Double,
    val projected: Double,
    val target: Double,
    val percentDifference: Double,
    val withinTolerance: Boolean
)

data class RecommendationItem(
    val productId: String,
    val name: String,
    val purchaseUnits: Int,
    val servings: Double,
    val costMicros: Long?,
    val nutrition: NutritionSummary,
    val itemType: PlannerItemType = PlannerItemType.FOOD,
    val fixed: Boolean = false,
    val quantityLabel: String = "${servings.toDisplay()} servings"
)

enum class RecommendationMode { STRICT, BALANCED_FALLBACK }

data class RecommendationPlan(
    val items: List<RecommendationItem>,
    val nutrition: NutritionSummary,
    val totalCostMicros: Long,
    val projectedSpendingMicros: Long,
    val withinBudget: Boolean,
    val completeFit: Boolean,
    val impacts: List<ConstraintImpact>,
    val explanation: String,
    val unknownCostItems: Int = 0,
    val mode: RecommendationMode = RecommendationMode.STRICT,
    val unmetMinimums: List<ConstraintImpact> = emptyList()
) {
    val balancedFallback: Boolean get() = mode == RecommendationMode.BALANCED_FALLBACK
    val minimumTargetFallback: Boolean get() = balancedFallback
    val spendingComplete: Boolean get() = unknownCostItems == 0
}

data class RecommendationResult(
    val plans: List<RecommendationPlan>,
    val unpricedProducts: Int,
    val spendingIncomplete: Boolean,
    val message: String? = null,
    val excludedFromPlanningProducts: Int = 0,
    val existingViolations: List<ConstraintImpact> = emptyList()
)

sealed interface ScannerResult {
    data class Found(val barcode: String) : ScannerResult
    data object Cancelled : ScannerResult
    data class Failed(val reason: ScannerFailure) : ScannerResult
}

enum class ScannerFailure {
    PERMISSION_DENIED,
    CAMERA_UNAVAILABLE,
    CAMERA_START_FAILED,
    DECODER_FAILED
}

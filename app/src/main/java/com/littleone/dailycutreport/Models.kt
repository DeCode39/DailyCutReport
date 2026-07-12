package com.littleone.dailycutreport

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
    val healthConnectStatus: String = "Not loaded"
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
    val effectiveCalorieTarget: Double
        get() = if (mode == GoalMode.DEFICIT) expectedBurnCalories - desiredDeficitCalories else calories

    val targets: DailyNutritionTargets
        get() = DailyNutritionTargets(
            effectiveCalorieTarget, proteinG, sodiumMg, carbsG, fatG, sugarG, fiberG, saturatedFatG
        )

    fun requireValid(): UserGoals = apply {
        require(calories > 0.0) { "Calorie target must be greater than zero." }
        require(expectedBurnCalories > 0.0) { "Expected burn must be greater than zero." }
        require(desiredDeficitCalories >= 0.0 && desiredDeficitCalories < expectedBurnCalories) {
            "Desired deficit must be lower than expected burn."
        }
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
        val deficit = finiteOr(desiredDeficitCalories, 450.0).coerceIn(0.0, (burn - 1.0).coerceAtLeast(0.0))
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
    val caloriesPerServing: Double = 0.0,
    val proteinGPerServing: Double = 0.0,
    val sodiumMgPerServing: Double = 0.0,
    val carbsGPerServing: Double = 0.0,
    val fatGPerServing: Double = 0.0,
    val sugarGPerServing: Double = 0.0,
    val fiberGPerServing: Double = 0.0,
    val saturatedFatGPerServing: Double = 0.0,
    val catalogCostPerServingMicros: Long? = null,
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
    val effectiveCostMicros: Long? get() = LoggedCost(catalogCostPerServingMicros, actualPaidTotalMicros, excludeCostFromBudget).effectiveTotalMicros(quantity)
    val recordedCostMicros: Long? get() = LoggedCost(catalogCostPerServingMicros, actualPaidTotalMicros).recordedTotalMicros(quantity)
}

data class ProductWithExtras(
    val product: ProductEntity,
    val extras: List<ProductExtraNutrientEntity> = emptyList()
)

data class MealEntryInput(
    val product: ProductWithExtras,
    val quantity: Double
)

fun allocateMealPaidTotal(totalMicros: Long?, itemCount: Int): List<Long?> {
    require(itemCount >= 0)
    require(totalMicros == null || totalMicros >= 0L)
    if (itemCount == 0) return emptyList()
    if (totalMicros == null) return List(itemCount) { null }
    val base = totalMicros / itemCount
    val remainder = totalMicros % itemCount
    return List(itemCount) { index -> base + if (index < remainder) 1L else 0L }
}

data class FoodQuantityEdit(
    val id: Long,
    val quantity: Double,
    val actualPaidTotalMicros: Long? = null,
    val excludeCostFromBudget: Boolean = false
)

fun FoodLogSnapshot.quantityEdit(
    quantity: Double,
    actualPaidTotalMicros: Long? = this.actualPaidTotalMicros,
    excludeCostFromBudget: Boolean = this.excludeCostFromBudget
) = FoodQuantityEdit(
    id = id,
    quantity = quantity,
    actualPaidTotalMicros = actualPaidTotalMicros,
    excludeCostFromBudget = excludeCostFromBudget
)

data class DeletedFoodLogSnapshot(
    val log: FoodLogSnapshot,
    val extras: List<DailyExtraNutrientLogEntity>
)

data class FoodMutationResult(
    val date: LocalDate,
    val before: NutritionSummary,
    val after: NutritionSummary,
    val deleted: DeletedFoodLogSnapshot? = null
)

data class ProductMutationResult(
    val product: ProductEntity,
    val linkedEntriesUpdated: Int,
    val affectedDates: Set<LocalDate>
)

data class ConstraintDelta(
    val label: String,
    val actual: Double,
    val target: Double,
    val percentDifference: Double,
    val withinTolerance: Boolean
)

data class RecommendationItem(
    val productId: String,
    val name: String,
    val purchaseUnits: Int,
    val servings: Double,
    val costMicros: Long,
    val nutrition: NutritionSummary,
    val itemType: PlannerItemType = PlannerItemType.FOOD,
    val fixed: Boolean = false
)

data class RecommendationPlan(
    val items: List<RecommendationItem>,
    val nutrition: NutritionSummary,
    val totalCostMicros: Long,
    val projectedSpendingMicros: Long,
    val withinBudget: Boolean,
    val completeFit: Boolean,
    val deltas: List<ConstraintDelta>,
    val explanation: String
)

data class RecommendationResult(
    val plans: List<RecommendationPlan>,
    val excludedUnpricedProducts: Int,
    val spendingIncomplete: Boolean,
    val message: String? = null,
    val excludedFromPlanningProducts: Int = 0
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

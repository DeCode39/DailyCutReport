package com.littleone.dailycutreport

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

class OfflineMealPlanner(
    private val beamWidth: Int = 24,
    private val maxUnitsPerProduct: Int = 6,
    private val maxTotalUnits: Int = 20,
    private val maxDrinkUnits: Int = 2
) {
    fun generate(
        products: List<ProductEntity>,
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals
    ): RecommendationResult {
        goals.requireValid()
        val eligible = products.filter(ProductEntity::includeInPlanner)
        val excludedFromPlanning = products.size - eligible.size
        val excludedUnpriced = eligible.count { it.purchasePriceMicros == null }
        if (goals.dailyBudgetMicros <= 0L) {
            return RecommendationResult(emptyList(), excludedUnpriced, spending.unknownEntries > 0, "Set a daily budget first.", excludedFromPlanning)
        }
        val invalidFixed = eligible.filter { it.alwaysIncludeInPlanner && it.purchasePriceMicros == null }
        if (invalidFixed.isNotEmpty()) {
            return RecommendationResult(
                emptyList(), excludedUnpriced, spending.unknownEntries > 0,
                "Add prices to fixed planner items before planning.", excludedFromPlanning
            )
        }
        val fixedProducts = eligible.filter(ProductEntity::alwaysIncludeInPlanner).sortedBy(ProductEntity::productId)
        if (fixedProducts.size > maxTotalUnits) {
            return RecommendationResult(
                emptyList(), excludedUnpriced, spending.unknownEntries > 0,
                "Too many fixed items: planning allows at most $maxTotalUnits purchase units.", excludedFromPlanning
            )
        }
        if (fixedProducts.count { it.plannerType == PlannerItemType.DRINK } > maxDrinkUnits) {
            return RecommendationResult(
                emptyList(), excludedUnpriced, spending.unknownEntries > 0,
                "Too many fixed drinks: planning allows at most $maxDrinkUnits.", excludedFromPlanning
            )
        }
        val allCandidates = eligible.asSequence()
            .filterNot(ProductEntity::alwaysIncludeInPlanner)
            .filter { it.purchasePriceMicros != null && it.purchasePriceMicros >= 0L && it.purchaseUnitServings > 0.0 }
            .sortedBy { it.productId }
            .toList()
        if (allCandidates.isEmpty() && fixedProducts.isEmpty()) {
            return RecommendationResult(
                emptyList(), excludedUnpriced, spending.unknownEntries > 0,
                "Enable planning and add prices to saved products before planning.", excludedFromPlanning
            )
        }
        val candidates = shortlist(allCandidates, consumed, goals)

        val fixedState = fixedProducts.fold(PlanState()) { state, product -> state.add(product, 1, fixed = true) }
        if (!withinHardCeilings(fixedState, consumed, spending, goals)) {
            val violations = hardViolations(consumed + fixedState.nutrition, spending.knownTotalMicros + fixedState.costMicros, goals)
            val fallback = minimumTargetFallback(candidates, fixedState, consumed, spending, goals)
            return RecommendationResult(
                listOfNotNull(fallback), excludedUnpriced, spending.unknownEntries > 0,
                if (fallback == null) {
                    "Planning is blocked by current or fixed-item limits; no additional food is needed for the protein or fiber minimums."
                } else {
                    "A complete plan is impossible because current or fixed values exceed the allowed limits. Showing one best option for the unmet minimum goals."
                },
                excludedFromPlanning,
                violations.ifEmpty { fallback?.deltas.orEmpty().filterNot(ConstraintDelta::withinTolerance) }
            )
        }
        var beam = listOf(fixedState)
        candidates.forEach { product ->
            val price = requireNotNull(product.purchasePriceMicros)
            val budgetHeadroom = (goals.dailyBudgetMicros * 1.1).toLong() - spending.knownTotalMicros
            val affordable = if (price == 0L) maxUnitsPerProduct else floor(budgetHeadroom.coerceAtLeast(0).toDouble() / price).toInt()
            val drinkRoom = if (product.plannerType == PlannerItemType.DRINK) maxDrinkUnits else maxUnitsPerProduct
            val maxUnits = minOf(maxUnitsPerProduct, affordable.coerceAtLeast(0), drinkRoom)
            val expanded = ArrayList<PlanState>(beam.size * (maxUnits + 1))
            beam.forEach { state ->
                for (units in 0..maxUnits) {
                    if (state.totalUnits + units > maxTotalUnits) break
                    if (product.plannerType == PlannerItemType.DRINK && state.drinkUnits + units > maxDrinkUnits) break
                    val next = if (units == 0) state else state.add(product, units)
                    if (withinHardCeilings(next, consumed, spending, goals)) expanded += next
                }
            }
            beam = expanded.distinctBy(PlanState::signature)
                .map { ScoredState(it, evaluate(it, consumed, spending, goals)) }
                .sortedWith(scoredComparator)
                .take(beamWidth)
                .map(ScoredState::state)
        }

        val rankedPlans = beam.asSequence()
            .filter { it.items.isNotEmpty() }
            .distinctBy(PlanState::signature)
            .map { ScoredState(it, evaluate(it, consumed, spending, goals)) }
            .sortedWith(scoredComparator)
            .toList()
        val completePlans = rankedPlans.asSequence()
            .filter { it.evaluation.complete }
            .take(3)
            .map { it.state.toRecommendation(consumed, spending, goals) }
            .toList()
        if (completePlans.isNotEmpty()) {
            return RecommendationResult(
                completePlans, excludedUnpriced, spending.unknownEntries > 0,
                null, excludedFromPlanning
            )
        }

        val fallback = minimumTargetFallback(candidates, fixedState, consumed, spending, goals)
        val violations = fallback?.deltas.orEmpty().filterNot(ConstraintDelta::withinTolerance)
        val message = when {
            fallback == null -> "No complete plan fits, and no purchasable combination improves the remaining protein or fiber minimums."
            else -> "No complete plan fits the current values and limits. Showing one best option for the unmet minimum goals."
        }
        return RecommendationResult(
            listOfNotNull(fallback), excludedUnpriced, spending.unknownEntries > 0,
            message, excludedFromPlanning, violations
        )
    }

    private fun minimumTargetFallback(
        candidates: List<ProductEntity>,
        fixedState: PlanState,
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals
    ): RecommendationPlan? {
        val startingNutrition = consumed + fixedState.nutrition
        val needsProtein = goals.proteinG > 0.0 && startingNutrition.proteinG < goals.proteinG
        val needsFiber = goals.fiberG > 0.0 && startingNutrition.fiberG < goals.fiberG
        if (!needsProtein && !needsFiber) return null

        var beam = listOf(fixedState)
        candidates.forEach { product ->
            val expanded = ArrayList<PlanState>(beam.size * (maxUnitsPerProduct + 1))
            beam.forEach { state ->
                for (units in 0..maxUnitsPerProduct) {
                    if (state.totalUnits + units > maxTotalUnits) break
                    if (product.plannerType == PlannerItemType.DRINK && state.drinkUnits + units > maxDrinkUnits) break
                    expanded += if (units == 0) state else state.add(product, units)
                }
            }
            beam = expanded.distinctBy(PlanState::signature)
                .map { MinimumScoredState(it, minimumEvaluation(it, consumed, spending, goals)) }
                .sortedWith(minimumComparator)
                .take(beamWidth)
                .map(MinimumScoredState::state)
        }

        val baseProtein = consumed.proteinG
        val baseFiber = consumed.fiberG
        return beam.asSequence()
            .filter { it.items.isNotEmpty() }
            .filter {
                val projected = consumed + it.nutrition
                (needsProtein && projected.proteinG > baseProtein) || (needsFiber && projected.fiberG > baseFiber)
            }
            .distinctBy(PlanState::signature)
            .map { MinimumScoredState(it, minimumEvaluation(it, consumed, spending, goals)) }
            .sortedWith(minimumComparator)
            .firstOrNull()
            ?.state
            ?.toRecommendation(consumed, spending, goals, minimumTargetFallback = true)
    }

    private fun shortlist(products: List<ProductEntity>, consumed: NutritionSummary, goals: UserGoals): List<ProductEntity> {
        if (products.size <= 24) return products
        val remainingCalories = (goals.effectiveCalorieTarget - consumed.calories).coerceAtLeast(1.0)
        val remainingProtein = (goals.proteinG - consumed.proteinG).coerceAtLeast(1.0)
        val remainingFiber = (goals.fiberG - consumed.fiberG).coerceAtLeast(1.0)
        val balanced = products.sortedWith(compareBy<ProductEntity> { product ->
            val servings = product.purchaseUnitServings
            val calorieShare = product.calories * servings / remainingCalories
            val proteinShare = product.proteinG * servings / remainingProtein
            val fiberShare = product.fiberG * servings / remainingFiber
            val costShare = requireNotNull(product.purchasePriceMicros).toDouble() / goals.dailyBudgetMicros.coerceAtLeast(1L)
            abs(calorieShare - 0.25) - proteinShare * 0.4 - fiberShare * 0.2 + costShare * 0.25
        }.thenBy { it.productId }).take(10)
        val cheapest = products.sortedWith(compareBy<ProductEntity> { it.purchasePriceMicros }.thenBy { it.productId }).take(6)
        val proteinDense = products.sortedWith(compareByDescending<ProductEntity> {
            it.proteinG * it.purchaseUnitServings / requireNotNull(it.purchasePriceMicros).coerceAtLeast(1L)
        }.thenBy { it.productId }).take(6)
        val fiberDense = products.sortedWith(compareByDescending<ProductEntity> {
            it.fiberG * it.purchaseUnitServings / requireNotNull(it.purchasePriceMicros).coerceAtLeast(1L)
        }.thenBy { it.productId }).take(6)
        return (balanced + cheapest + proteinDense + fiberDense).distinctBy { it.productId }.sortedBy { it.productId }
    }

    private fun withinHardCeilings(state: PlanState, consumed: NutritionSummary, spending: DailySpending, goals: UserGoals): Boolean {
        val total = consumed + state.nutrition
        val targets = goals.targets
        return state.totalUnits <= maxTotalUnits && state.drinkUnits <= maxDrinkUnits &&
            total.calories <= targets.calories * 1.1 &&
            total.sodiumMg <= targets.sodiumMg * 1.1 &&
            total.carbsG <= targets.carbsG * 1.1 &&
            total.fatG <= targets.fatG * 1.1 &&
            total.sugarG <= targets.sugarG * 1.1 &&
            total.saturatedFatG <= targets.saturatedFatG * 1.1 &&
            spending.knownTotalMicros + state.costMicros <= (goals.dailyBudgetMicros * 1.1).toLong()
    }

    private val scoredComparator = compareByDescending<ScoredState> { it.evaluation.complete }
        .thenByDescending { it.evaluation.withinBudget }
        .thenBy { it.evaluation.misses }
        .thenBy { it.evaluation.penalty }
        .thenBy { it.state.costMicros }
        .thenBy { it.state.items.size }
        .thenBy { it.state.signature() }

    private val minimumComparator = compareBy<MinimumScoredState> { it.evaluation.unmetMinimums }
        .thenBy { it.evaluation.minimumShortfall }
        .thenBy { it.evaluation.damagePenalty }
        .thenBy { it.state.costMicros }
        .thenBy { it.state.items.size }
        .thenBy { it.state.signature() }

    private fun evaluate(state: PlanState, consumed: NutritionSummary, spending: DailySpending, goals: UserGoals): Evaluation {
        val n = consumed + state.nutrition
        val t = goals.targets
        val calorieDeviation = abs(n.calories - t.calories) / t.calories
        val proteinShortfall = ((t.proteinG - n.proteinG) / t.proteinG.coerceAtLeast(1.0)).coerceAtLeast(0.0)
        val fiberShortfall = ((t.fiberG - n.fiberG) / t.fiberG.coerceAtLeast(1.0)).coerceAtLeast(0.0)
        val overages = listOf(
            ratioOver(n.sodiumMg, t.sodiumMg), ratioOver(n.carbsG, t.carbsG), ratioOver(n.fatG, t.fatG),
            ratioOver(n.sugarG, t.sugarG), ratioOver(n.saturatedFatG, t.saturatedFatG)
        )
        val projected = spending.knownTotalMicros + state.costMicros
        val budgetOverage = ratioOver(projected.toDouble(), goals.dailyBudgetMicros.toDouble())
        val misses = (if (calorieDeviation > 0.1) 1 else 0) +
            (if (proteinShortfall > 0.1) 1 else 0) +
            (if (fiberShortfall > 0.1) 1 else 0) +
            overages.count { it > 0.1 } +
            (if (budgetOverage > 0.1) 1 else 0)
        return Evaluation(
            complete = misses == 0,
            withinBudget = projected <= goals.dailyBudgetMicros,
            misses = misses,
            penalty = calorieDeviation + proteinShortfall + fiberShortfall + overages.sum() + budgetOverage * 2.0
        )
    }

    private fun minimumEvaluation(
        state: PlanState,
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals
    ): MinimumEvaluation {
        val n = consumed + state.nutrition
        val t = goals.targets
        val proteinShortfall = if (t.proteinG <= 0.0) 0.0 else ((t.proteinG - n.proteinG) / t.proteinG).coerceAtLeast(0.0)
        val fiberShortfall = if (t.fiberG <= 0.0) 0.0 else ((t.fiberG - n.fiberG) / t.fiberG).coerceAtLeast(0.0)
        val unmet = (if (proteinShortfall > 0.0) 1 else 0) + (if (fiberShortfall > 0.0) 1 else 0)
        val projectedSpending = spending.knownTotalMicros + state.costMicros
        val upperDamage = ratioOver(n.calories, t.calories) +
            ratioOver(n.sodiumMg, t.sodiumMg) + ratioOver(n.carbsG, t.carbsG) +
            ratioOver(n.fatG, t.fatG) + ratioOver(n.sugarG, t.sugarG) +
            ratioOver(n.saturatedFatG, t.saturatedFatG) +
            ratioOver(projectedSpending.toDouble(), goals.dailyBudgetMicros.toDouble()) * 2.0
        return MinimumEvaluation(unmet, proteinShortfall + fiberShortfall, upperDamage)
    }

    private fun PlanState.toRecommendation(
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals,
        minimumTargetFallback: Boolean = false
    ): RecommendationPlan {
        val projectedNutrition = consumed + nutrition
        val projectedSpending = spending.knownTotalMicros + costMicros
        val evaluation = evaluate(this, consumed, spending, goals)
        val deltas = projectedNutrition.deltas(goals.targets) + ConstraintDelta(
            "Budget", projectedSpending.toDouble() / MONEY_MICROS_PER_UNIT,
            goals.dailyBudgetMicros.toDouble() / MONEY_MICROS_PER_UNIT,
            percentageDifference(projectedSpending.toDouble(), goals.dailyBudgetMicros.toDouble()),
            projectedSpending <= goals.dailyBudgetMicros * 1.1
        )
        val explanation = buildString {
            append(when {
                minimumTargetFallback -> "Best available option for unmet protein and fiber minimums"
                evaluation.complete -> "Within goal tolerances"
                else -> "Closest partial match"
            })
            append(when {
                evaluation.withinBudget -> " and within budget."
                projectedSpending <= goals.dailyBudgetMicros * 1.1 -> "; budget is within the allowed 10% margin."
                else -> "; this exceeds the budget limit and is shown only as a minimum-goal fallback."
            })
        }
        return RecommendationPlan(
            items, projectedNutrition, costMicros, projectedSpending, evaluation.withinBudget,
            evaluation.complete, deltas, explanation, minimumTargetFallback
        )
    }

    private fun hardViolations(nutrition: NutritionSummary, spendingMicros: Long, goals: UserGoals): List<ConstraintDelta> {
        val nutritionViolations = nutrition.deltas(goals.targets).filter {
            it.label !in setOf("Protein", "Fiber") && it.percentDifference > 10.0
        }
        val budget = ConstraintDelta(
            "Budget",
            spendingMicros.toDouble() / MONEY_MICROS_PER_UNIT,
            goals.dailyBudgetMicros.toDouble() / MONEY_MICROS_PER_UNIT,
            percentageDifference(spendingMicros.toDouble(), goals.dailyBudgetMicros.toDouble()),
            spendingMicros <= goals.dailyBudgetMicros * 1.1
        )
        return nutritionViolations + listOfNotNull(budget.takeUnless(ConstraintDelta::withinTolerance))
    }

    private data class Evaluation(val complete: Boolean, val withinBudget: Boolean, val misses: Int, val penalty: Double)
    private data class ScoredState(val state: PlanState, val evaluation: Evaluation)
    private data class MinimumEvaluation(val unmetMinimums: Int, val minimumShortfall: Double, val damagePenalty: Double)
    private data class MinimumScoredState(val state: PlanState, val evaluation: MinimumEvaluation)

    private data class PlanState(
        val items: List<RecommendationItem> = emptyList(),
        val nutrition: NutritionSummary = NutritionSummary(),
        val costMicros: Long = 0L,
        val totalUnits: Int = 0,
        val drinkUnits: Int = 0
    ) {
        fun add(product: ProductEntity, units: Int, fixed: Boolean = false): PlanState {
            val servings = product.purchaseUnitServings * units
            val itemNutrition = product.nutrition(servings)
            val item = RecommendationItem(
                product.productId, product.name, units, servings,
                requireNotNull(product.purchasePriceMicros) * units, itemNutrition,
                product.plannerType, fixed
            )
            return copy(
                items = items + item,
                nutrition = nutrition + itemNutrition,
                costMicros = costMicros + item.costMicros,
                totalUnits = totalUnits + units,
                drinkUnits = drinkUnits + if (product.plannerType == PlannerItemType.DRINK) units else 0
            )
        }

        fun signature(): String = items.joinToString("|") { "${it.productId}:${it.purchaseUnits}" }
    }
}

private val ProductEntity.plannerType: PlannerItemType
    get() = PlannerItemType.entries.firstOrNull { it.name == plannerItemType } ?: PlannerItemType.FOOD

private fun ProductEntity.nutrition(servings: Double) = NutritionSummary(
    calories * servings, proteinG * servings, sodiumMg * servings, carbsG * servings,
    fatG * servings, sugarG * servings, fiberG * servings, saturatedFatG * servings
)

operator fun NutritionSummary.plus(other: NutritionSummary) = NutritionSummary(
    calories + other.calories, proteinG + other.proteinG, sodiumMg + other.sodiumMg,
    carbsG + other.carbsG, fatG + other.fatG, sugarG + other.sugarG,
    fiberG + other.fiberG, saturatedFatG + other.saturatedFatG,
    entries + other.entries, extras
)

private fun NutritionSummary.deltas(targets: DailyNutritionTargets): List<ConstraintDelta> = listOf(
    targetDelta("Calories", calories, targets.calories, true),
    targetDelta("Protein", proteinG, targets.proteinG, false),
    targetDelta("Fiber", fiberG, targets.fiberG, false),
    targetDelta("Sodium", sodiumMg, targets.sodiumMg, true),
    targetDelta("Carbs", carbsG, targets.carbsG, true),
    targetDelta("Fat", fatG, targets.fatG, true),
    targetDelta("Sugar", sugarG, targets.sugarG, true),
    targetDelta("Saturated fat", saturatedFatG, targets.saturatedFatG, true)
)

private fun targetDelta(label: String, actual: Double, target: Double, upper: Boolean): ConstraintDelta {
    val difference = percentageDifference(actual, target)
    val within = if (label == "Calories") abs(difference) <= 10.0
    else if (upper) difference <= 10.0 else difference >= -10.0
    return ConstraintDelta(label, actual, target, difference, within)
}

private fun percentageDifference(actual: Double, target: Double): Double =
    if (target <= 0.0) 0.0 else ((actual - target) / target) * 100.0

private fun ratioOver(actual: Double, target: Double): Double =
    if (target <= 0.0) 0.0 else ((actual - target) / target).coerceAtLeast(0.0)

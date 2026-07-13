package com.littleone.dailycutreport

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class OfflineMealPlanner(
    private val beamWidth: Int = 24,
    private val fallbackBeamWidth: Int = 48,
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
        require(goals.mode == GoalMode.CALORIE) { "Planner requires a resolved calorie allowance." }
        val eligible = products.filter(ProductEntity::includeInPlanner).sortedBy(ProductEntity::productId)
        val excludedFromPlanning = products.size - eligible.size
        val unpriced = eligible.count { it.purchasePriceMicros == null }

        val strictBlock = strictSetupBlock(eligible, spending, goals)
        if (strictBlock == null) {
            val strict = strictPlans(eligible, consumed, spending, goals)
            if (strict.plans.isNotEmpty()) {
                return RecommendationResult(
                    plans = strict.plans,
                    unpricedProducts = unpriced,
                    spendingIncomplete = spending.unknownEntries > 0,
                    excludedFromPlanningProducts = excludedFromPlanning
                )
            }
            return fallbackResult(
                eligible, consumed, spending, goals, unpriced, excludedFromPlanning,
                strict.reason ?: "No complete strict plan fits the current values and limits.",
                strict.violations
            )
        }

        return fallbackResult(
            eligible, consumed, spending, goals, unpriced, excludedFromPlanning,
            strictBlock, emptyList()
        )
    }

    private fun strictSetupBlock(
        eligible: List<ProductEntity>,
        spending: DailySpending,
        goals: UserGoals
    ): String? {
        if (goals.dailyBudgetMicros <= 0L) return "Strict planning requires a daily budget."
        val fixed = eligible.filter(ProductEntity::alwaysIncludeInPlanner)
        if (fixed.any { it.purchasePriceMicros == null }) return "Strict planning requires prices for fixed items."
        if (fixed.size > maxTotalUnits) return "Strict planning allows at most $maxTotalUnits fixed purchase units."
        if (fixed.count { it.plannerType == PlannerItemType.DRINK } > maxDrinkUnits) {
            return "Strict planning allows at most $maxDrinkUnits fixed drinks."
        }
        if (spending.knownTotalMicros > (goals.dailyBudgetMicros * 1.1).toLong()) {
            return "Current spending already exceeds the strict budget ceiling."
        }
        return null
    }

    private fun strictPlans(
        eligible: List<ProductEntity>,
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals
    ): StrictOutcome {
        val fixedProducts = eligible.filter(ProductEntity::alwaysIncludeInPlanner)
        val allCandidates = eligible.asSequence()
            .filterNot(ProductEntity::alwaysIncludeInPlanner)
            .filter { it.purchasePriceMicros != null && it.purchasePriceMicros >= 0L && it.purchaseUnitServings > 0.0 }
            .toList()
        val fixedState = fixedProducts.fold(PlanState()) { state, product -> state.add(product, 1, fixed = true) }
        if (!withinHardCeilings(fixedState, consumed, spending, goals)) {
            return StrictOutcome(
                reason = "Current values or fixed items exceed a strict nutrition or budget ceiling.",
                violations = hardViolations(consumed + fixedState.nutrition, spending.knownTotalMicros + fixedState.knownCostMicros, goals)
            )
        }
        if (allCandidates.isEmpty() && fixedProducts.isEmpty()) {
            return StrictOutcome(reason = "Strict planning has no priced eligible products.")
        }

        var beam = listOf(fixedState)
        shortlist(allCandidates, consumed, goals).forEach { product ->
            val price = requireNotNull(product.purchasePriceMicros)
            val budgetHeadroom = (goals.dailyBudgetMicros * 1.1).toLong() - spending.knownTotalMicros
            val affordable = if (price == 0L) maxUnitsPerProduct
            else floor(budgetHeadroom.coerceAtLeast(0).toDouble() / price).toInt()
            val perProductLimit = if (product.plannerType == PlannerItemType.DRINK) maxDrinkUnits else maxUnitsPerProduct
            val maxUnits = minOf(maxUnitsPerProduct, affordable.coerceAtLeast(0), perProductLimit)
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
                .map { ScoredState(it, evaluateStrict(it, consumed, spending, goals)) }
                .sortedWith(strictComparator)
                .take(beamWidth)
                .map(ScoredState::state)
        }

        val plans = beam.asSequence()
            .filter { it.items.isNotEmpty() }
            .distinctBy(PlanState::signature)
            .map { ScoredState(it, evaluateStrict(it, consumed, spending, goals)) }
            .filter { it.evaluation.complete }
            .sortedWith(strictComparator)
            .take(3)
            .map { it.state.toRecommendation(consumed, spending, goals, RecommendationMode.STRICT) }
            .toList()
        return StrictOutcome(plans, reason = if (plans.isEmpty()) "No complete strict plan fits the current values and limits." else null)
    }

    private fun fallbackResult(
        eligible: List<ProductEntity>,
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals,
        unpriced: Int,
        excludedFromPlanning: Int,
        strictReason: String,
        strictViolations: List<ConstraintDelta>
    ): RecommendationResult {
        val fallback = unrestrictedMinimumFallback(eligible, consumed, spending, goals)
        val message = if (fallback == null) {
            "$strictReason No eligible product can improve an unmet protein or fiber minimum."
        } else {
            "$strictReason Showing one unrestricted minimum-target option; fixed-item, drink, budget, calorie, upper-nutrient, and strict quantity limits are ignored."
        }
        val fallbackViolations = fallback?.let { plan ->
            (plan.unmetMinimums + plan.deltas.filterNot(ConstraintDelta::withinTolerance)).distinctBy(ConstraintDelta::label)
        }.orEmpty()
        return RecommendationResult(
            plans = listOfNotNull(fallback),
            unpricedProducts = unpriced,
            spendingIncomplete = spending.unknownEntries > 0 || (fallback?.unknownCostItems ?: 0) > 0,
            message = message,
            excludedFromPlanningProducts = excludedFromPlanning,
            blockingViolations = (strictViolations + fallbackViolations).distinctBy(ConstraintDelta::label)
        )
    }

    private fun unrestrictedMinimumFallback(
        eligible: List<ProductEntity>,
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals
    ): RecommendationPlan? {
        val needsProtein = goals.proteinG > 0.0 && consumed.proteinG < goals.proteinG
        val needsFiber = goals.fiberG > 0.0 && consumed.fiberG < goals.fiberG
        if (!needsProtein && !needsFiber) return null
        val candidates = eligible.filter { product ->
            product.purchaseUnitServings > 0.0 &&
                ((needsProtein && product.proteinG > 0.0) || (needsFiber && product.fiberG > 0.0))
        }
        if (candidates.isEmpty()) return null

        var beam = listOf(PlanState())
        candidates.forEach { product ->
            val expanded = ArrayList<PlanState>(beam.size * 8)
            beam.forEach { state ->
                fallbackQuantityOptions(state, product, consumed, goals).forEach { units ->
                    expanded += if (units == 0) state else state.add(product, units)
                }
            }
            beam = expanded.distinctBy(PlanState::signature)
                .map { MinimumScoredState(it, evaluateMinimum(it, consumed, goals)) }
                .sortedWith(minimumComparator)
                .take(fallbackBeamWidth)
                .map(MinimumScoredState::state)
        }

        return beam.asSequence()
            .filter { it.items.isNotEmpty() }
            .filter {
                val projected = consumed + it.nutrition
                (needsProtein && projected.proteinG > consumed.proteinG) ||
                    (needsFiber && projected.fiberG > consumed.fiberG)
            }
            .distinctBy(PlanState::signature)
            .map { MinimumScoredState(it, evaluateMinimum(it, consumed, goals)) }
            .sortedWith(minimumComparator)
            .firstOrNull()
            ?.state
            ?.toRecommendation(consumed, spending, goals, RecommendationMode.UNRESTRICTED_MINIMUM)
    }

    private fun fallbackQuantityOptions(
        state: PlanState,
        product: ProductEntity,
        consumed: NutritionSummary,
        goals: UserGoals
    ): List<Int> {
        val projected = consumed + state.nutrition
        val proteinPerUnit = product.proteinG * product.purchaseUnitServings
        val fiberPerUnit = product.fiberG * product.purchaseUnitServings
        val boundaries = buildList {
            if (goals.proteinG > projected.proteinG && proteinPerUnit > 0.0) {
                add(usefulUnits(goals.proteinG - projected.proteinG, proteinPerUnit))
            }
            if (goals.fiberG > projected.fiberG && fiberPerUnit > 0.0) {
                add(usefulUnits(goals.fiberG - projected.fiberG, fiberPerUnit))
            }
        }
        if (boundaries.isEmpty()) return listOf(0)
        return buildSet {
            add(0)
            add(1)
            boundaries.forEach { boundary ->
                add(boundary)
                if (boundary > 1) add(boundary - 1)
                for (divisor in 2..4) add(ceil(boundary.toDouble() / divisor).toInt().coerceAtLeast(1))
            }
        }.sorted()
    }

    private fun usefulUnits(remaining: Double, perUnit: Double): Int {
        val calculated = ceil(remaining / perUnit)
        return when {
            !calculated.isFinite() || calculated >= MAX_TARGET_DERIVED_UNITS -> MAX_TARGET_DERIVED_UNITS
            calculated <= 1.0 -> 1
            else -> calculated.toInt()
        }
    }

    private fun shortlist(products: List<ProductEntity>, consumed: NutritionSummary, goals: UserGoals): List<ProductEntity> {
        if (products.size <= 24) return products
        val remainingCalories = (goals.calories - consumed.calories).coerceAtLeast(1.0)
        val remainingProtein = (goals.proteinG - consumed.proteinG).coerceAtLeast(1.0)
        val remainingFiber = (goals.fiberG - consumed.fiberG).coerceAtLeast(1.0)
        val balanced = products.sortedWith(compareBy<ProductEntity> { product ->
            val servings = product.purchaseUnitServings
            val calorieShare = product.calories * servings / remainingCalories
            val proteinShare = product.proteinG * servings / remainingProtein
            val fiberShare = product.fiberG * servings / remainingFiber
            val costShare = requireNotNull(product.purchasePriceMicros).toDouble() / goals.dailyBudgetMicros.coerceAtLeast(1L)
            abs(calorieShare - 0.25) - proteinShare * 0.4 - fiberShare * 0.2 + costShare * 0.25
        }.thenBy(ProductEntity::productId)).take(10)
        val cheapest = products.sortedWith(compareBy<ProductEntity> { it.purchasePriceMicros }.thenBy(ProductEntity::productId)).take(6)
        val proteinDense = products.sortedWith(compareByDescending<ProductEntity> {
            it.proteinG * it.purchaseUnitServings / requireNotNull(it.purchasePriceMicros).coerceAtLeast(1L)
        }.thenBy(ProductEntity::productId)).take(6)
        val fiberDense = products.sortedWith(compareByDescending<ProductEntity> {
            it.fiberG * it.purchaseUnitServings / requireNotNull(it.purchasePriceMicros).coerceAtLeast(1L)
        }.thenBy(ProductEntity::productId)).take(6)
        return (balanced + cheapest + proteinDense + fiberDense).distinctBy(ProductEntity::productId).sortedBy(ProductEntity::productId)
    }

    private fun withinHardCeilings(state: PlanState, consumed: NutritionSummary, spending: DailySpending, goals: UserGoals): Boolean {
        val total = consumed + state.nutrition
        val targets = goals.targetsFor(null)
        return state.totalUnits <= maxTotalUnits && state.drinkUnits <= maxDrinkUnits &&
            total.calories <= targets.calories * 1.1 && total.sodiumMg <= targets.sodiumMg * 1.1 &&
            total.carbsG <= targets.carbsG * 1.1 && total.fatG <= targets.fatG * 1.1 &&
            total.sugarG <= targets.sugarG * 1.1 && total.saturatedFatG <= targets.saturatedFatG * 1.1 &&
            spending.knownTotalMicros + state.knownCostMicros <= (goals.dailyBudgetMicros * 1.1).toLong()
    }

    private val strictComparator = compareByDescending<ScoredState> { it.evaluation.complete }
        .thenByDescending { it.evaluation.withinBudget }
        .thenBy { it.evaluation.misses }
        .thenBy { it.evaluation.penalty }
        .thenBy { it.state.knownCostMicros }
        .thenBy { it.state.items.size }
        .thenBy { it.state.signature() }

    private val minimumComparator = compareBy<MinimumScoredState> { it.evaluation.unmetMinimums }
        .thenBy { it.evaluation.minimumShortfall }
        .thenBy { it.state.nutrition.calories }
        .thenBy { it.evaluation.upperDamage }
        .thenBy { it.state.unknownCostItems > 0 }
        .thenBy { it.state.knownCostMicros }
        .thenBy { it.state.items.size }
        .thenBy { it.state.signature() }

    private fun evaluateStrict(state: PlanState, consumed: NutritionSummary, spending: DailySpending, goals: UserGoals): Evaluation {
        val n = consumed + state.nutrition
        val t = goals.targetsFor(null)
        val calorieDeviation = normalizedDifference(n.calories, t.calories)
        val proteinShortfall = shortfall(n.proteinG, t.proteinG)
        val fiberShortfall = shortfall(n.fiberG, t.fiberG)
        val overages = listOf(
            ratioOver(n.sodiumMg, t.sodiumMg), ratioOver(n.carbsG, t.carbsG), ratioOver(n.fatG, t.fatG),
            ratioOver(n.sugarG, t.sugarG), ratioOver(n.saturatedFatG, t.saturatedFatG)
        )
        val projected = spending.knownTotalMicros + state.knownCostMicros
        val budgetOverage = ratioOver(projected.toDouble(), goals.dailyBudgetMicros.toDouble())
        val misses = (if (calorieDeviation > 0.1) 1 else 0) + (if (proteinShortfall > 0.1) 1 else 0) +
            (if (fiberShortfall > 0.1) 1 else 0) + overages.count { it > 0.1 } +
            (if (budgetOverage > 0.1) 1 else 0)
        return Evaluation(misses == 0, projected <= goals.dailyBudgetMicros, misses,
            calorieDeviation + proteinShortfall + fiberShortfall + overages.sum() + budgetOverage * 2.0)
    }

    private fun evaluateMinimum(state: PlanState, consumed: NutritionSummary, goals: UserGoals): MinimumEvaluation {
        val n = consumed + state.nutrition
        val t = goals.targetsFor(null)
        val proteinShortfall = shortfall(n.proteinG, t.proteinG)
        val fiberShortfall = shortfall(n.fiberG, t.fiberG)
        val unmet = (if (proteinShortfall > 0.0) 1 else 0) + (if (fiberShortfall > 0.0) 1 else 0)
        val upperDamage = incrementalOverage(consumed.sodiumMg, n.sodiumMg, t.sodiumMg) +
            incrementalOverage(consumed.carbsG, n.carbsG, t.carbsG) +
            incrementalOverage(consumed.fatG, n.fatG, t.fatG) +
            incrementalOverage(consumed.sugarG, n.sugarG, t.sugarG) +
            incrementalOverage(consumed.saturatedFatG, n.saturatedFatG, t.saturatedFatG)
        return MinimumEvaluation(unmet, proteinShortfall + fiberShortfall, upperDamage)
    }

    private fun PlanState.toRecommendation(
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals,
        mode: RecommendationMode
    ): RecommendationPlan {
        val projectedNutrition = consumed + nutrition
        val projectedSpending = spending.knownTotalMicros + knownCostMicros
        val evaluation = evaluateStrict(this, consumed, spending, goals)
        val deltas = projectedNutrition.deltas(goals.targetsFor(null)) + ConstraintDelta(
            "Budget", projectedSpending.toDouble() / MONEY_MICROS_PER_UNIT,
            goals.dailyBudgetMicros.toDouble() / MONEY_MICROS_PER_UNIT,
            percentageDifference(projectedSpending.toDouble(), goals.dailyBudgetMicros.toDouble()),
            projectedSpending <= goals.dailyBudgetMicros * 1.1
        )
        val unmetMinimums = deltas.filter { it.label in MINIMUM_LABELS && it.actual < it.target }
        val explanation = if (mode == RecommendationMode.UNRESTRICTED_MINIMUM) {
            "Unrestricted fallback prioritizing unmet protein and fiber with the least additional calories. Planner limits are ignored."
        } else {
            if (evaluation.withinBudget) "Within goal tolerances and budget."
            else "Within goal tolerances; budget is within the allowed 10% margin."
        }
        return RecommendationPlan(
            items = items,
            nutrition = projectedNutrition,
            totalCostMicros = knownCostMicros,
            projectedSpendingMicros = projectedSpending,
            withinBudget = projectedSpending <= goals.dailyBudgetMicros,
            completeFit = mode == RecommendationMode.STRICT && evaluation.complete,
            deltas = deltas,
            explanation = explanation,
            unknownCostItems = unknownCostItems,
            mode = mode,
            unmetMinimums = unmetMinimums
        )
    }

    private fun hardViolations(nutrition: NutritionSummary, spendingMicros: Long, goals: UserGoals): List<ConstraintDelta> {
        val nutritionViolations = nutrition.deltas(goals.targetsFor(null)).filter {
            it.label !in MINIMUM_LABELS && it.percentDifference > 10.0
        }
        val budget = ConstraintDelta(
            "Budget", spendingMicros.toDouble() / MONEY_MICROS_PER_UNIT,
            goals.dailyBudgetMicros.toDouble() / MONEY_MICROS_PER_UNIT,
            percentageDifference(spendingMicros.toDouble(), goals.dailyBudgetMicros.toDouble()),
            spendingMicros <= goals.dailyBudgetMicros * 1.1
        )
        return nutritionViolations + listOfNotNull(budget.takeUnless(ConstraintDelta::withinTolerance))
    }

    private data class StrictOutcome(
        val plans: List<RecommendationPlan> = emptyList(),
        val reason: String? = null,
        val violations: List<ConstraintDelta> = emptyList()
    )
    private data class Evaluation(val complete: Boolean, val withinBudget: Boolean, val misses: Int, val penalty: Double)
    private data class ScoredState(val state: PlanState, val evaluation: Evaluation)
    private data class MinimumEvaluation(val unmetMinimums: Int, val minimumShortfall: Double, val upperDamage: Double)
    private data class MinimumScoredState(val state: PlanState, val evaluation: MinimumEvaluation)

    private data class PlanState(
        val items: List<RecommendationItem> = emptyList(),
        val nutrition: NutritionSummary = NutritionSummary(),
        val knownCostMicros: Long = 0L,
        val unknownCostItems: Int = 0,
        val totalUnits: Int = 0,
        val drinkUnits: Int = 0
    ) {
        fun add(product: ProductEntity, units: Int, fixed: Boolean = false): PlanState {
            val servings = product.purchaseUnitServings * units
            val itemNutrition = product.nutrition(servings)
            val itemCost = product.purchasePriceMicros?.saturatedMultiply(units)
            val item = RecommendationItem(
                product.productId, product.name, units, servings, itemCost, itemNutrition,
                product.plannerType, fixed
            )
            return copy(
                items = items + item,
                nutrition = nutrition + itemNutrition,
                knownCostMicros = knownCostMicros.saturatedAdd(itemCost ?: 0L),
                unknownCostItems = unknownCostItems + if (itemCost == null) 1 else 0,
                totalUnits = totalUnits.saturatedAdd(units),
                drinkUnits = drinkUnits.saturatedAdd(if (product.plannerType == PlannerItemType.DRINK) units else 0)
            )
        }

        fun signature(): String = items.joinToString("|") { "${it.productId}:${it.purchaseUnits}" }
    }

    private companion object {
        const val MAX_TARGET_DERIVED_UNITS = 1_000_000
        val MINIMUM_LABELS = setOf("Protein", "Fiber")
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
    if (target <= 0.0) if (actual <= 0.0) 0.0 else 100.0 else ((actual - target) / target) * 100.0

private fun ratioOver(actual: Double, target: Double): Double =
    if (target <= 0.0) if (actual <= 0.0) 0.0 else actual else ((actual - target) / target).coerceAtLeast(0.0)

private fun normalizedDifference(actual: Double, target: Double): Double =
    if (target <= 0.0) if (actual <= 0.0) 0.0 else actual else abs(actual - target) / target

private fun shortfall(actual: Double, target: Double): Double =
    if (target <= 0.0) 0.0 else ((target - actual) / target).coerceAtLeast(0.0)

private fun incrementalOverage(before: Double, after: Double, target: Double): Double =
    (ratioOver(after, target) - ratioOver(before, target)).coerceAtLeast(0.0)

private fun Long.saturatedMultiply(value: Int): Long =
    runCatching { Math.multiplyExact(this, value.toLong()) }.getOrDefault(Long.MAX_VALUE)

private fun Long.saturatedAdd(value: Long): Long =
    runCatching { Math.addExact(this, value) }.getOrDefault(Long.MAX_VALUE)

private fun Int.saturatedAdd(value: Int): Int =
    runCatching { Math.addExact(this, value) }.getOrDefault(Int.MAX_VALUE)

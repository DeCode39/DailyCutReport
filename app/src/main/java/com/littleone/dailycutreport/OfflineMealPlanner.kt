package com.littleone.dailycutreport

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

class OfflineMealPlanner(
    private val beamWidth: Int = 24,
    private val fallbackBeamWidth: Int = 96,
    private val maxUnitsPerProduct: Int = 6,
    private val maxTotalUnits: Int = 20,
    private val maxDrinkUnits: Int = 2
) {
    fun generate(
        products: List<ProductEntity>,
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals
    ): RecommendationResult = generate(products, PlannerDayContext(consumed, spending), goals)

    fun generate(
        products: List<ProductEntity>,
        context: PlannerDayContext,
        goals: UserGoals
    ): RecommendationResult {
        goals.requireValid()
        require(goals.mode == GoalMode.CALORIE) { "Planner requires a resolved calorie allowance." }
        val eligible = products.filter(ProductEntity::includeInPlanner).sortedBy(ProductEntity::productId)
        val excludedFromPlanning = products.size - eligible.size
        val unpriced = eligible.count { it.purchasePriceMicros == null }
        val requiredFixed = eligible.filter { product ->
            product.alwaysIncludeInPlanner && !fixedRequirementSatisfied(product, context.loggedServingsByProductId)
        }
        val existingViolations = existingUpperViolations(context, goals)

        var fallbackReason: String
        if (existingViolations.isEmpty()) {
            val strictBlock = strictSetupBlock(requiredFixed, context.spending, goals)
            if (strictBlock == null) {
                val strict = strictPlans(eligible, requiredFixed, context, goals)
                if (strict.plans.isNotEmpty()) {
                    return RecommendationResult(
                        plans = strict.plans,
                        unpricedProducts = unpriced,
                        spendingIncomplete = context.spending.unknownEntries > 0,
                        excludedFromPlanningProducts = excludedFromPlanning
                    )
                }
                fallbackReason = strict.reason ?: "No complete strict plan fits the current values and limits."
            } else {
                fallbackReason = strictBlock
            }
        } else {
            fallbackReason = "The current log already exceeds ${existingViolations.joinToString { it.label }}."
        }

        return fallbackResult(
            eligible, context, goals, unpriced, excludedFromPlanning,
            fallbackReason, existingViolations
        )
    }

    private fun fixedRequirementSatisfied(product: ProductEntity, loggedServings: Map<String, Double>): Boolean =
        (loggedServings[product.productId] ?: 0.0) + FIXED_EPSILON >= product.purchaseUnitServings

    private fun strictSetupBlock(
        requiredFixed: List<ProductEntity>,
        spending: DailySpending,
        goals: UserGoals
    ): String? {
        if (goals.dailyBudgetMicros <= 0L) return "Strict planning requires a daily budget."
        if (requiredFixed.any { it.purchasePriceMicros == null }) return "Strict planning requires prices for fixed items."
        if (requiredFixed.size > maxTotalUnits) return "Strict planning allows at most $maxTotalUnits fixed purchase units."
        if (requiredFixed.count { it.plannerType == PlannerItemType.DRINK } > maxDrinkUnits) {
            return "Strict planning allows at most $maxDrinkUnits fixed drinks."
        }
        if (spending.knownTotalMicros > (goals.dailyBudgetMicros * 1.1).toLong()) {
            return "Current spending already exceeds the strict budget ceiling."
        }
        return null
    }

    private fun strictPlans(
        eligible: List<ProductEntity>,
        requiredFixed: List<ProductEntity>,
        context: PlannerDayContext,
        goals: UserGoals
    ): StrictOutcome {
        val allCandidates = eligible.asSequence()
            .filterNot(ProductEntity::alwaysIncludeInPlanner)
            .filter { it.purchasePriceMicros != null && it.purchasePriceMicros >= 0L && it.purchaseUnitServings > 0.0 }
            .toList()
        val fixedState = requiredFixed.fold(PlanState()) { state, product -> state.add(product, 1, fixed = true) }
        if (!withinHardCeilings(fixedState, context.consumed, context.spending, goals)) {
            return StrictOutcome(reason = "Required fixed items exceed a strict nutrition or budget ceiling.")
        }
        if (allCandidates.isEmpty() && requiredFixed.isEmpty()) {
            return StrictOutcome(reason = "Strict planning has no priced eligible products.")
        }

        var beam = listOf(fixedState)
        shortlist(allCandidates, context.consumed, goals).forEach { product ->
            val price = requireNotNull(product.purchasePriceMicros)
            val budgetHeadroom = (goals.dailyBudgetMicros * 1.1).toLong() - context.spending.knownTotalMicros
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
                    if (withinHardCeilings(next, context.consumed, context.spending, goals)) expanded += next
                }
            }
            beam = expanded.distinctBy(PlanState::signature)
                .map { ScoredState(it, evaluateStrict(it, context.consumed, context.spending, goals)) }
                .sortedWith(strictComparator)
                .take(beamWidth)
                .map(ScoredState::state)
        }

        val plans = beam.asSequence()
            .filter { it.items.isNotEmpty() }
            .distinctBy(PlanState::signature)
            .map { ScoredState(it, evaluateStrict(it, context.consumed, context.spending, goals)) }
            .filter { it.evaluation.complete }
            .sortedWith(strictComparator)
            .take(3)
            .map { it.state.toRecommendation(context, goals, RecommendationMode.STRICT) }
            .toList()
        return StrictOutcome(plans, reason = if (plans.isEmpty()) "No complete strict plan fits the current values and limits." else null)
    }

    private fun fallbackResult(
        eligible: List<ProductEntity>,
        context: PlannerDayContext,
        goals: UserGoals,
        unpriced: Int,
        excludedFromPlanning: Int,
        strictReason: String,
        existingViolations: List<ConstraintImpact>
    ): RecommendationResult {
        val needsMinimum = needsMinimum(context.consumed, goals)
        val fallback = balancedFallback(eligible, context, goals)
        val message = when {
            fallback != null -> "$strictReason Showing one best balanced option; strict fixed-item, drink, budget, upper-limit, and quantity limits are treated as penalties rather than hard blocks."
            !needsMinimum -> "$strictReason Protein and fiber minimums are already satisfied; no recovery suggestion is needed."
            else -> "$strictReason No eligible product can improve an unmet protein or fiber minimum."
        }
        return RecommendationResult(
            plans = listOfNotNull(fallback),
            unpricedProducts = unpriced,
            spendingIncomplete = context.spending.unknownEntries > 0 || (fallback?.unknownCostItems ?: 0) > 0,
            message = message,
            excludedFromPlanningProducts = excludedFromPlanning,
            existingViolations = existingViolations
        )
    }

    private fun balancedFallback(
        eligible: List<ProductEntity>,
        context: PlannerDayContext,
        goals: UserGoals
    ): RecommendationPlan? {
        val needsProtein = goals.proteinG > 0.0 && context.consumed.proteinG < goals.proteinG
        val needsFiber = goals.fiberG > 0.0 && context.consumed.fiberG < goals.fiberG
        if (!needsProtein && !needsFiber) return null
        val candidates = eligible.filter { product ->
            product.purchaseUnitServings > 0.0 &&
                (!product.alwaysIncludeInPlanner ||
                    !fixedRequirementSatisfied(product, context.loggedServingsByProductId)) &&
                ((needsProtein && product.proteinG > 0.0) || (needsFiber && product.fiberG > 0.0))
        }
        if (candidates.isEmpty()) return null

        var beam = listOf(PlanState())
        candidates.forEach { product ->
            val expanded = ArrayList<PlanState>(beam.size * 8)
            beam.forEach { state ->
                fallbackQuantityOptions(state, product, context.consumed, goals).forEach { units ->
                    expanded += if (units == 0) state else state.add(product, units)
                }
            }
            beam = expanded.distinctBy(PlanState::signature)
                .map { BalancedScoredState(it, evaluateBalanced(it, context, goals)) }
                .sortedWith(balancedComparator)
                .take(fallbackBeamWidth)
                .map(BalancedScoredState::state)
        }

        return beam.asSequence()
            .filter { it.items.isNotEmpty() }
            .filter {
                val projected = context.consumed + it.nutrition
                (needsProtein && projected.proteinG > context.consumed.proteinG) ||
                    (needsFiber && projected.fiberG > context.consumed.fiberG)
            }
            .distinctBy(PlanState::signature)
            .map { BalancedScoredState(it, evaluateBalanced(it, context, goals)) }
            .sortedWith(balancedComparator)
            .firstOrNull()
            ?.state
            ?.toRecommendation(context, goals, RecommendationMode.BALANCED_FALLBACK)
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

    private fun withinHardCeilings(
        state: PlanState,
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals
    ): Boolean {
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

    private val balancedComparator = compareBy<BalancedScoredState> { it.evaluation.score }
        .thenBy { it.state.knownCostMicros }
        .thenBy { it.state.totalUnits }
        .thenBy { it.state.items.size }
        .thenBy { it.state.signature() }

    private fun evaluateStrict(
        state: PlanState,
        consumed: NutritionSummary,
        spending: DailySpending,
        goals: UserGoals
    ): Evaluation {
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
        return Evaluation(
            misses == 0, projected <= goals.dailyBudgetMicros, misses,
            calorieDeviation + proteinShortfall + fiberShortfall + overages.sum() + budgetOverage * 2.0
        )
    }

    private fun evaluateBalanced(state: PlanState, context: PlannerDayContext, goals: UserGoals): BalancedEvaluation {
        val before = context.consumed
        val after = before + state.nutrition
        val targets = goals.targetsFor(null)
        val upperDamage = incrementalOverage(before.sodiumMg, after.sodiumMg, targets.sodiumMg) +
            incrementalOverage(before.carbsG, after.carbsG, targets.carbsG) +
            incrementalOverage(before.fatG, after.fatG, targets.fatG) +
            incrementalOverage(before.sugarG, after.sugarG, targets.sugarG) +
            incrementalOverage(before.saturatedFatG, after.saturatedFatG, targets.saturatedFatG)
        val projectedSpending = context.spending.knownTotalMicros + state.knownCostMicros
        val budgetDamage = if (goals.dailyBudgetMicros > 0L) {
            incrementalOverage(
                context.spending.knownTotalMicros.toDouble(), projectedSpending.toDouble(),
                goals.dailyBudgetMicros.toDouble()
            )
        } else 0.0
        val score = shortfall(after.proteinG, targets.proteinG) + shortfall(after.fiberG, targets.fiberG) +
            normalizedDifference(after.calories, targets.calories) + upperDamage + budgetDamage * 2.0 +
            state.unknownCostUnits * UNKNOWN_COST_PENALTY
        return BalancedEvaluation(score)
    }

    private fun PlanState.toRecommendation(
        context: PlannerDayContext,
        goals: UserGoals,
        mode: RecommendationMode
    ): RecommendationPlan {
        val projectedNutrition = context.consumed + nutrition
        val projectedSpending = context.spending.knownTotalMicros + knownCostMicros
        val evaluation = evaluateStrict(this, context.consumed, context.spending, goals)
        val impacts = constraintImpacts(context, projectedNutrition, projectedSpending, goals)
        val unmetMinimums = impacts.filter { it.label in MINIMUM_LABELS && it.projected < it.target }
        val explanation = if (mode == RecommendationMode.BALANCED_FALLBACK) {
            "Balanced recovery option trading off protein and fiber shortfalls against calories, upper-limit damage, and budget excess."
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
            impacts = impacts,
            explanation = explanation,
            unknownCostItems = unknownCostItems,
            mode = mode,
            unmetMinimums = unmetMinimums
        )
    }

    private data class StrictOutcome(
        val plans: List<RecommendationPlan> = emptyList(),
        val reason: String? = null
    )

    private data class Evaluation(val complete: Boolean, val withinBudget: Boolean, val misses: Int, val penalty: Double)
    private data class ScoredState(val state: PlanState, val evaluation: Evaluation)
    private data class BalancedEvaluation(val score: Double)
    private data class BalancedScoredState(val state: PlanState, val evaluation: BalancedEvaluation)

    private data class PlanState(
        val items: List<RecommendationItem> = emptyList(),
        val nutrition: NutritionSummary = NutritionSummary(),
        val knownCostMicros: Long = 0L,
        val unknownCostItems: Int = 0,
        val unknownCostUnits: Int = 0,
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
                unknownCostUnits = unknownCostUnits.saturatedAdd(if (itemCost == null) units else 0),
                totalUnits = totalUnits.saturatedAdd(units),
                drinkUnits = drinkUnits.saturatedAdd(if (product.plannerType == PlannerItemType.DRINK) units else 0)
            )
        }

        fun signature(): String = items.joinToString("|") { "${it.productId}:${it.purchaseUnits}" }
    }

    private companion object {
        const val MAX_TARGET_DERIVED_UNITS = 1_000_000
        const val FIXED_EPSILON = 1e-6
        const val UNKNOWN_COST_PENALTY = 0.25
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

private fun existingUpperViolations(context: PlannerDayContext, goals: UserGoals): List<ConstraintImpact> =
    constraintImpacts(context, context.consumed, context.spending.knownTotalMicros, goals).filter { impact ->
        impact.label !in setOf("Protein", "Fiber") && impact.target > 0.0 && impact.percentDifference > 10.0
    }

private fun constraintImpacts(
    context: PlannerDayContext,
    projectedNutrition: NutritionSummary,
    projectedSpendingMicros: Long,
    goals: UserGoals
): List<ConstraintImpact> {
    val targets = goals.targetsFor(null)
    return listOf(
        targetImpact("Calories", context.consumed.calories, projectedNutrition.calories, targets.calories, upper = true),
        targetImpact("Protein", context.consumed.proteinG, projectedNutrition.proteinG, targets.proteinG, upper = false),
        targetImpact("Fiber", context.consumed.fiberG, projectedNutrition.fiberG, targets.fiberG, upper = false),
        targetImpact("Sodium", context.consumed.sodiumMg, projectedNutrition.sodiumMg, targets.sodiumMg, upper = true),
        targetImpact("Carbs", context.consumed.carbsG, projectedNutrition.carbsG, targets.carbsG, upper = true),
        targetImpact("Fat", context.consumed.fatG, projectedNutrition.fatG, targets.fatG, upper = true),
        targetImpact("Sugar", context.consumed.sugarG, projectedNutrition.sugarG, targets.sugarG, upper = true),
        targetImpact(
            "Saturated fat", context.consumed.saturatedFatG, projectedNutrition.saturatedFatG,
            targets.saturatedFatG, upper = true
        ),
        targetImpact(
            "Budget",
            context.spending.knownTotalMicros.toDouble() / MONEY_MICROS_PER_UNIT,
            projectedSpendingMicros.toDouble() / MONEY_MICROS_PER_UNIT,
            goals.dailyBudgetMicros.toDouble() / MONEY_MICROS_PER_UNIT,
            upper = true,
            disabled = goals.dailyBudgetMicros <= 0L
        )
    )
}

private fun targetImpact(
    label: String,
    baseline: Double,
    projected: Double,
    target: Double,
    upper: Boolean,
    disabled: Boolean = false
): ConstraintImpact {
    val difference = if (disabled) 0.0 else percentageDifference(projected, target)
    val within = when {
        disabled -> true
        label == "Calories" -> abs(difference) <= 10.0
        upper -> difference <= 10.0
        else -> difference >= -10.0
    }
    return ConstraintImpact(label, baseline, projected, target, difference, within)
}

private fun needsMinimum(nutrition: NutritionSummary, goals: UserGoals): Boolean =
    (goals.proteinG > 0.0 && nutrition.proteinG < goals.proteinG) ||
        (goals.fiberG > 0.0 && nutrition.fiberG < goals.fiberG)

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

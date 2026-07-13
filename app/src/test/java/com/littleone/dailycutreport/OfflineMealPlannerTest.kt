package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMealPlannerTest {
    private val planner = OfflineMealPlanner()
    private val goals = UserGoals(
        calories = 1000.0, proteinG = 80.0, fiberG = 10.0,
        sodiumMg = 2000.0, carbsG = 200.0, fatG = 80.0, sugarG = 80.0, saturatedFatG = 30.0,
        dailyBudgetMicros = 100_000_000L
    )

    @Test fun usesWholePurchaseUnitsAndExcludesUnpricedProducts() {
        val priced = ProductEntity(
            productId = "meal", name = "Meal", calories = 250.0, proteinG = 20.0, fiberG = 2.5,
            purchasePriceMicros = 20_000_000L, purchaseUnitServings = 2.0
        )
        val result = planner.generate(listOf(priced, ProductEntity("unknown", name = "Unknown")), NutritionSummary(), DailySpending(budgetMicros = goals.dailyBudgetMicros), goals)

        assertEquals(1, result.unpricedProducts)
        assertTrue(result.plans.isNotEmpty())
        assertTrue(result.plans.all { plan -> plan.items.all { it.servings % 2.0 == 0.0 } })
    }

    @Test fun withinBudgetPlansRankBeforeAllowedTenPercentOverage() {
        val exact = ProductEntity("exact", name = "Exact", calories = 900.0, proteinG = 75.0, fiberG = 9.0, purchasePriceMicros = 100_000_000L)
        val over = ProductEntity("over", name = "Over", calories = 1000.0, proteinG = 80.0, fiberG = 10.0, purchasePriceMicros = 105_000_000L)
        val result = planner.generate(listOf(exact, over), NutritionSummary(), DailySpending(budgetMicros = goals.dailyBudgetMicros), goals)

        assertTrue(result.plans.first().withinBudget)
        result.plans.firstOrNull { !it.withinBudget }?.let { overBudget ->
            assertTrue(overBudget.projectedSpendingMicros <= (goals.dailyBudgetMicros * 1.1).toLong())
        }
    }

    @Test fun plannerIsDeterministic() {
        val products = (1..20).map { index ->
            ProductEntity("p$index", name = "P$index", calories = 100.0 + index, proteinG = 10.0, fiberG = 1.0, purchasePriceMicros = 5_000_000L + index)
        }
        val first = planner.generate(products, NutritionSummary(), DailySpending(budgetMicros = goals.dailyBudgetMicros), goals)
        val second = planner.generate(products.reversed(), NutritionSummary(), DailySpending(budgetMicros = goals.dailyBudgetMicros), goals)
        assertEquals(first.plans, second.plans)
    }

    @Test fun excludesDisabledProductsAndAlwaysIncludesFixedItem() {
        val disabled = ProductEntity(
            "disabled", name = "Disabled", calories = 900.0, proteinG = 80.0,
            purchasePriceMicros = 1L, includeInPlanner = false
        )
        val fixed = ProductEntity(
            "fixed", name = "Fixed meal", calories = 300.0, proteinG = 25.0, fiberG = 2.0,
            purchasePriceMicros = 20_000_000L, alwaysIncludeInPlanner = true
        )
        val optional = ProductEntity(
            "optional", name = "Optional", calories = 200.0, proteinG = 20.0, fiberG = 2.0,
            purchasePriceMicros = 10_000_000L
        )

        val result = planner.generate(listOf(disabled, fixed, optional), NutritionSummary(), DailySpending(budgetMicros = goals.dailyBudgetMicros), goals)

        assertEquals(1, result.excludedFromPlanningProducts)
        assertTrue(result.plans.isNotEmpty())
        assertTrue(result.plans.all { plan -> plan.items.single { it.productId == "fixed" }.fixed })
        assertTrue(result.plans.none { plan -> plan.items.any { it.productId == "disabled" } })
    }

    @Test fun limitsRecommendedDrinksToTwoPurchaseUnits() {
        val drinks = (1..4).map { index ->
            ProductEntity(
                "drink$index", name = "Drink $index", calories = 100.0, proteinG = 10.0,
                purchasePriceMicros = 5_000_000L, plannerItemType = PlannerItemType.DRINK.name
            )
        }
        val drinkGoals = goals.copy(calories = 200.0, proteinG = 20.0, fiberG = 0.0)
        val result = planner.generate(drinks, NutritionSummary(), DailySpending(budgetMicros = drinkGoals.dailyBudgetMicros), drinkGoals)

        assertTrue(result.plans.isNotEmpty())
        assertTrue(result.plans.all { plan ->
            plan.items.filter { it.itemType == PlannerItemType.DRINK }.sumOf { it.purchaseUnits } <= 2
        })
    }

    @Test fun twoHundredProductCatalogCompletesWithinUnitTestBudget() {
        val products = (1..200).map { index ->
            ProductEntity(
                "p$index", name = "P$index", calories = 80.0 + index % 40,
                proteinG = 5.0 + index % 12, fiberG = (index % 5).toDouble(),
                sodiumMg = (index % 200).toDouble(), carbsG = 10.0, fatG = 3.0,
                purchasePriceMicros = 2_000_000L + index * 10_000L
            )
        }
        planner.generate(products.take(2), NutritionSummary(), DailySpending(budgetMicros = goals.dailyBudgetMicros), goals)
        val started = System.nanoTime()
        planner.generate(products, NutritionSummary(), DailySpending(budgetMicros = goals.dailyBudgetMicros), goals)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue("Planner took ${elapsedMillis}ms", elapsedMillis < 500L)
    }

    @Test fun existingUpperLimitViolationIsNamedAndReturnsOneMinimumTargetFallback() {
        val proteinAndFiber = ProductEntity(
            "protein", name = "Protein bowl", calories = 200.0, proteinG = 40.0, fiberG = 5.0,
            purchasePriceMicros = 20_000_000L
        )
        val consumed = NutritionSummary(sodiumMg = 2_500.0)

        val result = planner.generate(
            listOf(proteinAndFiber), consumed,
            DailySpending(budgetMicros = goals.dailyBudgetMicros), goals
        )

        assertTrue(result.blockingViolations.any { it.label == "Sodium" })
        assertEquals(1, result.plans.size)
        assertTrue(result.plans.single().minimumTargetFallback)
        assertTrue(result.plans.single().nutrition.proteinG >= goals.proteinG)
        assertTrue(result.plans.single().nutrition.fiberG >= goals.fiberG)
    }

    @Test fun impossibleCompletePlanReturnsOnlyBestFallbackAndListsProjectedViolation() {
        val neededProtein = ProductEntity(
            "protein", name = "Protein", calories = 150.0, proteinG = 80.0, fiberG = 10.0,
            purchasePriceMicros = 20_000_000L
        )
        val consumed = NutritionSummary(calories = 1_000.0)

        val result = planner.generate(
            listOf(neededProtein), consumed,
            DailySpending(budgetMicros = goals.dailyBudgetMicros), goals
        )

        assertEquals(1, result.plans.size)
        assertTrue(result.plans.single().minimumTargetFallback)
        assertTrue(result.blockingViolations.any { it.label == "Calories" })
    }

    @Test fun unrestrictedFallbackIgnoresFixedDrinkBudgetAndStrictQuantityLimits() {
        val fallbackGoals = goals.copy(
            calories = 10.0, proteinG = 25.0, fiberG = 0.0, dailyBudgetMicros = 1L
        )
        val fixed = ProductEntity(
            "fixed", name = "Fixed", calories = 100.0, proteinG = 1.0,
            purchasePriceMicros = 1_000_000L, alwaysIncludeInPlanner = true
        )
        val drink = ProductEntity(
            "drink", name = "Unpriced drink", calories = 1.0, proteinG = 1.0,
            purchasePriceMicros = null, plannerItemType = PlannerItemType.DRINK.name
        )
        val disabled = ProductEntity(
            "disabled", name = "Disabled perfect item", calories = 0.0, proteinG = 100.0,
            purchasePriceMicros = 0L, includeInPlanner = false
        )

        val result = planner.generate(
            listOf(fixed, drink, disabled), NutritionSummary(),
            DailySpending(budgetMicros = fallbackGoals.dailyBudgetMicros), fallbackGoals
        )

        val plan = result.plans.single()
        assertTrue(plan.minimumTargetFallback)
        assertEquals(listOf("drink"), plan.items.map { it.productId })
        assertEquals(25, plan.items.single().purchaseUnits)
        assertEquals(null, plan.items.single().costMicros)
        assertEquals(1, plan.unknownCostItems)
        assertTrue(plan.items.single().purchaseUnits > 20)
    }

    @Test fun unrestrictedFallbackRespectsPhysicalPurchaseUnitServings() {
        val product = ProductEntity(
            "two-pack", name = "Two pack", calories = 100.0, proteinG = 20.0, fiberG = 2.5,
            purchasePriceMicros = null, purchaseUnitServings = 2.0
        )

        val plan = planner.generate(
            listOf(product), NutritionSummary(), DailySpending(budgetMicros = goals.dailyBudgetMicros), goals
        ).plans.single()

        assertTrue(plan.minimumTargetFallback)
        assertEquals(2, plan.items.single().purchaseUnits)
        assertEquals(4.0, plan.items.single().servings, 0.0)
    }
}

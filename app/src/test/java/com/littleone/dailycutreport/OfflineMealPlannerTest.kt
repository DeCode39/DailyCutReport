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

    @Test fun existingUpperLimitViolationIsNamedAndReturnsBalancedRecovery() {
        val proteinAndFiber = ProductEntity(
            "protein", name = "Protein bowl", calories = 200.0, proteinG = 40.0, fiberG = 5.0,
            purchasePriceMicros = 20_000_000L
        )
        val consumed = NutritionSummary(sodiumMg = 2_500.0)

        val result = planner.generate(
            listOf(proteinAndFiber), consumed,
            DailySpending(budgetMicros = goals.dailyBudgetMicros), goals
        )

        assertTrue(result.existingViolations.any { it.label == "Sodium" && it.baseline == 2_500.0 })
        assertEquals(1, result.plans.size)
        assertTrue(result.plans.single().balancedFallback)
        assertTrue(result.plans.single().nutrition.proteinG > consumed.proteinG)
        assertTrue(result.plans.single().nutrition.fiberG > consumed.fiberG)
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
        assertTrue(result.plans.single().balancedFallback)
        assertTrue(result.plans.single().impacts.any { it.label == "Calories" && !it.withinTolerance })
    }

    @Test fun balancedFallbackIgnoresFixedDrinkBudgetAndStrictQuantityLimits() {
        val fallbackGoals = goals.copy(
            calories = 9.0, proteinG = 9.0, fiberG = 0.0, dailyBudgetMicros = 1L
        )
        val fixed = ProductEntity(
            "fixed", name = "Fixed", calories = 100.0, proteinG = 1.0,
            purchasePriceMicros = 1_000_000L, alwaysIncludeInPlanner = true
        )
        val drink = ProductEntity(
            "drink", name = "Drink", calories = 1.0, proteinG = 1.0,
            purchasePriceMicros = 0L, plannerItemType = PlannerItemType.DRINK.name
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
        assertEquals(9, plan.items.single().purchaseUnits)
        assertEquals(0L, plan.items.single().costMicros)
        assertEquals(0, plan.unknownCostItems)
        assertTrue(plan.items.single().purchaseUnits > maxOf(6, 2))
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

    @Test fun loggedFixedPurchaseUnitIsAlreadySatisfied() {
        val fixed = ProductEntity(
            "fixed", name = "Fixed", calories = 300.0, proteinG = 25.0, fiberG = 2.0,
            purchasePriceMicros = 20_000_000L, alwaysIncludeInPlanner = true
        )
        val optional = ProductEntity(
            "optional", name = "Optional", calories = 700.0, proteinG = 55.0, fiberG = 8.0,
            purchasePriceMicros = 20_000_000L
        )
        val context = PlannerDayContext(
            consumed = NutritionSummary(calories = 300.0, proteinG = 25.0, fiberG = 2.0),
            spending = DailySpending(knownTotalMicros = 20_000_000L, budgetMicros = goals.dailyBudgetMicros),
            loggedServingsByProductId = mapOf("fixed" to 1.0)
        )

        val plan = planner.generate(listOf(fixed, optional), context, goals).plans.first()

        assertEquals(RecommendationMode.STRICT, plan.mode)
        assertTrue(plan.items.none { it.productId == "fixed" })
        assertEquals(listOf("optional"), plan.items.map { it.productId })
    }

    @Test fun configuredFixedUnitsRecommendOnlyWholeUnitsStillRequired() {
        val fixed = ProductEntity(
            "fixed", name = "Fixed multipack", calories = 100.0, proteinG = 8.0,
            purchasePriceMicros = 10_000_000L, purchaseUnitServings = 2.0,
            alwaysIncludeInPlanner = true, fixedPurchaseUnits = 3
        )
        val result = planner.generate(
            listOf(fixed),
            PlannerDayContext(
                consumed = NutritionSummary(calories = 250.0, proteinG = 20.0),
                spending = DailySpending(budgetMicros = goals.dailyBudgetMicros),
                loggedServingsByProductId = mapOf("fixed" to 2.5)
            ),
            goals.copy(calories = 650.0, proteinG = 50.0, fiberG = 0.0)
        )

        val item = result.plans.first().items.single { it.productId == "fixed" }
        assertEquals(2, item.purchaseUnits)
        assertEquals(4.0, item.servings, 0.0)
        assertTrue(item.fixed)
    }

    @Test fun fixedDrinksDoNotConsumeTwoAdditionalDrinkUnits() {
        val fixedDrink = ProductEntity(
            "fixed-drink", name = "Fixed drink", calories = 100.0, proteinG = 10.0,
            purchasePriceMicros = 1_000_000L, plannerItemType = PlannerItemType.DRINK.name,
            alwaysIncludeInPlanner = true, fixedPurchaseUnits = 2
        )
        val optionalDrink = ProductEntity(
            "optional-drink", name = "Optional drink", calories = 100.0, proteinG = 10.0,
            purchasePriceMicros = 1_000_000L, plannerItemType = PlannerItemType.DRINK.name
        )
        val result = planner.generate(
            listOf(fixedDrink, optionalDrink),
            NutritionSummary(),
            DailySpending(budgetMicros = goals.dailyBudgetMicros),
            goals.copy(calories = 400.0, proteinG = 40.0, fiberG = 0.0)
        )

        val plan = result.plans.first()
        assertEquals(2, plan.items.single { it.productId == "fixed-drink" }.purchaseUnits)
        assertEquals(2, plan.items.single { it.productId == "optional-drink" }.purchaseUnits)
        assertEquals(4, plan.items.sumOf { it.purchaseUnits })
    }

    @Test fun partialOrDetachedFixedLogStillRequiresOneWholeUnit() {
        val fixed = ProductEntity(
            "fixed", name = "Fixed", calories = 1_000.0, proteinG = 80.0, fiberG = 10.0,
            purchasePriceMicros = 20_000_000L, alwaysIncludeInPlanner = true
        )

        listOf(mapOf("fixed" to 0.5), mapOf("detached" to 10.0)).forEach { logged ->
            val result = planner.generate(
                listOf(fixed),
                PlannerDayContext(
                    NutritionSummary(),
                    DailySpending(budgetMicros = goals.dailyBudgetMicros),
                    logged
                ),
                goals
            )
            val item = result.plans.single().items.single()
            assertTrue(item.fixed)
            assertEquals(1, item.purchaseUnits)
            assertEquals(1.0, item.servings, 0.0)
        }
    }

    @Test fun julySixteenRegressionBalancesDamageAndKeepsDisplayedValuesConsistent() {
        val regressionGoals = goals.copy(
            calories = 1_804.4383413011487, proteinG = 120.0, fiberG = 20.0,
            sodiumMg = 2_000.0, carbsG = 150.0, fatG = 60.0, sugarG = 50.0,
            saturatedFatG = 15.0, dailyBudgetMicros = 350_000_000L
        )
        val fixedFiber = ProductEntity(
            "fixed-fiber", name = "Fixed fiber", calories = 70.0, sodiumMg = 81.0,
            carbsG = 25.0, sugarG = 9.2, fiberG = 12.6,
            purchasePriceMicros = 42_000_000L, alwaysIncludeInPlanner = true
        )
        val proteinFood = ProductEntity(
            "protein-food", name = "Protein food", calories = 139.0, proteinG = 14.3,
            sodiumMg = 382.0, carbsG = 11.4, fatG = 4.1, sugarG = 0.2,
            saturatedFatG = 1.3, purchasePriceMicros = 45_000_000L
        )
        val context = PlannerDayContext(
            consumed = NutritionSummary(
                calories = 862.0, proteinG = 49.4, sodiumMg = 544.0, carbsG = 116.4,
                fatG = 28.8, sugarG = 48.8, fiberG = 25.2, saturatedFatG = 17.1,
                entries = 3
            ),
            spending = DailySpending(knownTotalMicros = 213_000_000L, budgetMicros = 350_000_000L),
            loggedServingsByProductId = mapOf("fixed-fiber" to 2.0)
        )

        val result = planner.generate(listOf(fixedFiber, proteinFood), context, regressionGoals)
        val plan = result.plans.single()

        assertTrue(plan.balancedFallback)
        assertEquals(listOf("protein-food"), plan.items.map { it.productId })
        assertEquals(3, plan.items.single().purchaseUnits)
        assertEquals(348_000_000L, plan.projectedSpendingMicros)
        assertTrue(result.existingViolations.any {
            it.label == "Saturated fat" && it.baseline == 17.1 && it.projected == 17.1
        })
        assertTrue(plan.impacts.any {
            it.label == "Sugar" && it.baseline == 48.8 && kotlin.math.abs(it.projected - 49.4) < 0.0001
        })
    }

    @Test fun balancedFallbackPrefersKnownCostWhenNutritionIsEqual() {
        val consumed = NutritionSummary(sodiumMg = 2_500.0)
        val priced = ProductEntity(
            "priced", name = "Priced", calories = 200.0, proteinG = 40.0, fiberG = 5.0,
            purchasePriceMicros = 20_000_000L
        )
        val unknown = priced.copy(productId = "unknown", name = "Unknown", purchasePriceMicros = null)

        val plan = planner.generate(
            listOf(unknown, priced), consumed,
            DailySpending(budgetMicros = goals.dailyBudgetMicros), goals
        ).plans.single()

        assertTrue(plan.balancedFallback)
        assertEquals(listOf("priced"), plan.items.map { it.productId })
    }

    @Test fun balancedFallbackDoesNotRepeatASatisfiedFixedProduct() {
        val fixed = ProductEntity(
            "fixed", name = "Fixed", calories = 100.0, proteinG = 100.0,
            purchasePriceMicros = 1L, alwaysIncludeInPlanner = true
        )
        val optional = ProductEntity(
            "optional", name = "Optional", calories = 200.0, proteinG = 20.0,
            purchasePriceMicros = 20_000_000L
        )
        val result = planner.generate(
            listOf(fixed, optional),
            PlannerDayContext(
                consumed = NutritionSummary(sodiumMg = 2_500.0),
                spending = DailySpending(budgetMicros = goals.dailyBudgetMicros),
                loggedServingsByProductId = mapOf("fixed" to 1.0)
            ),
            goals
        )

        assertEquals(listOf("optional"), result.plans.single().items.map { it.productId })
    }

    @Test fun noFallbackIsNeededWhenProteinAndFiberAreAlreadySatisfied() {
        val consumed = NutritionSummary(proteinG = goals.proteinG, fiberG = goals.fiberG, sodiumMg = 2_500.0)

        val result = planner.generate(
            listOf(ProductEntity("food", name = "Food", proteinG = 20.0, purchasePriceMicros = 1L)),
            consumed, DailySpending(budgetMicros = goals.dailyBudgetMicros), goals
        )

        assertTrue(result.plans.isEmpty())
        assertTrue(result.message.orEmpty().contains("already satisfied"))
    }
}

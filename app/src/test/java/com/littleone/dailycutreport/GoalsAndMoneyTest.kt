package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GoalsAndMoneyTest {
    @Test fun deficitModeDerivesStableCalorieAllowance() {
        val goals = UserGoals(mode = GoalMode.DEFICIT, expectedBurnCalories = 2400.0, desiredDeficitCalories = 500.0)
        assertEquals(1900.0, goals.effectiveCalorieTarget, 0.0)
        assertEquals(1900.0, goals.targets.calories, 0.0)
    }

    @Test fun invalidDeficitIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            UserGoals(mode = GoalMode.DEFICIT, expectedBurnCalories = 400.0, desiredDeficitCalories = 500.0).requireValid()
        }
    }

    @Test fun moneyParsingIsExactAndZeroMeansFree() {
        assertEquals(12_345_678L, parseMoneyMicros("12.345678"))
        assertEquals(0L, parseMoneyMicros("0"))
        assertEquals(null, parseMoneyMicros(""))
    }

    @Test fun explicitPaidTotalOverridesCatalogEstimate() {
        assertEquals(15_000_000L, LoggedCost(10_000_000L, 15_000_000L).effectiveTotalMicros(2.0))
        assertEquals(0L, LoggedCost(10_000_000L, 0L).effectiveTotalMicros(2.0))
        assertEquals(20_000_000L, LoggedCost(10_000_000L, null).effectiveTotalMicros(2.0))
    }
}

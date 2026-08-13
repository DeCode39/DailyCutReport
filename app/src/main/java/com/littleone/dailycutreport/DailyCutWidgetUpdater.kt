package com.littleone.dailycutreport

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import java.time.LocalDate

object DailyCutWidgetUpdater {
    suspend fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(ComponentName(appContext, QuickScanWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val state = todayState(appContext)
        ids.forEach { id ->
            manager.updateAppWidget(
                id,
                QuickScanWidgetProvider.widgetViews(
                    appContext, state,
                    QuickScanWidgetProvider.isExpanded(manager.getAppWidgetOptions(id))
                )
            )
        }
    }

    private suspend fun todayState(context: Context): TodayWidgetState {
        val dao = NutritionDatabase.get(context).nutritionDao()
        val today = LocalDate.now().toString()
        val report = dao.dailyReport(today)
        val forecast = dao.metadata(burnForecastMetadataKey(LocalDate.now()))?.let(BurnForecastCodec::decode)
        val totals = dao.totalsForDate(today)
        val goals = (dao.userGoals() ?: UserGoalsEntity()).toDomain()
        val burn = report?.manualBurnCalories
            ?: report?.totalCalories?.takeIf { it > 0.0 }
            ?: report?.activeCalories?.takeIf { it > 0.0 }
            ?: 0.0
        val food = report?.manualFoodCalories
            ?: totals.calories.takeIf { totals.entries > 0 }
            ?: report?.nutritionCalories?.takeIf { report.nutritionRecords > 0 }
            ?: 0.0
        val allowance = goals.calorieAllowance(burn.takeIf { it > 0.0 })
        val balanceLabel = if (goals.mode == GoalMode.CALORIE) {
            "${formatCalories(food)} / ${formatCalories(goals.calories)} kcal"
        } else when (val balance = calculateEnergyBalance(burn, food)) {
            EnergyBalance.Unavailable -> "Burn unavailable"
            is EnergyBalance.Cut -> "−${formatCalories(balance.calories)} kcal deficit"
            is EnergyBalance.Surplus -> "+${formatCalories(balance.calories)} kcal surplus"
            is EnergyBalance.Maintenance -> "Maintenance"
        }
        val spending = dao.spendingForDate(today)
        fun percent(value: Double, target: Double) =
            if (target <= 0.0) 0 else ((value / target) * 100).toInt().coerceIn(0, 100)
        return TodayWidgetState(
            balanceLabel,
            allowance?.let {
                "${formatCalories(food)} / ${formatCalories(it)} kcal" +
                    if (forecast?.isEstimate == true) " · ${forecast.confidence.name.lowercase()}" else ""
            } ?: "Intake ${formatCalories(food)} kcal",
            allowance?.let { percent(food, it) } ?: 0,
            "${formatDecimal(totals.proteinG)} / ${formatDecimal(goals.proteinG)} g protein",
            percent(totals.proteinG, goals.proteinG),
            if (goals.dailyBudgetMicros > 0L) {
                "${formatMoney(spending.knownTotalMicros, goals.currencyCode)} / ${formatMoney(goals.dailyBudgetMicros, goals.currencyCode)}"
            } else "Budget not set",
            if (goals.dailyBudgetMicros > 0L) percent(spending.knownTotalMicros.toDouble(), goals.dailyBudgetMicros.toDouble()) else 0
        )
    }
}

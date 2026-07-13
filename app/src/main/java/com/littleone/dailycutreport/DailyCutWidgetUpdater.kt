package com.littleone.dailycutreport

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import java.time.LocalDate
import kotlin.math.roundToInt

object DailyCutWidgetUpdater {
    suspend fun updateAll(context: Context) {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val ids = manager.getAppWidgetIds(ComponentName(appContext, QuickScanWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val label = todayDeficitLabel(appContext)
        ids.forEach { id ->
            manager.updateAppWidget(id, QuickScanWidgetProvider.widgetViews(appContext, label))
        }
    }

    private suspend fun todayDeficitLabel(context: Context): String {
        val dao = NutritionDatabase.get(context).nutritionDao()
        val today = LocalDate.now().toString()
        val report = dao.dailyReport(today)
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
        if (goals.mode == GoalMode.CALORIE) {
            return "${food.roundToInt()} / ${goals.calories.roundToInt()} kcal"
        }
        return when (val balance = calculateEnergyBalance(burn, food)) {
            EnergyBalance.Unavailable -> "Open app to load burn"
            is EnergyBalance.Cut -> "−${balance.calories.roundToInt()} / ${goals.desiredDeficitCalories.roundToInt()} kcal"
            is EnergyBalance.Surplus -> "+${balance.calories.roundToInt()} kcal surplus"
            is EnergyBalance.Maintenance -> "Maintenance"
        }
    }
}

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

data class HealthWriteSummary(val recordsWritten: Int, val date: LocalDate)

data class ManualOverrides(
    val foodCalories: Double? = null,
    val proteinG: Double? = null,
    val sodiumMg: Double? = null,
    val burnCalories: Double? = null,
    val notes: String = ""
)

enum class DayVerdict(val label: String) {
    CUT("Cut day"),
    SURPLUS("Surplus day"),
    MAINTENANCE("Maintenance-ish")
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

    val verdict: DayVerdict
        get() = when {
            deficitCalories >= 300.0 -> DayVerdict.CUT
            deficitCalories <= -200.0 -> DayVerdict.SURPLUS
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
}

data class ProductWithExtras(
    val product: ProductEntity,
    val extras: List<ProductExtraNutrientEntity> = emptyList()
)

data class FoodLogEdit(
    val id: Long,
    val quantity: Double,
    val servingLabel: String,
    val caloriesPerServing: Double,
    val proteinGPerServing: Double,
    val sodiumMgPerServing: Double,
    val carbsGPerServing: Double,
    val fatGPerServing: Double,
    val sugarGPerServing: Double,
    val fiberGPerServing: Double,
    val saturatedFatGPerServing: Double
)

fun FoodLogSnapshot.quantityEdit(quantity: Double) = FoodLogEdit(
    id = id,
    quantity = quantity,
    servingLabel = servingLabel,
    caloriesPerServing = caloriesPerServing,
    proteinGPerServing = proteinGPerServing,
    sodiumMgPerServing = sodiumMgPerServing,
    carbsGPerServing = carbsGPerServing,
    fatGPerServing = fatGPerServing,
    sugarGPerServing = sugarGPerServing,
    fiberGPerServing = fiberGPerServing,
    saturatedFatGPerServing = saturatedFatGPerServing
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

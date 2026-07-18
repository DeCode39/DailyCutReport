package com.littleone.dailycutreport

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "products", indices = [Index(value = ["barcode"], unique = true)])
data class ProductEntity(
    @PrimaryKey val productId: String,
    val barcode: String? = null,
    val name: String,
    val brand: String = "",
    val servingLabel: String = "1 serving",
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val sugarG: Double = 0.0,
    val fiberG: Double = 0.0,
    val saturatedFatG: Double = 0.0,
    val purchasePriceMicros: Long? = null,
    val purchaseUnitServings: Double = 1.0,
    val includeInPlanner: Boolean = true,
    val plannerItemType: String = PlannerItemType.FOOD.name,
    val alwaysIncludeInPlanner: Boolean = false,
    val fixedPurchaseUnits: Int = 1,
    val favorite: Boolean = false,
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "product_extra_nutrients",
    primaryKeys = ["productId", "name"],
    foreignKeys = [ForeignKey(
        entity = ProductEntity::class,
        parentColumns = ["productId"],
        childColumns = ["productId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("productId")]
)
data class ProductExtraNutrientEntity(
    val productId: String,
    val name: String,
    val value: Double,
    val unit: String
)

@Entity(tableName = "daily_reports")
data class DailyReportEntity(
    @PrimaryKey val date: String,
    val steps: Long = 0,
    val distanceKm: Double = 0.0,
    val activeCalories: Double = 0.0,
    val totalCalories: Double = 0.0,
    val exerciseSessions: Int = 0,
    val exerciseMinutes: Long = 0,
    val nutritionCalories: Double = 0.0,
    val nutritionProteinG: Double = 0.0,
    val nutritionSodiumMg: Double = 0.0,
    val nutritionRecords: Int = 0,
    val healthConnectStatus: String = "Not loaded",
    val manualFoodCalories: Double? = null,
    val manualProteinG: Double? = null,
    val manualSodiumMg: Double? = null,
    val manualBurnCalories: Double? = null,
    val notes: String = "",
    val savedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_goals")
data class UserGoalsEntity(
    @PrimaryKey val id: Int = 1,
    val mode: String = GoalMode.CALORIE.name,
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
)

@Entity(tableName = "health_profile")
data class HealthProfileEntity(
    @PrimaryKey val id: Int = 1,
    val weightUnit: String = WeightUnit.KG.name,
    val targetWeightKg: Double? = null
)

@Entity(tableName = "weight_entries", indices = [Index("date"), Index("recordedAtEpochMs")])
data class WeightEntryEntity(
    @PrimaryKey val entryId: String,
    val date: String,
    val recordedAtEpochMs: Long,
    val weightKg: Double,
    val source: String
)

@Entity(tableName = "walking_session_samples", indices = [Index("date"), Index("startEpochMs")])
data class WalkingSessionSampleEntity(
    @PrimaryKey val sessionId: String,
    val date: String,
    val startEpochMs: Long,
    val durationMinutes: Double,
    val steps: Long,
    val distanceKm: Double,
    val activeCalories: Double
)

@Entity(
    tableName = "daily_food_logs",
    indices = [Index("date"), Index("productId"), Index("barcode"), Index("mealId")]
)
data class DailyFoodLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
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
)

@Entity(
    tableName = "daily_extra_nutrient_logs",
    foreignKeys = [ForeignKey(
        entity = DailyFoodLogEntity::class,
        parentColumns = ["id"],
        childColumns = ["logId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("logId")]
)
data class DailyExtraNutrientLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long,
    val name: String,
    val valuePerServing: Double,
    val unit: String
)

@Entity(tableName = "app_metadata")
data class AppMetadataEntity(@PrimaryKey val key: String, val value: String)

data class DailyNutritionTotals(
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val sugarG: Double = 0.0,
    val fiberG: Double = 0.0,
    val saturatedFatG: Double = 0.0,
    val entries: Int = 0
)

data class DailySpendingTotals(
    val knownTotalMicros: Long = 0L,
    val unknownEntries: Int = 0,
    val catalogEstimatedMicros: Long = 0L,
    val actualPaidMicros: Long = 0L,
    val actualPaidEntries: Int = 0
)

data class DailyNutritionHistoryRow(
    val date: String,
    val calories: Double,
    val entries: Int
)

data class ExtraNutrientTotal(val name: String, val unit: String, val value: Double)

data class DeletedFoodLogEntity(
    val log: DailyFoodLogEntity,
    val extras: List<DailyExtraNutrientLogEntity>
)

data class DailyNutritionMutation(
    val date: String,
    val before: DailyNutritionTotals,
    val after: DailyNutritionTotals,
    val deleted: DeletedFoodLogEntity? = null
)

data class DailyNutritionGroupMutation(
    val date: String,
    val before: DailyNutritionTotals,
    val after: DailyNutritionTotals,
    val deleted: List<DeletedFoodLogEntity>
)

data class ProductSaveMutation(
    val affectedDates: Set<String>,
    val linkedEntriesUpdated: Int
)

fun DailyFoodLogEntity.toDomainSnapshot() = FoodLogSnapshot(
    id = id,
    date = java.time.LocalDate.parse(date),
    productId = productId,
    barcode = barcode,
    productName = productName,
    brand = brand,
    servingLabel = servingLabel,
    quantity = quantity,
    caloriesPerServing = caloriesPerServing,
    proteinGPerServing = proteinGPerServing,
    sodiumMgPerServing = sodiumMgPerServing,
    carbsGPerServing = carbsGPerServing,
    fatGPerServing = fatGPerServing,
    sugarGPerServing = sugarGPerServing,
    fiberGPerServing = fiberGPerServing,
    saturatedFatGPerServing = saturatedFatGPerServing,
    catalogCostPerServingMicros = catalogCostPerServingMicros,
    actualPaidTotalMicros = actualPaidTotalMicros,
    excludeCostFromBudget = excludeCostFromBudget,
    mealId = mealId,
    mealName = mealName,
    loggedAt = loggedAt
)

@Dao
interface NutritionDao {
    @Query("SELECT * FROM user_goals WHERE id = 1") fun observeUserGoals(): Flow<UserGoalsEntity?>
    @Query("SELECT * FROM user_goals WHERE id = 1") suspend fun userGoals(): UserGoalsEntity?
    @Upsert suspend fun upsertUserGoals(goals: UserGoalsEntity)
    @Query("SELECT * FROM health_profile WHERE id = 1") fun observeHealthProfile(): Flow<HealthProfileEntity?>
    @Query("SELECT * FROM health_profile WHERE id = 1") suspend fun healthProfile(): HealthProfileEntity?
    @Upsert suspend fun upsertHealthProfile(profile: HealthProfileEntity)
    @Query("SELECT * FROM weight_entries WHERE date BETWEEN :startDate AND :endDate ORDER BY recordedAtEpochMs")
    fun observeWeightEntries(startDate: String, endDate: String): Flow<List<WeightEntryEntity>>
    @Query("SELECT * FROM weight_entries ORDER BY recordedAtEpochMs") suspend fun allWeightEntries(): List<WeightEntryEntity>
    @Upsert suspend fun upsertWeightEntry(entry: WeightEntryEntity)
    @Upsert suspend fun upsertWeightEntries(entries: List<WeightEntryEntity>)
    @Query("DELETE FROM weight_entries WHERE entryId = :entryId") suspend fun deleteWeightEntry(entryId: String)
    @Query("DELETE FROM weight_entries WHERE source = 'HEALTH_CONNECT' AND date BETWEEN :startDate AND :endDate")
    suspend fun clearImportedWeights(startDate: String, endDate: String)
    @Query("SELECT * FROM walking_session_samples WHERE date BETWEEN :startDate AND :endDate ORDER BY startEpochMs")
    fun observeWalkingSamples(startDate: String, endDate: String): Flow<List<WalkingSessionSampleEntity>>
    @Query("SELECT * FROM walking_session_samples ORDER BY startEpochMs") suspend fun allWalkingSamples(): List<WalkingSessionSampleEntity>
    @Upsert suspend fun upsertWalkingSamples(samples: List<WalkingSessionSampleEntity>)
    @Query("DELETE FROM walking_session_samples WHERE date BETWEEN :startDate AND :endDate")
    suspend fun clearWalkingSamples(startDate: String, endDate: String)
    @Upsert suspend fun upsertProduct(product: ProductEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertProductIfMissing(product: ProductEntity): Long
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertExtraNutrients(nutrients: List<ProductExtraNutrientEntity>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertExtraNutrientsIfMissing(nutrients: List<ProductExtraNutrientEntity>)
    @Query("DELETE FROM product_extra_nutrients WHERE productId = :productId")
    suspend fun clearExtraNutrients(productId: String)
    @Query("SELECT * FROM products WHERE productId = :productId LIMIT 1")
    suspend fun productById(productId: String): ProductEntity?
    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun productByBarcode(barcode: String): ProductEntity?
    @Query("SELECT * FROM product_extra_nutrients WHERE productId = :productId ORDER BY name")
    suspend fun extrasForProduct(productId: String): List<ProductExtraNutrientEntity>
    @Query("SELECT * FROM products WHERE name LIKE '%' || :query || '%' ESCAPE '\\' OR brand LIKE '%' || :query || '%' ESCAPE '\\' OR COALESCE(barcode, '') LIKE '%' || :query || '%' ESCAPE '\\' ORDER BY favorite DESC, name LIMIT 100")
    fun observeProducts(query: String): Flow<List<ProductEntity>>
    @Query("""
        SELECT p.* FROM products p
        JOIN daily_food_logs f ON f.productId = p.productId
        GROUP BY p.productId
        ORDER BY MAX(f.loggedAt) DESC
        LIMIT 10
    """)
    fun observeRecentProducts(): Flow<List<ProductEntity>>
    @Query("""
        SELECT p.* FROM products p
        LEFT JOIN daily_food_logs f ON f.productId = p.productId
        WHERE p.favorite = 1
        GROUP BY p.productId
        ORDER BY MAX(f.loggedAt) IS NULL, MAX(f.loggedAt) DESC, p.updatedAt DESC, p.productId
        LIMIT 5
    """)
    fun observeFavoriteProducts(): Flow<List<ProductEntity>>
    @Query("UPDATE products SET favorite = :favorite, updatedAt = :updatedAt WHERE productId = :productId")
    suspend fun updateProductFavorite(productId: String, favorite: Boolean, updatedAt: Long = System.currentTimeMillis()): Int

    @Query("""
        UPDATE products SET includeInPlanner = :included, plannerItemType = :itemType,
            alwaysIncludeInPlanner = :fixed, fixedPurchaseUnits = :fixedUnits,
            updatedAt = :updatedAt WHERE productId = :productId
    """)
    suspend fun updateProductPlannerSettings(
        productId: String,
        included: Boolean,
        itemType: String,
        fixed: Boolean,
        fixedUnits: Int,
        updatedAt: Long = System.currentTimeMillis()
    ): Int

    @Query("SELECT * FROM products ORDER BY name COLLATE NOCASE, brand COLLATE NOCASE, productId")
    fun observePlannerProducts(): Flow<List<ProductEntity>>

    @Query("SELECT * FROM products ORDER BY productId") suspend fun allProducts(): List<ProductEntity>
    @Query("SELECT * FROM product_extra_nutrients ORDER BY productId, name") suspend fun allProductExtras(): List<ProductExtraNutrientEntity>
    @Query("SELECT * FROM daily_reports ORDER BY date") suspend fun allDailyReports(): List<DailyReportEntity>
    @Query("SELECT * FROM daily_food_logs ORDER BY id") suspend fun allFoodLogs(): List<DailyFoodLogEntity>
    @Query("SELECT * FROM daily_extra_nutrient_logs ORDER BY id") suspend fun allDailyExtras(): List<DailyExtraNutrientLogEntity>
    @Query("SELECT * FROM daily_reports WHERE date BETWEEN :startDate AND :endDate ORDER BY date")
    fun observeDailyReports(startDate: String, endDate: String): Flow<List<DailyReportEntity>>
    @Query("""
        SELECT date, COALESCE(SUM(caloriesPerServing * quantity), 0.0) AS calories, COUNT(*) AS entries
        FROM daily_food_logs WHERE date BETWEEN :startDate AND :endDate GROUP BY date ORDER BY date
    """)
    fun observeNutritionHistory(startDate: String, endDate: String): Flow<List<DailyNutritionHistoryRow>>

    @Transaction
    suspend fun saveProductWithExtras(product: ProductEntity, extras: List<ProductExtraNutrientEntity>) {
        val existing = productById(product.productId)
        upsertProduct(product.copy(
            createdAt = existing?.createdAt ?: product.createdAt,
            updatedAt = System.currentTimeMillis()
        ))
        clearExtraNutrients(product.productId)
        if (extras.isNotEmpty()) upsertExtraNutrients(extras)
    }

    @Query("SELECT DISTINCT date FROM daily_food_logs WHERE productId = :productId")
    suspend fun linkedDates(productId: String): List<String>
    @Query("SELECT id FROM daily_food_logs WHERE productId = :productId")
    suspend fun linkedLogIds(productId: String): List<Long>
    @Query("""
        UPDATE daily_food_logs SET barcode = :barcode, productName = :name, brand = :brand,
            servingLabel = :servingLabel, caloriesPerServing = :calories,
            proteinGPerServing = :proteinG, sodiumMgPerServing = :sodiumMg,
            carbsGPerServing = :carbsG, fatGPerServing = :fatG,
            sugarGPerServing = :sugarG, fiberGPerServing = :fiberG,
            saturatedFatGPerServing = :saturatedFatG
        WHERE productId = :productId
    """)
    suspend fun updateLinkedLogSnapshots(
        productId: String, barcode: String?, name: String, brand: String, servingLabel: String,
        calories: Double, proteinG: Double, sodiumMg: Double, carbsG: Double, fatG: Double,
        sugarG: Double, fiberG: Double, saturatedFatG: Double
    ): Int
    @Query("DELETE FROM daily_extra_nutrient_logs WHERE logId = :logId")
    suspend fun clearDailyExtrasForLog(logId: Long)

    @Transaction
    suspend fun saveProductAndUpdateLinkedLogs(
        product: ProductEntity,
        extras: List<ProductExtraNutrientEntity>
    ): ProductSaveMutation {
        val dates = linkedDates(product.productId).toSet()
        val logIds = linkedLogIds(product.productId)
        saveProductWithExtras(product, extras)
        val updated = updateLinkedLogSnapshots(
            product.productId, product.barcode, product.name, product.brand, product.servingLabel,
            product.calories, product.proteinG, product.sodiumMg, product.carbsG, product.fatG,
            product.sugarG, product.fiberG, product.saturatedFatG
        )
        logIds.forEach { logId ->
            clearDailyExtrasForLog(logId)
            if (extras.isNotEmpty()) insertDailyExtraLogs(extras.map {
                DailyExtraNutrientLogEntity(logId = logId, name = it.name, valuePerServing = it.value, unit = it.unit)
            })
        }
        return ProductSaveMutation(dates, updated)
    }

    @Transaction
    suspend fun importSeedProducts(products: List<ProductWithExtras>, markerKey: String) {
        if (metadata(markerKey) == "complete") return
        products.forEach { item ->
            if (insertProductIfMissing(item.product) != -1L && item.extras.isNotEmpty()) {
                insertExtraNutrientsIfMissing(item.extras)
            }
        }
        upsertMetadata(AppMetadataEntity(markerKey, "complete"))
    }

    @Insert suspend fun insertFoodLog(log: DailyFoodLogEntity): Long
    @Insert suspend fun insertFoodLogs(logs: List<DailyFoodLogEntity>)
    @Insert suspend fun insertDailyExtraLogs(logs: List<DailyExtraNutrientLogEntity>)
    @Update suspend fun updateFoodLog(log: DailyFoodLogEntity)
    @Query("UPDATE daily_food_logs SET quantity = :quantity, actualPaidTotalMicros = :actualPaidTotalMicros, excludeCostFromBudget = :excludeCostFromBudget WHERE id = :id")
    suspend fun updateFoodLogQuantity(id: Long, quantity: Double, actualPaidTotalMicros: Long?, excludeCostFromBudget: Boolean): Int
    @Query("SELECT * FROM daily_food_logs WHERE id = :id LIMIT 1") suspend fun foodLogById(id: Long): DailyFoodLogEntity?
    @Query("SELECT * FROM daily_extra_nutrient_logs WHERE logId = :logId ORDER BY id")
    suspend fun dailyExtrasForLog(logId: Long): List<DailyExtraNutrientLogEntity>
    @Query("SELECT * FROM daily_food_logs WHERE date = :date ORDER BY loggedAt DESC") suspend fun foodLogsForDate(date: String): List<DailyFoodLogEntity>
    @Query("SELECT * FROM daily_food_logs WHERE date = :date ORDER BY loggedAt DESC") fun observeLogsForDate(date: String): Flow<List<DailyFoodLogEntity>>
    @Query("DELETE FROM daily_food_logs WHERE id = :id") suspend fun deleteLog(id: Long): Int
    @Query("SELECT * FROM daily_food_logs WHERE mealId = :mealId ORDER BY loggedAt, id")
    suspend fun foodLogsForMeal(mealId: String): List<DailyFoodLogEntity>
    @Query("DELETE FROM daily_food_logs WHERE mealId = :mealId")
    suspend fun deleteMealLogs(mealId: String): Int

    @Transaction
    suspend fun addProductToDate(
        date: String,
        product: ProductEntity,
        quantity: Double,
        extras: List<ProductExtraNutrientEntity>,
        actualPaidTotalMicros: Long? = null,
        excludeCostFromBudget: Boolean = false,
        mealId: String? = null,
        mealName: String? = null
    ): DailyNutritionMutation {
        val before = totalsForDate(date)
        val logId = insertFoodLog(
            DailyFoodLogEntity(
                date = date,
                productId = product.productId,
                barcode = product.barcode,
                productName = product.name,
                brand = product.brand,
                servingLabel = product.servingLabel,
                quantity = quantity,
                caloriesPerServing = product.calories,
                proteinGPerServing = product.proteinG,
                sodiumMgPerServing = product.sodiumMg,
                carbsGPerServing = product.carbsG,
                fatGPerServing = product.fatG,
                sugarGPerServing = product.sugarG,
                fiberGPerServing = product.fiberG,
                saturatedFatGPerServing = product.saturatedFatG,
                catalogCostPerServingMicros = product.purchasePriceMicros?.let {
                    (it / product.purchaseUnitServings).toLong()
                },
                actualPaidTotalMicros = actualPaidTotalMicros,
                excludeCostFromBudget = excludeCostFromBudget,
                mealId = mealId,
                mealName = mealName
            )
        )
        if (extras.isNotEmpty()) insertDailyExtraLogs(extras.map {
            DailyExtraNutrientLogEntity(logId = logId, name = it.name, valuePerServing = it.value, unit = it.unit)
        })
        return DailyNutritionMutation(date, before, totalsForDate(date))
    }

    @Transaction
    suspend fun addBulkPurchaseToDate(
        date: String,
        groupId: String,
        groupLabel: String,
        selections: List<BulkLogSelection>,
        actualPaidTotalMicros: Long?,
        excludeCostFromBudget: Boolean
    ): DailyNutritionMutation {
        val before = totalsForDate(date)
        val entries = selections.map { selection ->
            val product = productById(selection.productId) ?: error("A selected product no longer exists.")
            BulkLogEntryInput(ProductWithExtras(product, extrasForProduct(product.productId)), selection.quantity)
        }
        val allocations = allocateBulkPaidTotal(actualPaidTotalMicros, entries)
        entries.forEachIndexed { index, entry ->
            addProductToDate(
                date = date,
                product = entry.product.product,
                quantity = entry.quantity,
                extras = entry.product.extras,
                actualPaidTotalMicros = allocations[index],
                excludeCostFromBudget = excludeCostFromBudget,
                mealId = groupId,
                mealName = groupLabel
            )
        }
        return DailyNutritionMutation(date, before, totalsForDate(date))
    }

    @Transaction
    suspend fun addMultipleProductsToDate(date: String, selections: List<BulkLogSelection>): DailyNutritionMutation {
        val before = totalsForDate(date)
        selections.forEach { selection ->
            val product = productById(selection.productId) ?: error("A scanned product no longer exists.")
            addProductToDate(
                date = date,
                product = product,
                quantity = selection.quantity,
                extras = extrasForProduct(product.productId),
                actualPaidTotalMicros = selection.actualPaidTotalMicros,
                excludeCostFromBudget = selection.excludeCostFromBudget
            )
        }
        return DailyNutritionMutation(date, before, totalsForDate(date))
    }

    @Transaction
    suspend fun updateFoodLogQuantitySnapshot(
        id: Long,
        quantity: Double,
        actualPaidTotalMicros: Long? = null,
        excludeCostFromBudget: Boolean = false
    ): DailyNutritionMutation? {
        val existing = foodLogById(id) ?: return null
        val before = totalsForDate(existing.date)
        check(updateFoodLogQuantity(id, quantity, actualPaidTotalMicros, excludeCostFromBudget) == 1) { "Food entry no longer exists." }
        return DailyNutritionMutation(existing.date, before, totalsForDate(existing.date))
    }

    @Transaction
    suspend fun deleteFoodLogSnapshot(id: Long): DailyNutritionMutation? {
        val existing = foodLogById(id) ?: return null
        val before = totalsForDate(existing.date)
        val deleted = DeletedFoodLogEntity(existing, dailyExtrasForLog(id))
        check(deleteLog(id) == 1) { "Food entry no longer exists." }
        return DailyNutritionMutation(existing.date, before, totalsForDate(existing.date), deleted)
    }

    @Transaction
    suspend fun restoreFoodLogSnapshot(deleted: DeletedFoodLogEntity): DailyNutritionMutation {
        val date = deleted.log.date
        val before = totalsForDate(date)
        insertFoodLog(deleted.log)
        if (deleted.extras.isNotEmpty()) insertDailyExtraLogs(deleted.extras)
        return DailyNutritionMutation(date, before, totalsForDate(date))
    }

    @Transaction
    suspend fun deleteFoodLogGroup(mealId: String): DailyNutritionGroupMutation? {
        val logs = foodLogsForMeal(mealId)
        if (logs.isEmpty()) return null
        val date = logs.first().date
        require(logs.all { it.date == date }) { "A bulk group cannot span dates." }
        val before = totalsForDate(date)
        val deleted = logs.map { DeletedFoodLogEntity(it, dailyExtrasForLog(it.id)) }
        check(deleteMealLogs(mealId) == logs.size) { "Bulk order changed before it could be deleted." }
        return DailyNutritionGroupMutation(date, before, totalsForDate(date), deleted)
    }

    @Transaction
    suspend fun restoreFoodLogGroup(deleted: List<DeletedFoodLogEntity>): DailyNutritionGroupMutation {
        require(deleted.isNotEmpty())
        val date = deleted.first().log.date
        require(deleted.all { it.log.date == date })
        val before = totalsForDate(date)
        deleted.forEach {
            insertFoodLog(it.log)
            if (it.extras.isNotEmpty()) insertDailyExtraLogs(it.extras)
        }
        return DailyNutritionGroupMutation(date, before, totalsForDate(date), deleted)
    }

    @Query("""
        SELECT COALESCE(SUM(caloriesPerServing * quantity), 0.0) AS calories,
            COALESCE(SUM(proteinGPerServing * quantity), 0.0) AS proteinG,
            COALESCE(SUM(sodiumMgPerServing * quantity), 0.0) AS sodiumMg,
            COALESCE(SUM(carbsGPerServing * quantity), 0.0) AS carbsG,
            COALESCE(SUM(fatGPerServing * quantity), 0.0) AS fatG,
            COALESCE(SUM(sugarGPerServing * quantity), 0.0) AS sugarG,
            COALESCE(SUM(fiberGPerServing * quantity), 0.0) AS fiberG,
            COALESCE(SUM(saturatedFatGPerServing * quantity), 0.0) AS saturatedFatG,
            COUNT(*) AS entries FROM daily_food_logs WHERE date = :date
    """) fun observeTotalsForDate(date: String): Flow<DailyNutritionTotals>

    @Query("""
        SELECT COALESCE(SUM(caloriesPerServing * quantity), 0.0) AS calories,
            COALESCE(SUM(proteinGPerServing * quantity), 0.0) AS proteinG,
            COALESCE(SUM(sodiumMgPerServing * quantity), 0.0) AS sodiumMg,
            COALESCE(SUM(carbsGPerServing * quantity), 0.0) AS carbsG,
            COALESCE(SUM(fatGPerServing * quantity), 0.0) AS fatG,
            COALESCE(SUM(sugarGPerServing * quantity), 0.0) AS sugarG,
            COALESCE(SUM(fiberGPerServing * quantity), 0.0) AS fiberG,
            COALESCE(SUM(saturatedFatGPerServing * quantity), 0.0) AS saturatedFatG,
            COUNT(*) AS entries FROM daily_food_logs WHERE date = :date
    """) suspend fun totalsForDate(date: String): DailyNutritionTotals

    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN excludeCostFromBudget = 1 THEN 0
            WHEN actualPaidTotalMicros IS NOT NULL THEN actualPaidTotalMicros
            WHEN catalogCostPerServingMicros IS NOT NULL THEN CAST(catalogCostPerServingMicros * quantity AS INTEGER)
            ELSE 0 END), 0) AS knownTotalMicros,
            COALESCE(SUM(CASE WHEN excludeCostFromBudget = 0 AND actualPaidTotalMicros IS NULL AND catalogCostPerServingMicros IS NULL THEN 1 ELSE 0 END), 0) AS unknownEntries,
            COALESCE(SUM(CASE WHEN excludeCostFromBudget = 0 AND catalogCostPerServingMicros IS NOT NULL THEN CAST(catalogCostPerServingMicros * quantity AS INTEGER) ELSE 0 END), 0) AS catalogEstimatedMicros,
            COALESCE(SUM(CASE WHEN excludeCostFromBudget = 0 AND actualPaidTotalMicros IS NOT NULL THEN actualPaidTotalMicros ELSE 0 END), 0) AS actualPaidMicros,
            COALESCE(SUM(CASE WHEN excludeCostFromBudget = 0 AND actualPaidTotalMicros IS NOT NULL THEN 1 ELSE 0 END), 0) AS actualPaidEntries
        FROM daily_food_logs WHERE date = :date
    """) fun observeSpendingForDate(date: String): Flow<DailySpendingTotals>

    @Query("""
        SELECT COALESCE(SUM(CASE
            WHEN excludeCostFromBudget = 1 THEN 0
            WHEN actualPaidTotalMicros IS NOT NULL THEN actualPaidTotalMicros
            WHEN catalogCostPerServingMicros IS NOT NULL THEN CAST(catalogCostPerServingMicros * quantity AS INTEGER)
            ELSE 0 END), 0) AS knownTotalMicros,
            COALESCE(SUM(CASE WHEN excludeCostFromBudget = 0 AND actualPaidTotalMicros IS NULL AND catalogCostPerServingMicros IS NULL THEN 1 ELSE 0 END), 0) AS unknownEntries,
            COALESCE(SUM(CASE WHEN excludeCostFromBudget = 0 AND catalogCostPerServingMicros IS NOT NULL THEN CAST(catalogCostPerServingMicros * quantity AS INTEGER) ELSE 0 END), 0) AS catalogEstimatedMicros,
            COALESCE(SUM(CASE WHEN excludeCostFromBudget = 0 AND actualPaidTotalMicros IS NOT NULL THEN actualPaidTotalMicros ELSE 0 END), 0) AS actualPaidMicros,
            COALESCE(SUM(CASE WHEN excludeCostFromBudget = 0 AND actualPaidTotalMicros IS NOT NULL THEN 1 ELSE 0 END), 0) AS actualPaidEntries
        FROM daily_food_logs WHERE date = :date
    """) suspend fun spendingForDate(date: String): DailySpendingTotals

    @Query("""
        SELECT e.name, e.unit, COALESCE(SUM(e.valuePerServing * f.quantity), 0.0) AS value
        FROM daily_extra_nutrient_logs e JOIN daily_food_logs f ON f.id = e.logId
        WHERE f.date = :date GROUP BY e.name, e.unit ORDER BY e.name
    """) fun observeExtraTotalsForDate(date: String): Flow<List<ExtraNutrientTotal>>

    @Query("SELECT * FROM daily_reports WHERE date = :date LIMIT 1") fun observeDailyReport(date: String): Flow<DailyReportEntity?>
    @Query("SELECT * FROM daily_reports WHERE date = :date LIMIT 1") suspend fun dailyReport(date: String): DailyReportEntity?
    @Upsert suspend fun upsertDailyReport(report: DailyReportEntity)
    @Insert suspend fun insertDailyReports(reports: List<DailyReportEntity>)

    @Transaction
    suspend fun replaceImportedHealthHistory(
        startDate: String,
        endDate: String,
        reports: List<DailyReportEntity>,
        weights: List<WeightEntryEntity>,
        walking: List<WalkingSessionSampleEntity>
    ) {
        reports.forEach { upsertDailyReport(it) }
        clearImportedWeights(startDate, endDate)
        if (weights.isNotEmpty()) upsertWeightEntries(weights)
        clearWalkingSamples(startDate, endDate)
        if (walking.isNotEmpty()) upsertWalkingSamples(walking)
    }

    @Query("SELECT value FROM app_metadata WHERE `key` = :key LIMIT 1") suspend fun metadata(key: String): String?
    @Query("SELECT value FROM app_metadata WHERE `key` = :key LIMIT 1") fun observeMetadata(key: String): Flow<String?>
    @Upsert suspend fun upsertMetadata(metadata: AppMetadataEntity)
    @Query("""
        UPDATE daily_reports
        SET manualFoodCalories = NULL,
            manualProteinG = NULL,
            manualSodiumMg = NULL,
            manualBurnCalories = NULL,
            notes = '',
            savedAtEpochMs = :updatedAt
        WHERE manualFoodCalories IS NOT NULL
            OR manualProteinG IS NOT NULL
            OR manualSodiumMg IS NOT NULL
            OR manualBurnCalories IS NOT NULL
            OR notes != ''
    """) suspend fun clearManualOverrides(updatedAt: Long = System.currentTimeMillis())

    @Transaction
    suspend fun importLegacyReports(reports: List<DailyReportEntity>) {
        reports.forEach { upsertDailyReport(it) }
        upsertMetadata(AppMetadataEntity(LegacyReportImporter.IMPORT_KEY, "complete"))
    }

    @Query("DELETE FROM daily_extra_nutrient_logs") suspend fun clearDailyExtras()
    @Query("DELETE FROM daily_food_logs") suspend fun clearFoodLogs()
    @Query("DELETE FROM daily_reports") suspend fun clearDailyReports()
    @Query("DELETE FROM product_extra_nutrients") suspend fun clearProductExtras()
    @Query("DELETE FROM products") suspend fun clearProducts()
    @Query("DELETE FROM user_goals") suspend fun clearUserGoals()
    @Query("DELETE FROM health_profile") suspend fun clearHealthProfile()
    @Query("DELETE FROM weight_entries") suspend fun clearWeightEntries()
    @Query("DELETE FROM walking_session_samples") suspend fun clearWalkingSamples()

    @Transaction
    suspend fun replaceUserData(
        products: List<ProductEntity>,
        productExtras: List<ProductExtraNutrientEntity>,
        reports: List<DailyReportEntity>,
        foodLogs: List<DailyFoodLogEntity>,
        dailyExtras: List<DailyExtraNutrientLogEntity>,
        goals: UserGoalsEntity,
        healthProfile: HealthProfileEntity = HealthProfileEntity(),
        weights: List<WeightEntryEntity> = emptyList(),
        walking: List<WalkingSessionSampleEntity> = emptyList()
    ) {
        clearDailyExtras(); clearFoodLogs(); clearDailyReports(); clearProductExtras(); clearProducts(); clearUserGoals()
        clearWeightEntries(); clearWalkingSamples(); clearHealthProfile()
        products.forEach { upsertProduct(it) }
        if (productExtras.isNotEmpty()) upsertExtraNutrients(productExtras)
        if (reports.isNotEmpty()) insertDailyReports(reports)
        if (foodLogs.isNotEmpty()) insertFoodLogs(foodLogs)
        if (dailyExtras.isNotEmpty()) insertDailyExtraLogs(dailyExtras)
        upsertUserGoals(goals)
        upsertHealthProfile(healthProfile)
        if (weights.isNotEmpty()) upsertWeightEntries(weights)
        if (walking.isNotEmpty()) upsertWalkingSamples(walking)
    }
}

@Database(
    entities = [ProductEntity::class, ProductExtraNutrientEntity::class, DailyReportEntity::class,
        DailyFoodLogEntity::class, DailyExtraNutrientLogEntity::class, AppMetadataEntity::class,
        UserGoalsEntity::class, HealthProfileEntity::class, WeightEntryEntity::class,
        WalkingSessionSampleEntity::class],
    version = 8,
    exportSchema = true
)
abstract class NutritionDatabase : RoomDatabase() {
    abstract fun nutritionDao(): NutritionDao

    companion object {
        @Volatile private var INSTANCE: NutritionDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `daily_reports` (`date` TEXT NOT NULL, `steps` INTEGER NOT NULL, `distanceKm` REAL NOT NULL, `activeCalories` REAL NOT NULL, `totalCalories` REAL NOT NULL, `exerciseSessions` INTEGER NOT NULL, `exerciseMinutes` INTEGER NOT NULL, `nutritionCalories` REAL NOT NULL, `nutritionProteinG` REAL NOT NULL, `nutritionSodiumMg` REAL NOT NULL, `nutritionRecords` INTEGER NOT NULL, `healthConnectStatus` TEXT NOT NULL, `manualFoodCalories` REAL, `manualProteinG` REAL, `manualSodiumMg` REAL, `manualBurnCalories` REAL, `notes` TEXT NOT NULL, `savedAtEpochMs` INTEGER NOT NULL, PRIMARY KEY(`date`))""")
                db.execSQL("CREATE TABLE IF NOT EXISTS `app_metadata` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("""CREATE TABLE `daily_food_logs_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `barcode` TEXT NOT NULL, `productName` TEXT NOT NULL, `brand` TEXT NOT NULL, `servingLabel` TEXT NOT NULL, `quantity` REAL NOT NULL, `caloriesPerServing` REAL NOT NULL, `proteinGPerServing` REAL NOT NULL, `sodiumMgPerServing` REAL NOT NULL, `carbsGPerServing` REAL NOT NULL, `fatGPerServing` REAL NOT NULL, `sugarGPerServing` REAL NOT NULL, `fiberGPerServing` REAL NOT NULL, `saturatedFatGPerServing` REAL NOT NULL, `loggedAt` INTEGER NOT NULL)""")
                db.execSQL("""INSERT INTO daily_food_logs_new SELECT id,date,barcode,productName,brand,servingLabel,quantity,CASE WHEN quantity>0 THEN calories/quantity ELSE calories END,CASE WHEN quantity>0 THEN proteinG/quantity ELSE proteinG END,CASE WHEN quantity>0 THEN sodiumMg/quantity ELSE sodiumMg END,CASE WHEN quantity>0 THEN carbsG/quantity ELSE carbsG END,CASE WHEN quantity>0 THEN fatG/quantity ELSE fatG END,CASE WHEN quantity>0 THEN sugarG/quantity ELSE sugarG END,CASE WHEN quantity>0 THEN fiberG/quantity ELSE fiberG END,CASE WHEN quantity>0 THEN saturatedFatG/quantity ELSE saturatedFatG END,loggedAt FROM daily_food_logs""")
                db.execSQL("""CREATE TABLE `daily_extra_nutrient_logs_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `logId` INTEGER NOT NULL, `name` TEXT NOT NULL, `valuePerServing` REAL NOT NULL, `unit` TEXT NOT NULL, FOREIGN KEY(`logId`) REFERENCES `daily_food_logs_new`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("""INSERT INTO daily_extra_nutrient_logs_new SELECT e.id,e.logId,e.name,CASE WHEN f.quantity>0 THEN e.value/f.quantity ELSE e.value END,e.unit FROM daily_extra_nutrient_logs e JOIN daily_food_logs f ON f.id=e.logId""")
                db.execSQL("DROP TABLE daily_extra_nutrient_logs"); db.execSQL("DROP TABLE daily_food_logs")
                db.execSQL("ALTER TABLE daily_food_logs_new RENAME TO daily_food_logs"); db.execSQL("ALTER TABLE daily_extra_nutrient_logs_new RENAME TO daily_extra_nutrient_logs")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_food_logs_date` ON `daily_food_logs` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_food_logs_barcode` ON `daily_food_logs` (`barcode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_extra_nutrient_logs_logId` ON `daily_extra_nutrient_logs` (`logId`)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE `products_new` (`productId` TEXT NOT NULL, `barcode` TEXT, `name` TEXT NOT NULL, `brand` TEXT NOT NULL, `servingLabel` TEXT NOT NULL, `calories` REAL NOT NULL, `proteinG` REAL NOT NULL, `sodiumMg` REAL NOT NULL, `carbsG` REAL NOT NULL, `fatG` REAL NOT NULL, `sugarG` REAL NOT NULL, `fiberG` REAL NOT NULL, `saturatedFatG` REAL NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`productId`))""")
                db.execSQL("""INSERT INTO products_new SELECT barcode,CASE WHEN length(barcode) BETWEEN 8 AND 14 AND barcode NOT GLOB '*[^0-9]*' THEN barcode ELSE NULL END,name,brand,servingLabel,calories,proteinG,sodiumMg,carbsG,fatG,sugarG,fiberG,saturatedFatG,notes,createdAt,updatedAt FROM products""")
                db.execSQL("""CREATE TABLE `product_extra_nutrients_backup` (`productId` TEXT NOT NULL, `name` TEXT NOT NULL, `value` REAL NOT NULL, `unit` TEXT NOT NULL)""")
                db.execSQL("INSERT INTO product_extra_nutrients_backup SELECT barcode,name,value,unit FROM product_extra_nutrients")
                db.execSQL("""CREATE TABLE `daily_food_logs_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `productId` TEXT, `barcode` TEXT, `productName` TEXT NOT NULL, `brand` TEXT NOT NULL, `servingLabel` TEXT NOT NULL, `quantity` REAL NOT NULL, `caloriesPerServing` REAL NOT NULL, `proteinGPerServing` REAL NOT NULL, `sodiumMgPerServing` REAL NOT NULL, `carbsGPerServing` REAL NOT NULL, `fatGPerServing` REAL NOT NULL, `sugarGPerServing` REAL NOT NULL, `fiberGPerServing` REAL NOT NULL, `saturatedFatGPerServing` REAL NOT NULL, `loggedAt` INTEGER NOT NULL)""")
                db.execSQL("""INSERT INTO daily_food_logs_new SELECT id,date,barcode,CASE WHEN length(barcode) BETWEEN 8 AND 14 AND barcode NOT GLOB '*[^0-9]*' THEN barcode ELSE NULL END,productName,brand,servingLabel,quantity,caloriesPerServing,proteinGPerServing,sodiumMgPerServing,carbsGPerServing,fatGPerServing,sugarGPerServing,fiberGPerServing,saturatedFatGPerServing,loggedAt FROM daily_food_logs""")
                db.execSQL("""CREATE TABLE `daily_extra_nutrient_logs_backup` (`id` INTEGER NOT NULL, `logId` INTEGER NOT NULL, `name` TEXT NOT NULL, `valuePerServing` REAL NOT NULL, `unit` TEXT NOT NULL)""")
                db.execSQL("INSERT INTO daily_extra_nutrient_logs_backup SELECT id,logId,name,valuePerServing,unit FROM daily_extra_nutrient_logs")
                db.execSQL("DROP TABLE product_extra_nutrients"); db.execSQL("DROP TABLE products"); db.execSQL("DROP TABLE daily_extra_nutrient_logs"); db.execSQL("DROP TABLE daily_food_logs")
                db.execSQL("ALTER TABLE products_new RENAME TO products"); db.execSQL("ALTER TABLE daily_food_logs_new RENAME TO daily_food_logs")
                db.execSQL("""CREATE TABLE `product_extra_nutrients` (`productId` TEXT NOT NULL, `name` TEXT NOT NULL, `value` REAL NOT NULL, `unit` TEXT NOT NULL, PRIMARY KEY(`productId`,`name`), FOREIGN KEY(`productId`) REFERENCES `products`(`productId`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("INSERT INTO product_extra_nutrients SELECT productId,name,value,unit FROM product_extra_nutrients_backup")
                db.execSQL("""CREATE TABLE `daily_extra_nutrient_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `logId` INTEGER NOT NULL, `name` TEXT NOT NULL, `valuePerServing` REAL NOT NULL, `unit` TEXT NOT NULL, FOREIGN KEY(`logId`) REFERENCES `daily_food_logs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("INSERT INTO daily_extra_nutrient_logs SELECT id,logId,name,valuePerServing,unit FROM daily_extra_nutrient_logs_backup")
                db.execSQL("DROP TABLE product_extra_nutrients_backup"); db.execSQL("DROP TABLE daily_extra_nutrient_logs_backup")
                db.execSQL("CREATE UNIQUE INDEX `index_products_barcode` ON `products` (`barcode`)")
                db.execSQL("CREATE INDEX `index_product_extra_nutrients_productId` ON `product_extra_nutrients` (`productId`)")
                db.execSQL("CREATE INDEX `index_daily_food_logs_date` ON `daily_food_logs` (`date`)")
                db.execSQL("CREATE INDEX `index_daily_food_logs_productId` ON `daily_food_logs` (`productId`)")
                db.execSQL("CREATE INDEX `index_daily_food_logs_barcode` ON `daily_food_logs` (`barcode`)")
                db.execSQL("CREATE INDEX `index_daily_extra_nutrient_logs_logId` ON `daily_extra_nutrient_logs` (`logId`)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `purchasePriceMicros` INTEGER")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `purchaseUnitServings` REAL NOT NULL DEFAULT 1.0")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `includeInPlanner` INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `plannerItemType` TEXT NOT NULL DEFAULT 'FOOD'")
                db.execSQL("ALTER TABLE `products` ADD COLUMN `alwaysIncludeInPlanner` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `daily_food_logs` ADD COLUMN `catalogCostPerServingMicros` INTEGER")
                db.execSQL("ALTER TABLE `daily_food_logs` ADD COLUMN `actualPaidTotalMicros` INTEGER")
                db.execSQL("ALTER TABLE `daily_food_logs` ADD COLUMN `excludeCostFromBudget` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `user_goals` (`id` INTEGER NOT NULL, `mode` TEXT NOT NULL, `calories` REAL NOT NULL, `expectedBurnCalories` REAL NOT NULL, `desiredDeficitCalories` REAL NOT NULL, `proteinG` REAL NOT NULL, `sodiumMg` REAL NOT NULL, `carbsG` REAL NOT NULL, `fatG` REAL NOT NULL, `sugarG` REAL NOT NULL, `fiberG` REAL NOT NULL, `saturatedFatG` REAL NOT NULL, `currencyCode` TEXT NOT NULL, `dailyBudgetMicros` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
                db.execSQL("""INSERT OR IGNORE INTO `user_goals` VALUES (1,'CALORIE',1850,2300,450,120,2000,150,60,50,15,15,'TWD',0)""")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `daily_food_logs` ADD COLUMN `mealId` TEXT")
                db.execSQL("ALTER TABLE `daily_food_logs` ADD COLUMN `mealName` TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_food_logs_mealId` ON `daily_food_logs` (`mealId`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS `health_profile` (`id` INTEGER NOT NULL, `weightUnit` TEXT NOT NULL, `targetWeightKg` REAL, PRIMARY KEY(`id`))""")
                db.execSQL("INSERT OR IGNORE INTO `health_profile` (`id`,`weightUnit`,`targetWeightKg`) VALUES (1,'KG',NULL)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `weight_entries` (`entryId` TEXT NOT NULL, `date` TEXT NOT NULL, `recordedAtEpochMs` INTEGER NOT NULL, `weightKg` REAL NOT NULL, `source` TEXT NOT NULL, PRIMARY KEY(`entryId`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_weight_entries_date` ON `weight_entries` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_weight_entries_recordedAtEpochMs` ON `weight_entries` (`recordedAtEpochMs`)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS `walking_session_samples` (`sessionId` TEXT NOT NULL, `date` TEXT NOT NULL, `startEpochMs` INTEGER NOT NULL, `durationMinutes` REAL NOT NULL, `steps` INTEGER NOT NULL, `distanceKm` REAL NOT NULL, `activeCalories` REAL NOT NULL, PRIMARY KEY(`sessionId`))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_walking_session_samples_date` ON `walking_session_samples` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_walking_session_samples_startEpochMs` ON `walking_session_samples` (`startEpochMs`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `products` ADD COLUMN `fixedPurchaseUnits` INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun get(context: Context): NutritionDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, NutritionDatabase::class.java, "dailycut_nutrition.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
                ).build().also { INSTANCE = it }
        }
    }
}

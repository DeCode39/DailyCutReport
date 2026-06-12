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

@Entity(tableName = "products")
data class ProductEntity(
    @PrimaryKey val barcode: String,
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
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "product_extra_nutrients",
    primaryKeys = ["barcode", "name"],
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["barcode"],
            childColumns = ["barcode"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("barcode")]
)
data class ProductExtraNutrientEntity(
    val barcode: String,
    val name: String,
    val value: Double,
    val unit: String
)

@Entity(
    tableName = "daily_food_logs",
    foreignKeys = [
        ForeignKey(
            entity = ProductEntity::class,
            parentColumns = ["barcode"],
            childColumns = ["barcode"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("date"), Index("barcode")]
)
data class DailyFoodLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val barcode: String,
    val productName: String,
    val brand: String = "",
    val servingLabel: String = "1 serving",
    val quantity: Double = 1.0,
    val calories: Double = 0.0,
    val proteinG: Double = 0.0,
    val sodiumMg: Double = 0.0,
    val carbsG: Double = 0.0,
    val fatG: Double = 0.0,
    val sugarG: Double = 0.0,
    val fiberG: Double = 0.0,
    val saturatedFatG: Double = 0.0,
    val loggedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "daily_extra_nutrient_logs",
    foreignKeys = [
        ForeignKey(
            entity = DailyFoodLogEntity::class,
            parentColumns = ["id"],
            childColumns = ["logId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("logId")]
)
data class DailyExtraNutrientLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val logId: Long,
    val name: String,
    val value: Double,
    val unit: String
)

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

data class ExtraNutrientTotal(
    val name: String,
    val unit: String,
    val value: Double
)

@Dao
interface NutritionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProduct(product: ProductEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExtraNutrients(nutrients: List<ProductExtraNutrientEntity>)

    @Query("DELETE FROM product_extra_nutrients WHERE barcode = :barcode")
    suspend fun clearExtraNutrients(barcode: String)

    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun productByBarcode(barcode: String): ProductEntity?

    @Query("SELECT * FROM product_extra_nutrients WHERE barcode = :barcode ORDER BY name")
    suspend fun extrasForProduct(barcode: String): List<ProductExtraNutrientEntity>

    @Insert
    suspend fun insertFoodLog(log: DailyFoodLogEntity): Long

    @Insert
    suspend fun insertDailyExtraLogs(logs: List<DailyExtraNutrientLogEntity>)

    @Transaction
    suspend fun saveProductWithExtras(product: ProductEntity, extras: List<ProductExtraNutrientEntity>) {
        upsertProduct(product.copy(updatedAt = System.currentTimeMillis()))
        clearExtraNutrients(product.barcode)
        if (extras.isNotEmpty()) upsertExtraNutrients(extras)
    }

    @Transaction
    suspend fun addProductToDate(date: String, product: ProductEntity, quantity: Double, extras: List<ProductExtraNutrientEntity>) {
        val logId = insertFoodLog(
            DailyFoodLogEntity(
                date = date,
                barcode = product.barcode,
                productName = product.name,
                brand = product.brand,
                servingLabel = product.servingLabel,
                quantity = quantity,
                calories = product.calories * quantity,
                proteinG = product.proteinG * quantity,
                sodiumMg = product.sodiumMg * quantity,
                carbsG = product.carbsG * quantity,
                fatG = product.fatG * quantity,
                sugarG = product.sugarG * quantity,
                fiberG = product.fiberG * quantity,
                saturatedFatG = product.saturatedFatG * quantity
            )
        )
        if (extras.isNotEmpty()) {
            insertDailyExtraLogs(
                extras.map {
                    DailyExtraNutrientLogEntity(
                        logId = logId,
                        name = it.name,
                        value = it.value * quantity,
                        unit = it.unit
                    )
                }
            )
        }
    }

    @Query("SELECT * FROM daily_food_logs WHERE date = :date ORDER BY loggedAt DESC")
    suspend fun logsForDate(date: String): List<DailyFoodLogEntity>

    @Query("DELETE FROM daily_food_logs WHERE id = :id")
    suspend fun deleteLog(id: Long)

    @Query(
        """
        SELECT
            COALESCE(SUM(calories), 0.0) AS calories,
            COALESCE(SUM(proteinG), 0.0) AS proteinG,
            COALESCE(SUM(sodiumMg), 0.0) AS sodiumMg,
            COALESCE(SUM(carbsG), 0.0) AS carbsG,
            COALESCE(SUM(fatG), 0.0) AS fatG,
            COALESCE(SUM(sugarG), 0.0) AS sugarG,
            COALESCE(SUM(fiberG), 0.0) AS fiberG,
            COALESCE(SUM(saturatedFatG), 0.0) AS saturatedFatG,
            COUNT(*) AS entries
        FROM daily_food_logs
        WHERE date = :date
        """
    )
    suspend fun totalsForDate(date: String): DailyNutritionTotals

    @Query(
        """
        SELECT name, unit, COALESCE(SUM(value), 0.0) AS value
        FROM daily_extra_nutrient_logs
        WHERE logId IN (SELECT id FROM daily_food_logs WHERE date = :date)
        GROUP BY name, unit
        ORDER BY name
        """
    )
    suspend fun extraTotalsForDate(date: String): List<ExtraNutrientTotal>
}

@Database(
    entities = [
        ProductEntity::class,
        ProductExtraNutrientEntity::class,
        DailyFoodLogEntity::class,
        DailyExtraNutrientLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NutritionDatabase : RoomDatabase() {
    abstract fun nutritionDao(): NutritionDao

    companion object {
        @Volatile private var INSTANCE: NutritionDatabase? = null

        fun get(context: Context): NutritionDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                NutritionDatabase::class.java,
                "dailycut_nutrition.db"
            ).build().also { INSTANCE = it }
        }
    }
}

package com.littleone.dailycutreport

import android.content.Context
import androidx.room.ColumnInfo
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
    foreignKeys = [ForeignKey(
        entity = ProductEntity::class,
        parentColumns = ["barcode"],
        childColumns = ["barcode"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("barcode")]
)
data class ProductExtraNutrientEntity(
    val barcode: String,
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

@Entity(tableName = "daily_food_logs", indices = [Index("date"), Index("barcode")])
data class DailyFoodLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val barcode: String,
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

data class ExtraNutrientTotal(val name: String, val unit: String, val value: Double)

@Dao
interface NutritionDao {
    @Upsert suspend fun upsertProduct(product: ProductEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertExtraNutrients(nutrients: List<ProductExtraNutrientEntity>)
    @Query("DELETE FROM product_extra_nutrients WHERE barcode = :barcode")
    suspend fun clearExtraNutrients(barcode: String)
    @Query("SELECT * FROM products WHERE barcode = :barcode LIMIT 1")
    suspend fun productByBarcode(barcode: String): ProductEntity?
    @Query("SELECT * FROM product_extra_nutrients WHERE barcode = :barcode ORDER BY name")
    suspend fun extrasForProduct(barcode: String): List<ProductExtraNutrientEntity>
    @Query("SELECT * FROM products WHERE name LIKE '%' || :query || '%' OR brand LIKE '%' || :query || '%' OR barcode LIKE '%' || :query || '%' ORDER BY name LIMIT 100")
    fun observeProducts(query: String): Flow<List<ProductEntity>>

    @Transaction
    suspend fun saveProductWithExtras(product: ProductEntity, extras: List<ProductExtraNutrientEntity>) {
        val existing = productByBarcode(product.barcode)
        upsertProduct(product.copy(
            createdAt = existing?.createdAt ?: product.createdAt,
            updatedAt = System.currentTimeMillis()
        ))
        clearExtraNutrients(product.barcode)
        if (extras.isNotEmpty()) upsertExtraNutrients(extras)
    }

    @Insert suspend fun insertFoodLog(log: DailyFoodLogEntity): Long
    @Insert suspend fun insertDailyExtraLogs(logs: List<DailyExtraNutrientLogEntity>)
    @Update suspend fun updateFoodLog(log: DailyFoodLogEntity)
    @Query("SELECT * FROM daily_food_logs WHERE id = :id LIMIT 1")
    suspend fun foodLogById(id: Long): DailyFoodLogEntity?
    @Query("SELECT * FROM daily_food_logs WHERE date = :date ORDER BY loggedAt DESC")
    fun observeLogsForDate(date: String): Flow<List<DailyFoodLogEntity>>
    @Query("DELETE FROM daily_food_logs WHERE id = :id")
    suspend fun deleteLog(id: Long)

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
                caloriesPerServing = product.calories,
                proteinGPerServing = product.proteinG,
                sodiumMgPerServing = product.sodiumMg,
                carbsGPerServing = product.carbsG,
                fatGPerServing = product.fatG,
                sugarGPerServing = product.sugarG,
                fiberGPerServing = product.fiberG,
                saturatedFatGPerServing = product.saturatedFatG
            )
        )
        if (extras.isNotEmpty()) {
            insertDailyExtraLogs(extras.map {
                DailyExtraNutrientLogEntity(logId = logId, name = it.name, valuePerServing = it.value, unit = it.unit)
            })
        }
    }

    @Query("""
        SELECT
            COALESCE(SUM(caloriesPerServing * quantity), 0.0) AS calories,
            COALESCE(SUM(proteinGPerServing * quantity), 0.0) AS proteinG,
            COALESCE(SUM(sodiumMgPerServing * quantity), 0.0) AS sodiumMg,
            COALESCE(SUM(carbsGPerServing * quantity), 0.0) AS carbsG,
            COALESCE(SUM(fatGPerServing * quantity), 0.0) AS fatG,
            COALESCE(SUM(sugarGPerServing * quantity), 0.0) AS sugarG,
            COALESCE(SUM(fiberGPerServing * quantity), 0.0) AS fiberG,
            COALESCE(SUM(saturatedFatGPerServing * quantity), 0.0) AS saturatedFatG,
            COUNT(*) AS entries
        FROM daily_food_logs WHERE date = :date
    """)
    fun observeTotalsForDate(date: String): Flow<DailyNutritionTotals>

    @Query("""
        SELECT e.name, e.unit, COALESCE(SUM(e.valuePerServing * f.quantity), 0.0) AS value
        FROM daily_extra_nutrient_logs e
        JOIN daily_food_logs f ON f.id = e.logId
        WHERE f.date = :date
        GROUP BY e.name, e.unit ORDER BY e.name
    """)
    fun observeExtraTotalsForDate(date: String): Flow<List<ExtraNutrientTotal>>

    @Query("SELECT * FROM daily_reports WHERE date = :date LIMIT 1")
    fun observeDailyReport(date: String): Flow<DailyReportEntity?>
    @Query("SELECT * FROM daily_reports WHERE date = :date LIMIT 1")
    suspend fun dailyReport(date: String): DailyReportEntity?
    @Upsert suspend fun upsertDailyReport(report: DailyReportEntity)

    @Query("SELECT value FROM app_metadata WHERE `key` = :key LIMIT 1")
    suspend fun metadata(key: String): String?
    @Upsert suspend fun upsertMetadata(metadata: AppMetadataEntity)

    @Transaction
    suspend fun importLegacyReports(reports: List<DailyReportEntity>) {
        reports.forEach { upsertDailyReport(it) }
        upsertMetadata(AppMetadataEntity(LegacyReportImporter.IMPORT_KEY, "complete"))
    }
}

@Database(
    entities = [
        ProductEntity::class,
        ProductExtraNutrientEntity::class,
        DailyReportEntity::class,
        DailyFoodLogEntity::class,
        DailyExtraNutrientLogEntity::class,
        AppMetadataEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class NutritionDatabase : RoomDatabase() {
    abstract fun nutritionDao(): NutritionDao

    companion object {
        @Volatile private var INSTANCE: NutritionDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `daily_reports` (
                        `date` TEXT NOT NULL, `steps` INTEGER NOT NULL, `distanceKm` REAL NOT NULL,
                        `activeCalories` REAL NOT NULL, `totalCalories` REAL NOT NULL,
                        `exerciseSessions` INTEGER NOT NULL, `exerciseMinutes` INTEGER NOT NULL,
                        `nutritionCalories` REAL NOT NULL, `nutritionProteinG` REAL NOT NULL,
                        `nutritionSodiumMg` REAL NOT NULL, `nutritionRecords` INTEGER NOT NULL,
                        `healthConnectStatus` TEXT NOT NULL, `manualFoodCalories` REAL,
                        `manualProteinG` REAL, `manualSodiumMg` REAL, `manualBurnCalories` REAL,
                        `notes` TEXT NOT NULL, `savedAtEpochMs` INTEGER NOT NULL,
                        PRIMARY KEY(`date`)
                    )
                """.trimIndent())
                db.execSQL("CREATE TABLE IF NOT EXISTS `app_metadata` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
                db.execSQL("""
                    CREATE TABLE `daily_food_logs_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL,
                        `barcode` TEXT NOT NULL, `productName` TEXT NOT NULL, `brand` TEXT NOT NULL,
                        `servingLabel` TEXT NOT NULL, `quantity` REAL NOT NULL,
                        `caloriesPerServing` REAL NOT NULL, `proteinGPerServing` REAL NOT NULL,
                        `sodiumMgPerServing` REAL NOT NULL, `carbsGPerServing` REAL NOT NULL,
                        `fatGPerServing` REAL NOT NULL, `sugarGPerServing` REAL NOT NULL,
                        `fiberGPerServing` REAL NOT NULL, `saturatedFatGPerServing` REAL NOT NULL,
                        `loggedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO daily_food_logs_new
                    SELECT id, date, barcode, productName, brand, servingLabel, quantity,
                        CASE WHEN quantity > 0 THEN calories / quantity ELSE calories END,
                        CASE WHEN quantity > 0 THEN proteinG / quantity ELSE proteinG END,
                        CASE WHEN quantity > 0 THEN sodiumMg / quantity ELSE sodiumMg END,
                        CASE WHEN quantity > 0 THEN carbsG / quantity ELSE carbsG END,
                        CASE WHEN quantity > 0 THEN fatG / quantity ELSE fatG END,
                        CASE WHEN quantity > 0 THEN sugarG / quantity ELSE sugarG END,
                        CASE WHEN quantity > 0 THEN fiberG / quantity ELSE fiberG END,
                        CASE WHEN quantity > 0 THEN saturatedFatG / quantity ELSE saturatedFatG END,
                        loggedAt FROM daily_food_logs
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE `daily_extra_nutrient_logs_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `logId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL, `valuePerServing` REAL NOT NULL, `unit` TEXT NOT NULL,
                        FOREIGN KEY(`logId`) REFERENCES `daily_food_logs_new`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO daily_extra_nutrient_logs_new
                    SELECT e.id, e.logId, e.name,
                        CASE WHEN f.quantity > 0 THEN e.value / f.quantity ELSE e.value END,
                        e.unit
                    FROM daily_extra_nutrient_logs e JOIN daily_food_logs f ON f.id = e.logId
                """.trimIndent())
                db.execSQL("DROP TABLE daily_extra_nutrient_logs")
                db.execSQL("DROP TABLE daily_food_logs")
                db.execSQL("ALTER TABLE daily_food_logs_new RENAME TO daily_food_logs")
                db.execSQL("ALTER TABLE daily_extra_nutrient_logs_new RENAME TO daily_extra_nutrient_logs")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_food_logs_date` ON `daily_food_logs` (`date`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_food_logs_barcode` ON `daily_food_logs` (`barcode`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_daily_extra_nutrient_logs_logId` ON `daily_extra_nutrient_logs` (`logId`)")
            }
        }

        fun get(context: Context): NutritionDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, NutritionDatabase::class.java, "dailycut_nutrition.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { INSTANCE = it }
        }
    }
}

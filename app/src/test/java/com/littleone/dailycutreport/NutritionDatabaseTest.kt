package com.littleone.dailycutreport

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NutritionDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: NutritionDatabase

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NutritionDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    @Test fun editingProductDoesNotDeleteHistoricalFoodLogs() = runBlocking {
        val dao = database.nutritionDao()
        val original = ProductEntity(productId = "123", barcode = "123", name = "Original", calories = 100.0)
        dao.saveProductWithExtras(original, emptyList())
        dao.addProductToDate("2026-01-02", original, 2.0, emptyList())
        dao.saveProductWithExtras(original.copy(name = "Renamed", calories = 300.0), emptyList())

        val logs = dao.observeLogsForDate("2026-01-02").first()
        assertEquals(1, logs.size)
        assertEquals("Original", logs.single().productName)
        assertEquals(100.0, logs.single().caloriesPerServing, 0.0)
    }

    @Test fun totalsReactToQuantityAndSnapshotEdits() = runBlocking {
        val dao = database.nutritionDao()
        val product = ProductEntity(productId = "123", barcode = "123", name = "Meal", calories = 250.0, proteinG = 12.0)
        dao.saveProductWithExtras(product, emptyList())
        dao.addProductToDate("2026-01-02", product, 2.0, emptyList())
        val log = dao.observeLogsForDate("2026-01-02").first().single()
        dao.updateFoodLog(log.copy(quantity = 3.0, caloriesPerServing = 200.0))
        val totals = dao.observeTotalsForDate("2026-01-02").first()
        assertEquals(600.0, totals.calories, 0.0)
        assertEquals(36.0, totals.proteinG, 0.0)
    }

    @Test fun suspendTotalsMatchFoodLogSnapshots() = runBlocking {
        val dao = database.nutritionDao()
        val product = ProductEntity(
            productId = "123",
            barcode = "123",
            name = "Meal",
            calories = 250.0,
            proteinG = 12.0,
            sodiumMg = 300.0,
            carbsG = 30.0,
            fatG = 8.0,
            sugarG = 4.0,
            fiberG = 2.0,
            saturatedFatG = 1.0
        )
        dao.saveProductWithExtras(product, emptyList())
        dao.addProductToDate("2026-01-02", product, 2.0, emptyList())

        val totals = dao.totalsForDate("2026-01-02")

        assertEquals(500.0, totals.calories, 0.0)
        assertEquals(24.0, totals.proteinG, 0.0)
        assertEquals(600.0, totals.sodiumMg, 0.0)
        assertEquals(60.0, totals.carbsG, 0.0)
        assertEquals(16.0, totals.fatG, 0.0)
        assertEquals(8.0, totals.sugarG, 0.0)
        assertEquals(4.0, totals.fiberG, 0.0)
        assertEquals(2.0, totals.saturatedFatG, 0.0)
        assertEquals(1, totals.entries)
    }

    @Test fun quantityMutationAndDeleteUndoAreTransactional() = runBlocking {
        val dao = database.nutritionDao()
        val product = ProductEntity(productId = "meal", name = "Meal", calories = 100.0)
        val extras = listOf(ProductExtraNutrientEntity("meal", "Potassium", 200.0, "mg"))
        dao.saveProductWithExtras(product, extras)
        dao.addProductToDate("2026-01-02", product, 1.0, extras)
        val id = dao.foodLogsForDate("2026-01-02").single().id

        val edited = dao.updateFoodLogQuantitySnapshot(id, 2.5)!!
        assertEquals(100.0, edited.before.calories, 0.0)
        assertEquals(250.0, edited.after.calories, 0.0)
        val deleted = dao.deleteFoodLogSnapshot(id)!!
        assertEquals(0, deleted.after.entries)
        assertEquals(1, deleted.deleted!!.extras.size)
        val restored = dao.restoreFoodLogSnapshot(deleted.deleted!!)
        assertEquals(250.0, restored.after.calories, 0.0)
        assertEquals(1, dao.dailyExtrasForLog(id).size)
    }

    @Test fun ignoredLogPriceDoesNotCountTowardDailySpending() = runBlocking {
        val dao = database.nutritionDao()
        val product = ProductEntity(
            productId = "meal", name = "Meal", purchasePriceMicros = 20_000_000L,
            purchaseUnitServings = 2.0
        )
        dao.addProductToDate("2026-01-02", product, 2.0, emptyList(), 15_000_000L, true)

        val spending = dao.spendingForDate("2026-01-02")
        val log = dao.foodLogsForDate("2026-01-02").single()
        assertEquals(0L, spending.knownTotalMicros)
        assertEquals(0, spending.unknownEntries)
        assertEquals(15_000_000L, log.actualPaidTotalMicros)
        assertEquals(true, log.excludeCostFromBudget)
    }

    @Test fun bulkLoggingIsAtomicAndUsesOneExactCheckoutPrice() = runBlocking {
        val dao = database.nutritionDao()
        val first = ProductWithExtras(ProductEntity("first", name = "Rice", calories = 200.0))
        val second = ProductWithExtras(ProductEntity("second", name = "Chicken", calories = 300.0))
        dao.saveProductWithExtras(first.product, first.extras)
        dao.saveProductWithExtras(second.product, second.extras)

        val mutation = dao.addBulkPurchaseToDate(
            "2026-01-02", "bulk-id", "7-Eleven",
            listOf(BulkLogSelection("first", 1.0), BulkLogSelection("second", 2.0)),
            15_000_001L, false
        )

        val logs = dao.foodLogsForDate("2026-01-02")
        assertEquals(2, mutation.after.entries)
        assertEquals(800.0, mutation.after.calories, 0.0)
        assertEquals(setOf("bulk-id"), logs.mapNotNull { it.mealId }.toSet())
        assertEquals(setOf("7-Eleven"), logs.mapNotNull { it.mealName }.toSet())
        assertEquals(15_000_001L, dao.spendingForDate("2026-01-02").knownTotalMicros)
    }

    @Test fun multiScanLoggingPreservesPerItemPaidAndBudgetChoices() = runBlocking {
        val dao = database.nutritionDao()
        dao.saveProductWithExtras(ProductEntity("first", name = "Rice"), emptyList())
        dao.saveProductWithExtras(ProductEntity("second", name = "Drink"), emptyList())

        dao.addMultipleProductsToDate(
            "2026-01-02",
            listOf(
                BulkLogSelection("first", 2.0, actualPaidTotalMicros = 12_000_000L),
                BulkLogSelection("second", 1.0, actualPaidTotalMicros = 8_000_000L, excludeCostFromBudget = true)
            )
        )

        val logs = dao.foodLogsForDate("2026-01-02").associateBy { it.productId }
        assertEquals(12_000_000L, logs.getValue("first").actualPaidTotalMicros)
        assertEquals(false, logs.getValue("first").excludeCostFromBudget)
        assertEquals(8_000_000L, logs.getValue("second").actualPaidTotalMicros)
        assertEquals(true, logs.getValue("second").excludeCostFromBudget)
        assertEquals(12_000_000L, dao.spendingForDate("2026-01-02").knownTotalMicros)
    }

    @Test fun escapedWildcardSearchMatchesLiteralProductName() = runBlocking {
        val dao = database.nutritionDao()
        dao.saveProductWithExtras(ProductEntity(productId = "literal", name = "100% Whey_Protein"), emptyList())
        dao.saveProductWithExtras(ProductEntity(productId = "wild", name = "100X WheyAProtein"), emptyList())

        val matches = dao.observeProducts("100\\% Whey\\_Protein").first()

        assertEquals(listOf("literal"), matches.map { it.productId })
    }

    @Test fun recentProductsReturnsTenUniqueProductsOrderedByLatestLog() = runBlocking {
        val dao = database.nutritionDao()
        repeat(12) { index ->
            val product = ProductEntity(productId = "p$index", name = "Product $index")
            dao.saveProductWithExtras(product, emptyList())
            dao.insertFoodLog(DailyFoodLogEntity(
                date = "2026-01-02", productId = product.productId,
                productName = product.name, loggedAt = index.toLong()
            ))
        }
        dao.insertFoodLog(DailyFoodLogEntity(
            date = "2026-01-03", productId = "p0", productName = "Product 0", loggedAt = 100L
        ))

        val recent = dao.observeRecentProducts().first()

        assertEquals(10, recent.size)
        assertEquals(listOf("p0", "p11", "p10", "p9", "p8", "p7", "p6", "p5", "p4", "p3"), recent.map { it.productId })
    }

    @Test fun clearManualOverridesRemovesLegacyOverrideValues() = runBlocking {
        val dao = database.nutritionDao()
        dao.upsertDailyReport(DailyReportEntity(
            date = "2026-01-02",
            manualFoodCalories = 100.0,
            manualProteinG = 20.0,
            manualSodiumMg = 300.0,
            manualBurnCalories = 2_000.0,
            notes = "legacy"
        ))

        dao.clearManualOverrides(updatedAt = 123L)
        val report = dao.dailyReport("2026-01-02")

        assertNotNull(report)
        assertEquals(null, report?.manualFoodCalories)
        assertEquals(null, report?.manualProteinG)
        assertEquals(null, report?.manualSodiumMg)
        assertEquals(null, report?.manualBurnCalories)
        assertEquals("", report?.notes)
        assertEquals(123L, report?.savedAtEpochMs)
    }

    @Test fun migrationOneToTwoRetainsAndConvertsFoodSnapshots() = runBlocking {
        database.close()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = createVersionOneSchema(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build()
        )
        helper.writableDatabase.apply {
            execSQL("INSERT INTO products VALUES ('4711089912108','Meal','','1 serving',100,10,20,0,0,0,0,0,'',1,1)")
            execSQL("INSERT INTO products VALUES ('CUSTOM-MEAL','Custom','','1 serving',50,5,10,0,0,0,0,0,'',1,1)")
            execSQL("INSERT INTO product_extra_nutrients VALUES ('CUSTOM-MEAL','BCAA',300,'mg')")
            execSQL("INSERT INTO daily_food_logs VALUES (1,'2026-01-02','4711089912108','Meal','','1 serving',2,200,20,40,0,0,0,0,0,1)")
            execSQL("INSERT INTO daily_extra_nutrient_logs VALUES (1,1,'Potassium',400,'mg')")
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, NutritionDatabase::class.java, name)
            .addMigrations(
                NutritionDatabase.MIGRATION_1_2,
                NutritionDatabase.MIGRATION_2_3,
                NutritionDatabase.MIGRATION_3_4,
                NutritionDatabase.MIGRATION_4_5,
                NutritionDatabase.MIGRATION_5_6,
                NutritionDatabase.MIGRATION_6_7
            )
            .allowMainThreadQueries().build()
        migrated.openHelper.writableDatabase
        val log = migrated.nutritionDao().observeLogsForDate("2026-01-02").first().single()
        assertEquals(100.0, log.caloriesPerServing, 0.0)
        assertEquals(2.0, log.quantity, 0.0)
        assertEquals("4711089912108", migrated.nutritionDao().productById("4711089912108")?.barcode)
        assertEquals(null, migrated.nutritionDao().productById("CUSTOM-MEAL")?.barcode)
        assertEquals(1, migrated.nutritionDao().extrasForProduct("CUSTOM-MEAL").size)
        migrated.close()
        context.deleteDatabase(name)
        database = Room.inMemoryDatabaseBuilder(context, NutritionDatabase::class.java).allowMainThreadQueries().build()
    }

    @Test fun productCorrectionPropagatesNutritionButPreservesLoggedCost() = runBlocking {
        val dao = database.nutritionDao()
        val original = ProductEntity(
            productId = "meal", name = "Original", calories = 100.0,
            purchasePriceMicros = 20_000_000L, purchaseUnitServings = 2.0
        )
        dao.saveProductWithExtras(original, listOf(ProductExtraNutrientEntity("meal", "BCAA", 1.0, "g")))
        dao.addProductToDate("2026-01-02", original, 2.0, dao.extrasForProduct("meal"), 15_000_000L)

        val mutation = dao.saveProductAndUpdateLinkedLogs(
            original.copy(name = "Corrected", calories = 150.0, purchasePriceMicros = 30_000_000L),
            listOf(ProductExtraNutrientEntity("meal", "Potassium", 100.0, "mg"))
        )
        val log = dao.foodLogsForDate("2026-01-02").single()

        assertEquals(1, mutation.linkedEntriesUpdated)
        assertEquals(setOf("2026-01-02"), mutation.affectedDates)
        assertEquals("Corrected", log.productName)
        assertEquals(150.0, log.caloriesPerServing, 0.0)
        assertEquals(10_000_000L, log.catalogCostPerServingMicros)
        assertEquals(15_000_000L, log.actualPaidTotalMicros)
        assertEquals("Potassium", dao.dailyExtrasForLog(log.id).single().name)
    }

    @Test fun spendingUsesActualPaidIncludingExplicitFreeItems() = runBlocking {
        val dao = database.nutritionDao()
        val priced = ProductEntity(productId = "priced", name = "Priced", purchasePriceMicros = 12_000_000L, purchaseUnitServings = 2.0)
        val unknown = ProductEntity(productId = "unknown", name = "Unknown")
        dao.saveProductWithExtras(priced, emptyList())
        dao.saveProductWithExtras(unknown, emptyList())
        dao.addProductToDate("2026-01-02", priced, 2.0, emptyList())
        dao.addProductToDate("2026-01-02", priced, 1.0, emptyList(), 0L)
        dao.addProductToDate("2026-01-02", unknown, 1.0, emptyList())

        val spending = dao.spendingForDate("2026-01-02")

        assertEquals(12_000_000L, spending.knownTotalMicros)
        assertEquals(1, spending.unknownEntries)
    }

    private fun createVersionOneSchema(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE products (barcode TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, brand TEXT NOT NULL, servingLabel TEXT NOT NULL, calories REAL NOT NULL, proteinG REAL NOT NULL, sodiumMg REAL NOT NULL, carbsG REAL NOT NULL, fatG REAL NOT NULL, sugarG REAL NOT NULL, fiberG REAL NOT NULL, saturatedFatG REAL NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE product_extra_nutrients (barcode TEXT NOT NULL, name TEXT NOT NULL, value REAL NOT NULL, unit TEXT NOT NULL, PRIMARY KEY(barcode,name), FOREIGN KEY(barcode) REFERENCES products(barcode) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_product_extra_nutrients_barcode ON product_extra_nutrients(barcode)")
        db.execSQL("CREATE TABLE daily_food_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, date TEXT NOT NULL, barcode TEXT NOT NULL, productName TEXT NOT NULL, brand TEXT NOT NULL, servingLabel TEXT NOT NULL, quantity REAL NOT NULL, calories REAL NOT NULL, proteinG REAL NOT NULL, sodiumMg REAL NOT NULL, carbsG REAL NOT NULL, fatG REAL NOT NULL, sugarG REAL NOT NULL, fiberG REAL NOT NULL, saturatedFatG REAL NOT NULL, loggedAt INTEGER NOT NULL, FOREIGN KEY(barcode) REFERENCES products(barcode) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_daily_food_logs_date ON daily_food_logs(date)")
        db.execSQL("CREATE INDEX index_daily_food_logs_barcode ON daily_food_logs(barcode)")
        db.execSQL("CREATE TABLE daily_extra_nutrient_logs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, logId INTEGER NOT NULL, name TEXT NOT NULL, value REAL NOT NULL, unit TEXT NOT NULL, FOREIGN KEY(logId) REFERENCES daily_food_logs(id) ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_daily_extra_nutrient_logs_logId ON daily_extra_nutrient_logs(logId)")
    }
}

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
        val original = ProductEntity("123", "Original", calories = 100.0)
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
        val product = ProductEntity("123", "Meal", calories = 250.0, proteinG = 12.0)
        dao.saveProductWithExtras(product, emptyList())
        dao.addProductToDate("2026-01-02", product, 2.0, emptyList())
        val log = dao.observeLogsForDate("2026-01-02").first().single()
        dao.updateFoodLog(log.copy(quantity = 3.0, caloriesPerServing = 200.0))
        val totals = dao.observeTotalsForDate("2026-01-02").first()
        assertEquals(600.0, totals.calories, 0.0)
        assertEquals(36.0, totals.proteinG, 0.0)
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
            execSQL("INSERT INTO products VALUES ('123','Meal','','1 serving',100,10,20,0,0,0,0,0,'',1,1)")
            execSQL("INSERT INTO daily_food_logs VALUES (1,'2026-01-02','123','Meal','','1 serving',2,200,20,40,0,0,0,0,0,1)")
            execSQL("INSERT INTO daily_extra_nutrient_logs VALUES (1,1,'Potassium',400,'mg')")
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, NutritionDatabase::class.java, name)
            .addMigrations(NutritionDatabase.MIGRATION_1_2)
            .allowMainThreadQueries().build()
        migrated.openHelper.writableDatabase
        val log = migrated.nutritionDao().observeLogsForDate("2026-01-02").first().single()
        assertEquals(100.0, log.caloriesPerServing, 0.0)
        assertEquals(2.0, log.quantity, 0.0)
        assertNotNull(migrated.nutritionDao().productByBarcode("123"))
        migrated.close()
        context.deleteDatabase(name)
        database = Room.inMemoryDatabaseBuilder(context, NutritionDatabase::class.java).allowMainThreadQueries().build()
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


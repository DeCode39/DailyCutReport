package com.littleone.dailycutreport

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutritionMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NutritionDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test fun migrationThreeToFourPreservesRowsAndDefaultsNewFields() {
        helper.createDatabase(DATABASE_NAME, 3).apply {
            execSQL("INSERT INTO products (productId,barcode,name,brand,servingLabel,calories,proteinG,sodiumMg,carbsG,fatG,sugarG,fiberG,saturatedFatG,notes,createdAt,updatedAt) VALUES ('meal',NULL,'Meal','','1 serving',100,10,20,0,0,0,0,0,'',1,1)")
            execSQL("INSERT INTO daily_food_logs (id,date,productId,barcode,productName,brand,servingLabel,quantity,caloriesPerServing,proteinGPerServing,sodiumMgPerServing,carbsGPerServing,fatGPerServing,sugarGPerServing,fiberGPerServing,saturatedFatGPerServing,loggedAt) VALUES (1,'2026-07-12','meal',NULL,'Meal','','1 serving',2,100,10,20,0,0,0,0,0,1)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            4,
            true,
            NutritionDatabase.MIGRATION_3_4
        )
        migrated.query("SELECT name,purchasePriceMicros,purchaseUnitServings,includeInPlanner,plannerItemType,alwaysIncludeInPlanner FROM products WHERE productId='meal'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Meal", cursor.getString(0))
            assertNull(if (cursor.isNull(1)) null else cursor.getLong(1))
            assertEquals(1.0, cursor.getDouble(2), 0.0)
            assertEquals(1, cursor.getInt(3))
            assertEquals("FOOD", cursor.getString(4))
            assertEquals(0, cursor.getInt(5))
        }
        migrated.query("SELECT catalogCostPerServingMicros,actualPaidTotalMicros,excludeCostFromBudget FROM daily_food_logs WHERE id=1").use { cursor ->
            cursor.moveToFirst()
            assertEquals(true, cursor.isNull(0))
            assertEquals(true, cursor.isNull(1))
            assertEquals(0, cursor.getInt(2))
        }
        migrated.query("SELECT mode,calories,currencyCode FROM user_goals WHERE id=1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("CALORIE", cursor.getString(0))
            assertEquals(1850.0, cursor.getDouble(1), 0.0)
            assertEquals("TWD", cursor.getString(2))
        }
        migrated.close()
    }

    @Test fun migrationFourToFivePreservesLogsAndAddsMealGrouping() {
        helper.createDatabase("migration-4-5", 4).apply {
            execSQL("INSERT INTO products (productId,barcode,name,brand,servingLabel,calories,proteinG,sodiumMg,carbsG,fatG,sugarG,fiberG,saturatedFatG,purchasePriceMicros,purchaseUnitServings,includeInPlanner,plannerItemType,alwaysIncludeInPlanner,notes,createdAt,updatedAt) VALUES ('meal',NULL,'Meal','','1 serving',100,10,20,0,0,0,0,0,NULL,1,1,'FOOD',0,'',1,1)")
            execSQL("INSERT INTO daily_food_logs (id,date,productId,barcode,productName,brand,servingLabel,quantity,caloriesPerServing,proteinGPerServing,sodiumMgPerServing,carbsGPerServing,fatGPerServing,sugarGPerServing,fiberGPerServing,saturatedFatGPerServing,catalogCostPerServingMicros,actualPaidTotalMicros,excludeCostFromBudget,loggedAt) VALUES (1,'2026-07-12','meal',NULL,'Meal','','1 serving',2,100,10,20,0,0,0,0,0,NULL,NULL,0,1)")
            close()
        }

        val migrated = helper.runMigrationsAndValidate("migration-4-5", 5, true, NutritionDatabase.MIGRATION_4_5)
        migrated.query("SELECT productName,quantity,mealId,mealName FROM daily_food_logs WHERE id=1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Meal", cursor.getString(0))
            assertEquals(2.0, cursor.getDouble(1), 0.0)
            assertEquals(true, cursor.isNull(2))
            assertEquals(true, cursor.isNull(3))
        }
        migrated.close()
    }

    @Test fun migrationFiveToSixAddsHealthTablesWithoutChangingExistingData() {
        helper.createDatabase("migration-5-6", 5).apply {
            execSQL("INSERT INTO daily_reports (date,steps,distanceKm,activeCalories,totalCalories,exerciseSessions,exerciseMinutes,nutritionCalories,nutritionProteinG,nutritionSodiumMg,nutritionRecords,healthConnectStatus,manualFoodCalories,manualProteinG,manualSodiumMg,manualBurnCalories,notes,savedAtEpochMs) VALUES ('2026-07-17',1234,1.2,100,2200,1,30,0,0,0,0,'loaded',NULL,NULL,NULL,NULL,'',1)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate("migration-5-6", 6, true, NutritionDatabase.MIGRATION_5_6)
        migrated.query("SELECT steps,totalCalories FROM daily_reports WHERE date='2026-07-17'").use { cursor ->
            cursor.moveToFirst()
            assertEquals(1234L, cursor.getLong(0))
            assertEquals(2200.0, cursor.getDouble(1), 0.0)
        }
        migrated.query("SELECT weightUnit,targetWeightKg FROM health_profile WHERE id=1").use { cursor ->
            cursor.moveToFirst()
            assertEquals("KG", cursor.getString(0))
            assertEquals(true, cursor.isNull(1))
        }
        migrated.close()
    }

    @Test fun migrationSixToSevenAddsFavoritesWithoutChangingProducts() {
        helper.createDatabase("migration-6-7", 6).apply {
            execSQL("INSERT INTO products (productId,barcode,name,brand,servingLabel,calories,proteinG,sodiumMg,carbsG,fatG,sugarG,fiberG,saturatedFatG,purchasePriceMicros,purchaseUnitServings,includeInPlanner,plannerItemType,alwaysIncludeInPlanner,notes,createdAt,updatedAt) VALUES ('meal',NULL,'Meal','','1 serving',100,10,20,0,0,0,0,0,NULL,1,1,'FOOD',0,'',1,1)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate("migration-6-7", 7, true, NutritionDatabase.MIGRATION_6_7)
        migrated.query("SELECT name,favorite FROM products WHERE productId='meal'").use { cursor ->
            cursor.moveToFirst()
            assertEquals("Meal", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        migrated.close()
    }

    private companion object { const val DATABASE_NAME = "migration-3-4" }
}

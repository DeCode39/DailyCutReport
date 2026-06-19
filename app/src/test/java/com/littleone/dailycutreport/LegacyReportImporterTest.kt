package com.littleone.dailycutreport

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LegacyReportImporterTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("daily_reports", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun importIsIdempotentAndMapsZeroToUnset() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, NutritionDatabase::class.java).allowMainThreadQueries().build()
        val json = JSONObject().apply {
            put("steps", 1234)
            put("totalCalories", 2200.0)
            put("foodCalories", 0.0)
            put("proteinG", 140.0)
            put("notes", "legacy")
        }
        context.getSharedPreferences("daily_reports", Context.MODE_PRIVATE).edit()
            .putString("2026-01-02", json.toString()).commit()
        val importer = LegacyReportImporter(context, database.nutritionDao())
        importer.importIfNeeded()
        importer.importIfNeeded()

        val imported = database.nutritionDao().dailyReport("2026-01-02")!!
        assertEquals(1234, imported.steps)
        assertNull(imported.manualFoodCalories)
        assertEquals(140.0, imported.manualProteinG!!, 0.0)
        assertEquals("complete", database.nutritionDao().metadata(LegacyReportImporter.IMPORT_KEY))
        database.close()
    }
}

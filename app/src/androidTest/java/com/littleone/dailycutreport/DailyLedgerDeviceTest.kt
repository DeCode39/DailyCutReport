package com.littleone.dailycutreport

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test

class DailyLedgerDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun ledgerTabsRetainTheirActionsAndNavigation() {
        val date = ViewModelProvider(compose.activity)[ReportDateViewModel::class.java]
        val original = date.selectedDate.value
        val fixture = LocalDate.of(2002, 2, 10)
        val db = NutritionDatabase.get(compose.activity)
        val dao = db.nutritionDao()
        val logIds = mutableListOf<Long>()
        val tag = "Ledger device test ${java.util.UUID.randomUUID()}"
        val days = (0..13).map { fixture.minusDays(it.toLong()) }
        val foods = listOf("Rice bowl" to 510.0, "Store lunch" to 740.0)
        var seeded = false
        try {
            runBlocking {
                db.withTransaction {
                    check(days.all { dao.dailyReport(it.toString()) == null && dao.foodLogsForDate(it.toString()).isEmpty() })
                    days.forEachIndexed { i, date ->
                        val noon = date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        dao.upsertDailyReport(DailyReportEntity(date.toString(), totalCalories = 2300.0,
                            nutritionRecords = 1, nutritionCalories = 1250.0, savedAtEpochMs = noon, notes = tag))
                        dao.upsertWeightEntry(WeightEntryEntity("$tag-$i", date.toString(), noon, 74.6 + i * 0.05, "MANUAL"))
                    }
                    foods.forEach { (name, kcal) ->
                        dao.upsertProduct(ProductEntity("$tag-$name", name = name, calories = kcal, favorite = name == "Rice bowl"))
                        logIds += dao.insertFoodLog(DailyFoodLogEntity(date = fixture.toString(), productId = "$tag-$name", productName = name,
                            caloriesPerServing = kcal, proteinGPerServing = 42.0))
                    }
                }
                seeded = true
            }
            compose.runOnUiThread { date.select(fixture) }
            fun tab(name: String) = compose.onNode(hasText(name) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
            fun capture(name: String) {
                if (InstrumentationRegistry.getArguments().getString("captureLedger") != "true") return
                val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
                val ctx = InstrumentationRegistry.getInstrumentation().targetContext
                ctx.openFileOutput("ledger-$name.png", 0).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
            tab("Today").performClick()
            compose.onNodeWithText("DailyCutReport").assertIsDisplayed()
            compose.onNodeWithContentDescription("Refresh last 28 days").assertIsDisplayed()
            compose.onNodeWithContentDescription("Copy daily report JSON").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("${formatCalories(1050.0)}  kcal").fetchSemanticsNodes().isNotEmpty() }
            capture("today")
            compose.onNodeWithText("More nutrients").performScrollTo().performClick()
            compose.onNodeWithText("Show less").performScrollTo().performClick()
            tab("Foods").performClick()
            compose.onNodeWithText("New food").assertIsDisplayed()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Rice bowl").fetchSemanticsNodes().isNotEmpty() }
            capture("foods")
            tab("Health").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("LATEST WEIGHT").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Weight").assertHasClickAction()
            capture("health")
            tab("Settings").performClick()
            compose.onNodeWithText("PLANNING").assertIsDisplayed()
            capture("settings")
            compose.onNodeWithText("Food database").performClick()
            tab("Settings").assertIsSelected()
            compose.onNodeWithText("‹ Back").performClick()
            tab("Today").performClick()
            // On a compact screen the restored lazy list can have disposed its header.
            // Scroll the list itself before asserting the action is visible.
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex))
                .performScrollToIndex(0)
            compose.onNodeWithContentDescription("Copy daily report JSON").assertIsDisplayed().assertHasClickAction()
        } finally {
            compose.runOnUiThread { date.select(original) }
            if (seeded) runBlocking {
                db.withTransaction {
                    logIds.forEach { dao.deleteLog(it) }
                    foods.forEach { (name, _) -> dao.deleteCatalogProduct("$tag-$name") }
                    days.indices.forEach { dao.deleteWeightEntry("$tag-$it") }
                    db.openHelper.writableDatabase.execSQL("DELETE FROM daily_reports WHERE notes = ?", arrayOf(tag))
                }
            }
        }
    }
}

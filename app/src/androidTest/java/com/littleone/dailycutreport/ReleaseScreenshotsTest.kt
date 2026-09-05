package com.littleone.dailycutreport

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/** CI-only synthetic demo data; never reads a user's device or copies old release images. */
class ReleaseScreenshotsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun captureReleaseTabs() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureReleaseMedia") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = (context.applicationContext as DailyCutApplication).container.repository
        val today = LocalDate.now()
        runBlocking {
            repository.initialize()
            val dao = NutritionDatabase.get(context).nutritionDao()
            dao.insertDailyReports(listOf(DailyReportEntity(today.toString(), steps = 6500,
                distanceKm = 4.8, activeCalories = 320.0, totalCalories = 2350.0,
                healthConnectStatus = "Synthetic release demo")))
            val food = ProductEntity(productId = "release-demo", name = "Demo rice bowl", brand = "Sample data",
                calories = 520.0, proteinG = 38.0, carbsG = 65.0, fatG = 12.0, fiberG = 6.0,
                sodiumMg = 600.0, purchasePriceMicros = 80000000)
            repository.saveProduct(food, emptyList())
            repository.addProduct(today, ProductWithExtras(food), 1.0)
            repository.addManualWeight(today, LocalTime.of(8, 0), 75.0)
        }
        for (tab in listOf("Today", "Foods", "Health", "Settings")) {
            compose.onNode(hasText(tab) and hasClickAction() and
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).performClick()
            compose.waitForIdle()
            SystemClock.sleep(700)
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            val directory = File(context.getExternalFilesDir(null), "release-media").apply { mkdirs() }
            File(directory, "${tab.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}

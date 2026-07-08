package com.littleone.dailycutreport

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class NutritionLabelFixtureOcrTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val processor = AndroidNutritionImagePreprocessor(context)
    private val ocr = DefaultNutritionLabelOcr(MlKitTextRecognizerEngine(context), processor)

    @Test fun recognizesBaseUGuidedCropValues() = runBlocking {
        val review = recognize("baseu_label.jpg")
        assertValue(review, OcrField.CARBS, 25.8, tolerance = 0.1)
        assertValue(review, OcrField.SATURATED_FAT, 2.7, tolerance = 0.1)
    }

    @Test fun recognizesMonsterCoreRowsForManualReview() = runBlocking {
        val review = recognize("monster_label.jpg")
        val calories = review.proposals.singleOrNull { it.field == OcrField.CALORIES }
        assertTrue("Monster calories row should be visible for manual correction", calories != null)
        assertTrue("Calories source row should be retained", calories!!.alternatives.values.flatten().any { it.sourceText.isNotBlank() })
        assertValue(review, OcrField.CARBS, 2.1, tolerance = 0.1)
        assertValue(review, OcrField.SODIUM, 195.0, tolerance = 1.0)
    }

    private suspend fun recognize(name: String): OcrReview {
        processor.cleanup()
        val captureDirectory = File(context.cacheDir, "ocr_captures").apply { mkdirs() }
        val sourceFile = File(captureDirectory, name)
        testContext.assets.open("ocr/$name").use { input -> sourceFile.outputStream().use(input::copyTo) }
        val source = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sourceFile)
        val prepared = processor.prepare(source, CropRegion(), 0)
        val result = ocr.extract(listOf(prepared.uri), OcrLanguage.AUTO)
        processor.delete(prepared.uri)
        sourceFile.delete()
        assertTrue("OCR failed for $name: $result", result is OcrResult.Success)
        return (result as OcrResult.Success).review
    }

    private fun assertValue(review: OcrReview, field: OcrField, expected: Double, tolerance: Double) {
        val proposal = review.proposals.singleOrNull { it.field == field }
        assertTrue("Missing $field in $review", proposal != null)
        val actual = proposal!!.candidates[OcrBasis.PER_SERVING]?.value
        assertTrue("Missing per-serving $field in $review", actual != null)
        assertEquals(expected, actual!!, tolerance)
    }
}

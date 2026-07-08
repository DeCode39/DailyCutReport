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
class NutritionImagePreprocessorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val processor = AndroidNutritionImagePreprocessor(context)

    @Test fun preparesRotatedCropCreatesVariantsAndCleansUp() = runBlocking {
        processor.cleanup()
        val captureDirectory = File(context.cacheDir, "ocr_captures").apply { mkdirs() }
        val sourceFile = File(captureDirectory, "baseu_fixture.jpg")
        testContext.assets.open("ocr/baseu_label.jpg").use { input -> sourceFile.outputStream().use(input::copyTo) }
        val source = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", sourceFile)

        val prepared = processor.prepare(source, CropRegion(0.1f, 0.1f, 0.9f, 0.9f), 90)
        val variants = processor.variants(prepared.uri)

        assertEquals(90, prepared.rotationDegrees)
        assertTrue(prepared.quality.width >= 900)
        assertTrue(prepared.fullFrameUri != prepared.uri)
        assertEquals(OcrImageVariant.ORIGINAL, variants.first().variant)
        assertEquals(setOf(OcrImageVariant.ORIGINAL, OcrImageVariant.CONTRAST, OcrImageVariant.SHARPENED), variants.map { it.variant }.toSet())

        variants.filter { it.temporary }.forEach { processor.delete(it.uri) }
        processor.delete(prepared.uri)
        processor.delete(prepared.fullFrameUri)
        sourceFile.delete()
        assertTrue(File(context.cacheDir, "ocr_prepared").listFiles().isNullOrEmpty())
    }
}

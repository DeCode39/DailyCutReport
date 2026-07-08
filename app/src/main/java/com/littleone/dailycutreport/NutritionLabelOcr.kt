package com.littleone.dailycutreport

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

interface TextRecognizerEngine {
    suspend fun recognize(uri: Uri, language: OcrLanguage): OcrTextDocument
}

interface NutritionLabelOcr {
    suspend fun extract(images: List<Uri>, language: OcrLanguage): OcrResult
}

class MlKitTextRecognizerEngine(private val context: Context) : TextRecognizerEngine {
    override suspend fun recognize(uri: Uri, language: OcrLanguage): OcrTextDocument = withContext(Dispatchers.IO) {
        require(language != OcrLanguage.AUTO)
        val image = InputImage.fromFilePath(context, uri)
        val recognizer = recognizer(language)
        try {
            suspendCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result ->
                        val boxes = result.textBlocks.flatMap { block ->
                            block.lines.mapNotNull { line ->
                                line.boundingBox?.let { bounds ->
                                    OcrTextBox(
                                        text = line.text,
                                        left = bounds.left,
                                        top = bounds.top,
                                        right = bounds.right,
                                        bottom = bounds.bottom
                                    )
                                }
                            }
                        }
                        continuation.resume(
                            OcrTextDocument(
                                language = language,
                                text = result.text,
                                boxes = boxes,
                                imageWidth = image.width,
                                imageHeight = image.height
                            )
                        )
                    }
                    .addOnFailureListener(continuation::resumeWithException)
            }
        } finally {
            recognizer.close()
        }
    }

    private fun recognizer(language: OcrLanguage): TextRecognizer = when (language) {
        OcrLanguage.ENGLISH -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        OcrLanguage.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        OcrLanguage.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        OcrLanguage.AUTO -> error("Auto is expanded before recognition")
    }
}

class DefaultNutritionLabelOcr(
    private val engine: TextRecognizerEngine,
    private val preprocessor: NutritionImagePreprocessor,
    private val parser: NutritionLabelParser = NutritionLabelParser()
) : NutritionLabelOcr {
    override suspend fun extract(images: List<Uri>, language: OcrLanguage): OcrResult {
        if (images.isEmpty()) return OcrResult.Failed("Add at least one nutrition-label image.")
        val languages = if (language == OcrLanguage.AUTO) {
            listOf(OcrLanguage.ENGLISH, OcrLanguage.CHINESE, OcrLanguage.JAPANESE)
        } else listOf(language)
        val documents = mutableListOf<OcrTextDocument>()
        images.take(3).forEachIndexed { imageIndex, image ->
            val variants = runCatching { preprocessor.variants(image) }
                .getOrElse {
                    Log.w(TAG, "Could not prepare OCR image variants", it)
                    listOf(OcrVariantImage(image, OcrImageVariant.ORIGINAL, temporary = false))
                }
            try {
                languages.forEach { script ->
                    val recognized = variants.mapNotNull { variant ->
                        runCatching { engine.recognize(variant.uri, script) }
                            .onFailure { Log.w(TAG, "On-device OCR failed for $script", it) }
                            .getOrNull()
                            ?.takeIf { it.text.isNotBlank() }
                            ?.let { document ->
                                val score = parser.documentScore(document)
                                document.copy(
                                    sourceImage = imageIndex,
                                    preprocessingVariant = variant.variant,
                                    coverageScore = score
                                )
                            }
                    }
                    recognized.maxByOrNull { it.coverageScore }?.let(documents::add)
                }
            } finally {
                variants.filter { it.temporary }.forEach { preprocessor.delete(it.uri) }
            }
        }
        if (documents.isEmpty()) return OcrResult.Failed("No text could be recognized from these images.")
        val review = parser.parse(documents)
        return if (review.proposals.isEmpty()) {
            OcrResult.Failed(review.warnings.firstOrNull() ?: "No nutrition values were recognized.")
        } else OcrResult.Success(review)
    }

    companion object { private const val TAG = "NutritionLabelOcr" }
}

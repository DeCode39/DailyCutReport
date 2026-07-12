package com.littleone.dailycutreport

enum class OcrLanguage(val label: String) {
    AUTO("Auto"), ENGLISH("English"), CHINESE("Chinese"), JAPANESE("Japanese")
}

enum class OcrBasis(val label: String) {
    PER_SERVING("Per serving"), PER_100_G("Per 100 g"), PER_100_ML("Per 100 ml")
}

enum class OcrEngine(val label: String) { ML_KIT("Standard OCR"), PP_OCR_V6_TINY("Enhanced OCR") }

enum class OcrMode { STANDARD, ENHANCED }

enum class OcrImageVariant { ORIGINAL, CONTRAST, SHARPENED }

data class CropRegion(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    fun normalized(minimumSpan: Float = 0.1f): CropRegion {
        val safeLeft = left.coerceIn(0f, 1f - minimumSpan)
        val safeTop = top.coerceIn(0f, 1f - minimumSpan)
        return CropRegion(
            left = safeLeft,
            top = safeTop,
            right = right.coerceIn(safeLeft + minimumSpan, 1f),
            bottom = bottom.coerceIn(safeTop + minimumSpan, 1f)
        )
    }
}

data class ImageQuality(
    val width: Int,
    val height: Int,
    val contrast: Double,
    val sharpness: Double,
    val glareFraction: Double
) {
    val warnings: List<String> get() = buildList {
        if (width < 900 || height < 600) add("Crop is low resolution; move closer to the label.")
        if (contrast < 28.0) add("Crop has low contrast; use brighter, even lighting.")
        if (sharpness < 10.0) add("Crop may be blurry; hold the phone steady and refocus.")
        if (glareFraction > 0.08) add("Strong glare may hide text; change the camera angle.")
    }
}

data class PreparedOcrImage(
    val sourceUri: android.net.Uri,
    val uri: android.net.Uri,
    val rotationDegrees: Int,
    val crop: CropRegion,
    val quality: ImageQuality
)

data class OcrVariantImage(
    val uri: android.net.Uri,
    val variant: OcrImageVariant,
    val temporary: Boolean
)

enum class OcrField(val label: String, val storageUnit: String) {
    CALORIES("Calories", "kcal"),
    PROTEIN("Protein", "g"),
    SODIUM("Sodium", "mg"),
    CARBS("Carbohydrates", "g"),
    FAT("Fat", "g"),
    SUGAR("Sugar", "g"),
    FIBER("Fiber", "g"),
    SATURATED_FAT("Saturated fat", "g")
}

data class OcrCandidate(
    val field: OcrField,
    val value: Double,
    val unit: String,
    val basis: OcrBasis,
    val sourceText: String,
    val converted: Boolean = false,
    val language: OcrLanguage = OcrLanguage.AUTO,
    val engine: OcrEngine = OcrEngine.ML_KIT,
    val confidence: Double = 1.0,
    val sourceImage: Int = 0
) {
    val candidateId: String get() = listOf(
        engine.name,
        sourceImage,
        this@OcrCandidate.field.name,
        basis.name,
        value,
        sourceText.hashCode()
    ).joinToString(":")
}

data class OcrFieldProposal(
    val field: OcrField,
    val candidates: Map<OcrBasis, OcrCandidate>,
    val alternatives: Map<OcrBasis, List<OcrCandidate>> = candidates.mapValues { listOf(it.value) }
)

data class OcrSelectionKey(val field: OcrField, val basis: OcrBasis)

data class OcrReview(
    val proposals: List<OcrFieldProposal> = emptyList(),
    val servingLabel: String? = null,
    val servingsPerContainer: Double? = null,
    val warnings: List<String> = emptyList(),
    val documents: List<OcrTextDocument> = emptyList()
) {
    val availableBases: Set<OcrBasis> get() = proposals.flatMap {
        it.candidates.keys + it.alternatives.keys
    }.toSet()

    fun draftFor(
        basis: OcrBasis,
        selections: Map<OcrSelectionKey, String> = emptyMap()
    ): OcrNutritionDraft {
        val values = proposals.mapNotNull { proposal ->
            val selectedId = selections[OcrSelectionKey(proposal.field, basis)]
            val candidate = selectedId?.let { id ->
                proposal.alternatives[basis]?.firstOrNull { it.candidateId == id }
            } ?: proposal.candidates[basis]
            candidate?.let { proposal.field to it.value }
        }.toMap()
        val selectedCandidates = proposals.mapNotNull { proposal ->
            val selectedId = selections[OcrSelectionKey(proposal.field, basis)]
            selectedId?.let { id -> proposal.alternatives[basis]?.firstOrNull { it.candidateId == id } }
                ?: proposal.candidates[basis]
        }
        return OcrNutritionDraft(
            basis = basis,
            servingLabel = servingLabel ?: when (basis) {
                OcrBasis.PER_SERVING -> "1 serving"
                OcrBasis.PER_100_G -> "100 g"
                OcrBasis.PER_100_ML -> "100 ml"
            },
            values = values,
            conversions = selectedCandidates.filter { it.converted }.map { it.field }.toSet()
        )
    }
}

data class OcrNutritionDraft(
    val basis: OcrBasis,
    val servingLabel: String,
    val values: Map<OcrField, Double>,
    val conversions: Set<OcrField> = emptySet()
)

/** A recognizer line and its position in the source image. */
data class OcrTextBox(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val confidence: Double = 1.0
) {
    val centerY: Double get() = (top + bottom) / 2.0
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

data class OcrTextDocument(
    val language: OcrLanguage,
    val text: String,
    val boxes: List<OcrTextBox> = emptyList(),
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    val sourceImage: Int = 0,
    val preprocessingVariant: OcrImageVariant = OcrImageVariant.ORIGINAL,
    val coverageScore: Int = 0,
    val engine: OcrEngine = OcrEngine.ML_KIT
)

sealed interface OcrResult {
    data class Success(val review: OcrReview) : OcrResult
    data class Failed(val message: String) : OcrResult
    data object Cancelled : OcrResult
}

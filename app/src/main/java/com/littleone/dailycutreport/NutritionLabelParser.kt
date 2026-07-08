package com.littleone.dailycutreport

import java.text.Normalizer
import kotlin.math.abs

class NutritionLabelParser {
    fun parse(documents: List<OcrTextDocument>): OcrReview {
        val candidates = mutableListOf<ScoredCandidate>()
        var servingLabel: String? = null
        var servings: Double? = null
        val warnings = linkedSetOf<String>()

        // In Auto mode, all bundled recognizers run. Parse the most plausible output first so
        // weak Latin/Japanese guesses cannot replace a good Chinese table (or vice versa).
        documents.sortedByDescending(::documentScore).forEach { document ->
            val text = normalize(document.text)
            val documentScore = maxOf(documentScore(document), document.coverageScore)
            servingLabel = servingLabel ?: findServingLabel(text)
            servings = servings ?: findServings(text)
            val hasServing = SERVING_MARKERS.any(text::contains) || findServingLabel(text) != null
            val has100g = PER_100_G_MARKERS.any(text::contains)
            val has100ml = PER_100_ML_MARKERS.any(text::contains)

            visualRows(document).forEach { row ->
                val line = row.text
                val nutrient = aliases.entries.firstOrNull { (_, terms) -> terms.any(line::contains) }?.key
                    ?: return@forEach
                if (nutrient == OcrField.FAT && TRANS_FAT_MARKERS.any(line::contains)) return@forEach
                val allAmounts = amountsIn(line)
                if (allAmounts.isEmpty()) return@forEach
                val amounts = preferredAmounts(nutrient, allAmounts)
                val lineBasis = basisIn(line)
                val bases = when {
                    lineBasis != null -> listOf(lineBasis)
                    amounts.size >= 2 && hasServing && has100g -> listOf(OcrBasis.PER_SERVING, OcrBasis.PER_100_G)
                    amounts.size >= 2 && hasServing && has100ml -> listOf(OcrBasis.PER_SERVING, OcrBasis.PER_100_ML)
                    has100g && !hasServing -> listOf(OcrBasis.PER_100_G)
                    has100ml && !hasServing -> listOf(OcrBasis.PER_100_ML)
                    else -> listOf(OcrBasis.PER_SERVING)
                }
                amounts.take(bases.size).forEachIndexed { index, amount ->
                    convert(nutrient, amount.value, amount.unit, line)?.let { (value, converted) ->
                        candidates += ScoredCandidate(
                            candidate = OcrCandidate(
                                field = nutrient,
                                value = value,
                                unit = nutrient.storageUnit,
                                basis = bases[index],
                                sourceText = line,
                                converted = converted,
                                language = document.language,
                                engine = document.engine,
                                confidence = row.confidence,
                                sourceImage = document.sourceImage
                            ),
                            score = documentScore + (if (converted) 0.0 else 2.0) + row.confidence * 5.0,
                            sourceImage = document.sourceImage
                        )
                        if (converted && nutrient == OcrField.SODIUM) warnings += "Salt equivalent was converted to sodium."
                        if (converted && nutrient == OcrField.CALORIES) warnings += "Kilojoules were converted to kilocalories."
                    }
                }
            }
        }

        val proposals = OcrField.entries.mapNotNull { field ->
            val byBasis = candidates.filter { it.candidate.field == field }
                .groupBy { it.candidate.basis }
            val selected = mutableMapOf<OcrBasis, OcrCandidate>()
            val alternatives = mutableMapOf<OcrBasis, List<OcrCandidate>>()
            byBasis.forEach { (basis, values) ->
                val distinct = mutableListOf<ScoredCandidate>()
                values.sortedByDescending { it.score }.forEach { value ->
                    if (distinct.all { materiallyDifferent(it.candidate.value, value.candidate.value) }) {
                        distinct += value
                    }
                }
                alternatives[basis] = distinct.map { it.candidate }
                if (distinct.size == 1) {
                    selected[basis] = distinct.single().candidate
                } else if (distinct.size > 1) {
                    warnings += "Conflicting ${field.label.lowercase()} values for ${basis.label.lowercase()} require review."
                }
            }
            alternatives.takeIf { it.isNotEmpty() }?.let { OcrFieldProposal(field, selected, it) }
        }
        if (proposals.isEmpty()) warnings += "No supported nutrient rows were recognized."
        return OcrReview(proposals, servingLabel, servings, warnings.toList(), documents)
    }

    internal fun documentScore(document: OcrTextDocument): Int {
        val text = normalize(document.text)
        val nutrientCount = aliases.values.count { terms -> terms.any(text::contains) }
        val amountCount = AMOUNT.findAll(text).count().coerceAtMost(20)
        val basisCount = listOf(SERVING_MARKERS, PER_100_G_MARKERS, PER_100_ML_MARKERS)
            .count { markers -> markers.any(text::contains) }
        return nutrientCount * 8 + amountCount * 2 + basisCount * 5
    }

    /**
     * ML Kit can emit each table cell as an independent line. Recombine lines that occupy the
     * same visual row before interpreting left-to-right nutrient columns. Plain-text documents
     * remain supported for deterministic parser tests.
     */
    private fun visualRows(document: OcrTextDocument): List<RowEvidence> {
        if (document.boxes.isEmpty()) {
            return normalize(document.text).lineSequence().map(String::trim).filter(String::isNotBlank)
                .map { RowEvidence(it, 1.0) }.toList()
        }
        val rows = mutableListOf<MutableList<OcrTextBox>>()
        document.boxes.filter { it.text.isNotBlank() }.sortedBy { it.centerY }.forEach { box ->
            val row = rows.minByOrNull { existing -> abs(existing.map { it.centerY }.average() - box.centerY) }
            val tolerance = row?.let { existing ->
                maxOf(existing.maxOf { it.height }, box.height) * 0.45
            } ?: 0.0
            if (row != null && abs(row.map { it.centerY }.average() - box.centerY) <= tolerance) {
                row += box
            } else {
                rows += mutableListOf(box)
            }
        }
        return rows.sortedBy { row -> row.minOf { it.top } }
            .map { row ->
                RowEvidence(
                    normalize(row.sortedBy { it.left }.joinToString(" ") { it.text }).trim(),
                    row.map { it.confidence }.average().coerceIn(0.0, 1.0)
                )
            }
            .filter { it.text.isNotBlank() }
    }

    private fun amountsIn(line: String): List<Amount> = AMOUNT.findAll(line).mapNotNull { match ->
        val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
        Amount(value, match.groupValues[2].lowercase())
    }.toList()

    // Energy rows commonly print kcal and kJ for every column. Use one unit consistently before
    // assigning values to columns; otherwise the serving kJ value is mistaken for per-100 kcal.
    private fun preferredAmounts(field: OcrField, amounts: List<Amount>): List<Amount> {
        if (field != OcrField.CALORIES) return amounts
        val kcal = amounts.filter { it.unit in KCAL_UNITS }
        return if (kcal.isNotEmpty()) kcal else amounts.filter { it.unit in KJ_UNITS }
    }

    private fun convert(field: OcrField, value: Double, unit: String, line: String): Pair<Double, Boolean>? {
        if (!value.isFinite() || value < 0) return null
        return when (field) {
            OcrField.CALORIES -> when (unit) {
                in KCAL_UNITS -> value to false
                in KJ_UNITS -> value / 4.184 to true
                else -> null
            }
            OcrField.SODIUM -> if (SALT_EQUIVALENT.any(line::contains)) {
                when (unit) {
                    in GRAM_UNITS -> value * 393.4 to true
                    in MILLIGRAM_UNITS -> value * 0.3934 to true
                    else -> null
                }
            } else when (unit) {
                in MILLIGRAM_UNITS -> value to false
                in GRAM_UNITS -> value * 1000.0 to true
                else -> null
            }
            else -> when (unit) {
                in GRAM_UNITS -> value to false
                in MILLIGRAM_UNITS -> value / 1000.0 to true
                else -> null
            }
        }
    }

    private fun basisIn(line: String): OcrBasis? = when {
        PER_100_ML_MARKERS.any(line::contains) -> OcrBasis.PER_100_ML
        PER_100_G_MARKERS.any(line::contains) -> OcrBasis.PER_100_G
        SERVING_MARKERS.any(line::contains) -> OcrBasis.PER_SERVING
        else -> null
    }

    private fun findServingLabel(text: String): String? = SERVING_SIZE.find(text)?.let {
        val unit = when (it.groupValues[2]) {
            "毫升", "ミリリットル" -> "ml"
            "公克", "克", "グラム" -> "g"
            else -> it.groupValues[2].lowercase()
        }
        "1 serving (${it.groupValues[1].replace(',', '.')} $unit)"
    }

    private fun findServings(text: String): Double? = SERVINGS.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .replace('\r', '\n')
        .replace(Regex("k\\s*ca[1l]", RegexOption.IGNORE_CASE), "kcal")
        .replace(Regex("k\\s*cal", RegexOption.IGNORE_CASE), "kcal")
        .replace(Regex("m\\s*g", RegexOption.IGNORE_CASE), "mg")
        .replace(Regex("(?<=100)\\s*m[1l](?=\\b|$)", RegexOption.IGNORE_CASE), "ml")
        .replace(Regex("(\\d)[oO](?=\\s*(?:kcal|kj|mg|g)\\b)"), "${'$'}{1}0")
        .replace(Regex("[ \\t]+"), " ")

    private fun materiallyDifferent(first: Double, second: Double): Boolean {
        val scale = maxOf(abs(first), abs(second), 1.0)
        return abs(first - second) / scale > 0.05
    }

    private data class Amount(val value: Double, val unit: String)
    private data class RowEvidence(val text: String, val confidence: Double)
    private data class ScoredCandidate(val candidate: OcrCandidate, val score: Double, val sourceImage: Int)

    companion object {
        private val aliases = linkedMapOf(
            OcrField.SATURATED_FAT to listOf("saturated fat", "saturates", "飽和脂肪", "饱和脂肪", "鲍和脂肪", "飽和脂質"),
            OcrField.PROTEIN to listOf("protein", "蛋白質", "蛋白质", "たんぱく質", "たん白質", "タンパク質"),
            OcrField.SODIUM to listOf("sodium", "鈉", "钠", "纳", "ナトリウム", "食塩相当量"),
            OcrField.CARBS to listOf("total carbohydrate", "carbohydrate", "carbohydrates", "碳水化合物", "炭水化物"),
            OcrField.FAT to listOf("total fat", "fat", "總脂肪", "总脂肪", "脂肪", "脂質"),
            OcrField.SUGAR to listOf("sugars", "sugar", "糖質", "糖類", "糖"),
            OcrField.FIBER to listOf("dietary fiber", "fibre", "fiber", "膳食纖維", "膳食纤维", "食物繊維"),
            OcrField.CALORIES to listOf("calories", "calorie", "energy", "熱量", "热量", "能量", "エネルギー")
        )
        private val AMOUNT = Regex("(\\d+(?:[.,]\\d+)?)\\s*(kcal|kj|mg|g|大卡|千卡|千焦|千焦耳|毫克|公克|克|キロカロリー|キロジュール|ミリグラム|グラム)", RegexOption.IGNORE_CASE)
        private val SERVING_MARKERS = listOf("per serving", "每份", "每一份", "一份", "1食", "1食分", "一食")
        private val PER_100_G_MARKERS = listOf("per 100 g", "per100g", "每100g", "每 100g", "每100公克", "每100克", "100gあたり", "100 gあたり")
        private val PER_100_ML_MARKERS = listOf("per 100 ml", "per 100ml", "per100ml", "每100ml", "每 100ml", "每100毫升", "每 100 毫升", "100mlあたり", "100 mlあたり")
        private val SALT_EQUIVALENT = listOf("salt equivalent", "食塩相当量", "食鹽相當量", "食盐相当量")
        private val TRANS_FAT_MARKERS = listOf("trans fat", "反式脂肪", "トランス脂肪")
        private val SERVING_SIZE = Regex("(?:serving size|每(?:一)?份量|一份|1食分)[^\\d]{0,16}(\\d+(?:[.,]\\d+)?)\\s*(g|ml|公克|克|毫升|グラム|ミリリットル)", RegexOption.IGNORE_CASE)
        private val SERVINGS = Regex("(?:servings per container|本包裝含|本包装含|內容量|内容量)[^\\d]{0,16}(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE)
        private val KCAL_UNITS = setOf("kcal", "大卡", "千卡", "キロカロリー")
        private val KJ_UNITS = setOf("kj", "千焦", "千焦耳", "キロジュール")
        private val GRAM_UNITS = setOf("g", "克", "公克", "グラム")
        private val MILLIGRAM_UNITS = setOf("mg", "毫克", "ミリグラム")
    }
}

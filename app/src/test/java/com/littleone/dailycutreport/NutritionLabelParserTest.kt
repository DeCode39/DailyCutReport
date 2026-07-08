package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NutritionLabelParserTest {
    private val parser = NutritionLabelParser()

    @Test fun parsesEnglishServingAndPer100Columns() {
        val review = parser.parse(listOf(OcrTextDocument(OcrLanguage.ENGLISH, """
            Nutrition per serving per 100 g
            Energy 120 kcal 240 kcal
            Protein 10 g 20 g
            Sodium 250 mg 500 mg
        """.trimIndent())))
        val protein = review.proposals.single { it.field == OcrField.PROTEIN }
        assertEquals(10.0, protein.candidates.getValue(OcrBasis.PER_SERVING).value, 0.0)
        assertEquals(20.0, protein.candidates.getValue(OcrBasis.PER_100_G).value, 0.0)
    }

    @Test fun parsesChineseAndConvertsKilojoules() {
        val review = parser.parse(listOf(OcrTextDocument(OcrLanguage.CHINESE, "每100ml\n熱量 418.4 kJ\n蛋白質 5 g\n鈉 100 mg")))
        val energy = review.proposals.single { it.field == OcrField.CALORIES }.candidates.getValue(OcrBasis.PER_100_ML)
        assertEquals(100.0, energy.value, 0.001)
        assertTrue(energy.converted)
    }

    @Test fun convertsJapaneseSaltEquivalentToSodium() {
        val review = parser.parse(listOf(OcrTextDocument(OcrLanguage.JAPANESE, "1食分\n食塩相当量 1 g\nたんぱく質 8 g")))
        val sodium = review.proposals.single { it.field == OcrField.SODIUM }.candidates.getValue(OcrBasis.PER_SERVING)
        assertEquals(393.4, sodium.value, 0.001)
    }

    @Test fun reconstructsChineseBottleTableFromPositionedCells() {
        val boxes = listOf(
            box("每份", 300, 100), box("每100毫升", 520, 104),
            box("熱量", 40, 180), box("232.5 kcal", 300, 184), box("77.5 kcal", 520, 188),
            box("蛋白質", 40, 240), box("22.2 g", 300, 244), box("7.4 g", 520, 247),
            box("總脂肪", 40, 300), box("4.5 g", 300, 304), box("1.5 g", 520, 307),
            box("碳水化合物", 40, 360), box("25.8 g", 300, 364), box("8.6 g", 520, 367),
            box("糖", 40, 420), box("11.4 g", 300, 424), box("3.8 g", 520, 427),
            box("鈉", 40, 480), box("168 mg", 300, 484), box("56 mg", 520, 487)
        )
        val text = "每一份量300毫升 本包裝含1份 每份 每100毫升\n" + boxes.joinToString("\n") { it.text }
        val review = parser.parse(listOf(OcrTextDocument(OcrLanguage.CHINESE, text, boxes, 800, 1200)))

        assertEquals("1 serving (300 ml)", review.servingLabel)
        assertEquals(1.0, review.servingsPerContainer!!, 0.0)
        assertValue(review, OcrField.CALORIES, OcrBasis.PER_SERVING, 232.5)
        assertValue(review, OcrField.CALORIES, OcrBasis.PER_100_ML, 77.5)
        assertValue(review, OcrField.PROTEIN, OcrBasis.PER_SERVING, 22.2)
        assertValue(review, OcrField.SODIUM, OcrBasis.PER_SERVING, 168.0)
        assertValue(review, OcrField.SODIUM, OcrBasis.PER_100_ML, 56.0)
    }

    @Test fun energyWithKcalAndKilojoulesDoesNotShiftColumns() {
        val review = parser.parse(listOf(OcrTextDocument(OcrLanguage.ENGLISH, """
            per serving per 100 ml
            energy 232.5 kcal 973 kJ 77.5 kcal 324 kJ
        """.trimIndent())))

        assertValue(review, OcrField.CALORIES, OcrBasis.PER_SERVING, 232.5)
        assertValue(review, OcrField.CALORIES, OcrBasis.PER_100_ML, 77.5)
    }

    @Test fun toleratesConstrainedOcrSubstitutionsAndRetainsZero() {
        val review = parser.parse(listOf(OcrTextDocument(OcrLanguage.ENGLISH, """
            per serving per 100m1
            energy 0 kca1 2.4 kca1
            carbohydrate 2.1 g 0.6 g
            sodium 195 mg 56 mg
        """.trimIndent())))

        assertValue(review, OcrField.CALORIES, OcrBasis.PER_SERVING, 0.0)
        assertValue(review, OcrField.CALORIES, OcrBasis.PER_100_ML, 2.4)
        assertValue(review, OcrField.SODIUM, OcrBasis.PER_100_ML, 56.0)
    }

    @Test fun conflictingValuesFromSeparateCropsAreLeftBlank() {
        val first = OcrTextDocument(OcrLanguage.CHINESE, "每份\n蛋白質 22.2 g", sourceImage = 0)
        val second = OcrTextDocument(OcrLanguage.CHINESE, "每份\n蛋白質 7.4 g", sourceImage = 1)
        val review = parser.parse(listOf(first, second))

        val proposal = review.proposals.single { it.field == OcrField.PROTEIN }
        assertTrue(proposal.candidates[OcrBasis.PER_SERVING] == null)
        assertEquals(2, proposal.alternatives.getValue(OcrBasis.PER_SERVING).size)
        assertTrue(review.warnings.any { it.contains("Conflicting protein") })
    }

    @Test fun agreeingEnginesSelectTheHigherConfidenceEvidence() {
        val standard = OcrTextDocument(
            OcrLanguage.CHINESE,
            "每份\n蛋白質 22.2 g",
            engine = OcrEngine.ML_KIT
        )
        val enhanced = OcrTextDocument(
            OcrLanguage.CHINESE,
            "每份\n蛋白質 22.2 g",
            boxes = listOf(OcrTextBox("蛋白質 22.2 g", 0, 0, 200, 40, confidence = 0.99)),
            engine = OcrEngine.PP_OCR_V6_TINY
        )
        val review = parser.parse(listOf(standard, enhanced))

        val proposal = review.proposals.single { it.field == OcrField.PROTEIN }
        assertEquals(22.2, proposal.candidates.getValue(OcrBasis.PER_SERVING).value, 0.001)
        assertEquals(1, proposal.alternatives.getValue(OcrBasis.PER_SERVING).size)
    }

    @Test fun draftRequiresSelectionForConflictingEngineValues() {
        val standard = OcrTextDocument(OcrLanguage.ENGLISH, "per serving\nenergy 120 kcal")
        val enhanced = OcrTextDocument(
            OcrLanguage.ENGLISH,
            "per serving\nenergy 180 kcal",
            engine = OcrEngine.PP_OCR_V6_TINY
        )
        val review = parser.parse(listOf(standard, enhanced))
        val proposal = review.proposals.single { it.field == OcrField.CALORIES }

        assertTrue(review.draftFor(OcrBasis.PER_SERVING).values[OcrField.CALORIES] == null)
        val chosen = proposal.alternatives.getValue(OcrBasis.PER_SERVING).first { it.value == 180.0 }
        val draft = review.draftFor(
            OcrBasis.PER_SERVING,
            mapOf(OcrSelectionKey(OcrField.CALORIES, OcrBasis.PER_SERVING) to chosen.candidateId)
        )
        assertEquals(180.0, draft.values.getValue(OcrField.CALORIES), 0.001)
    }

    private fun box(text: String, left: Int, top: Int) =
        OcrTextBox(text, left, top, left + 150, top + 34)

    private fun assertValue(review: OcrReview, field: OcrField, basis: OcrBasis, expected: Double) {
        val actual = review.proposals.single { it.field == field }.candidates.getValue(basis).value
        assertEquals(expected, actual, 0.001)
    }
}

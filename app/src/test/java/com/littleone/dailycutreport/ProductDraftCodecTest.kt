package com.littleone.dailycutreport

import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class ProductDraftCodecTest {
    @Test fun roundTripKeepsEditableValuesAndDropsOcrReviewState() = runTest {
        val product = ProductEntity(productId = "existing", name = "Old")
        val existing = ProductWithExtras(product, emptyList())
        val original = PendingProductDraft(
            ProductEditorDraft.create("", existing, ProductSaveTarget.CATALOG_ONLY).copy(
                name = "Corrected name",
                brand = "Brand",
                quantityMode = QuantityMode.SERVING_AND_WEIGHT,
                measurePerServing = "42.5",
                calories = "98",
                purchasePrice = "12.50",
                favorite = true,
                extras = "Potassium=10 mg"
            ),
            LocalDate.of(2026, 8, 20)
        )

        val restored = ProductDraftCodec.decode(ProductDraftCodec.encode(original)) {
            existing.takeIf { it.product.productId == "existing" }
        }!!

        assertEquals("Corrected name", restored.draft.name)
        assertEquals("42.5", restored.draft.measurePerServing)
        assertEquals("12.50", restored.draft.purchasePrice)
        assertTrue(restored.draft.favorite)
        assertNull(restored.draft.ocrDraft)
        assertEquals(original.destinationDate, restored.destinationDate)
    }

    @Test fun interruptedMultiScanRecoversIntoSharedCart() = runTest {
        val pending = PendingProductDraft(
            ProductEditorDraft.create("123", null, ProductSaveTarget.MULTI_SCAN_QUEUE).copy(name = "New"),
            LocalDate.now()
        )
        val restored = ProductDraftCodec.decode(ProductDraftCodec.encode(pending)) { null }!!
        assertEquals(ProductSaveTarget.BULK_CART, restored.draft.saveTarget)
    }

    @Test fun blankCorruptAndMissingProductDraftsAreRejected() = runTest {
        assertFalse(ProductDraftCodec.isMeaningful(ProductEditorDraft()))
        assertNull(ProductDraftCodec.decode("not-json") { null })
        val missing = PendingProductDraft(
            ProductEditorDraft.create("", ProductWithExtras(ProductEntity(productId = "gone", name = "Gone"), emptyList()), ProductSaveTarget.CATALOG_ONLY),
            LocalDate.now()
        )
        assertNull(ProductDraftCodec.decode(ProductDraftCodec.encode(missing)) { null })
    }

    @Test fun focusOrderSkipsDisabledFieldsAndEndsWithDone() {
        val coordinator = FormFocusCoordinator(listOf(
            FormImeSpec("servings", false), FormImeSpec("grams"), FormImeSpec("paid")
        ))
        assertEquals(ImeAction.Next, coordinator.action("grams"))
        assertEquals(ImeAction.Done, coordinator.action("paid"))
    }
}

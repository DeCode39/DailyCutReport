package com.littleone.dailycutreport

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class CartDraftCodecTest {
    @Test fun roundTripKeepsTransientCartInputs() = runTest {
        val product = ProductEntity(
            productId = "p1",
            name = "Protein milk",
            quantityMode = QuantityMode.SERVING_AND_VOLUME.name,
            measurePerServing = 300.0,
            preferredLogUnit = QuantityUnit.MILLILITERS.name,
            purchaseUnitServings = 2.0
        )
        val original = BulkDraft(
            date = LocalDate.now(),
            items = listOf(BulkDraftItem(product, QuantityInputState.forProduct(product, 4.0))),
            label = "Convenience store",
            actualPaidText = "75",
            excludeCostFromBudget = true,
            copySummary = "Copied from a previous meal"
        )

        val restored = CartDraftCodec.decode(CartDraftCodec.encode(original)) {
            product.takeIf { it.productId == product.productId }
        }

        assertEquals(1, restored.items.size)
        assertEquals(4.0, restored.items.single().quantity!!, 0.0)
        assertEquals("Convenience store", restored.label)
        assertEquals("75", restored.actualPaidText)
        assertTrue(restored.excludeCostFromBudget)
        assertEquals(original.copySummary, restored.copySummary)
    }

    @Test fun legacyCartWithoutADateCanBeReviewedAndLoggedToday() = runTest {
        val product = ProductEntity("food", name = "Food")
        val restored = CartDraftCodec.decode(CartDraftCodec.encode(BulkDraft(items = listOf(BulkDraftItem(product))))) { product }
        assertEquals(LocalDate.now(), restored.date)
        assertTrue(restored.isValid)
    }

    @Test fun restartPreservesPhysicalAmountsButNeverConvertsGramsToMilliliters() = runTest {
        val product = ProductEntity("food", name = "Food", quantityMode = "SERVING_AND_WEIGHT", measurePerServing = 40.0)
        val input = QuantityInputState.forProduct(product).edit(QuantityUnit.GRAMS, "80")
        val encoded = CartDraftCodec.encode(BulkDraft(LocalDate.now(), listOf(BulkDraftItem(product, input))))
        val compatible = CartDraftCodec.decode(encoded) { product.copy(measurePerServing = 20.0) }
        assertEquals(4.0, compatible.items.single().quantity!!, 0.0)
        assertEquals(80.0, compatible.items.single().quantityInput.enteredAmount!!, 0.0)
        val incompatible = CartDraftCodec.decode(encoded) { product.copy(quantityMode = "VOLUME_ONLY") }
        assertTrue(!incompatible.isValid)
        assertTrue(incompatible.copySummary!!.contains("Previous entry 80 g"))
    }

    @Test fun corruptOrMissingProductsRestoreAsEmptyCart() = runTest {
        assertTrue(CartDraftCodec.decode("not-json") { null }.items.isEmpty())
        val product = ProductEntity(productId = "gone", name = "Gone")
        val encoded = CartDraftCodec.encode(BulkDraft(LocalDate.now(), listOf(BulkDraftItem(product))))
        assertTrue(CartDraftCodec.decode(encoded) { null }.items.isEmpty())
    }
}

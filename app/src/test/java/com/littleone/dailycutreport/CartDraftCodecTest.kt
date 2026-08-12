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
            excludeCostFromBudget = true
        )

        val restored = CartDraftCodec.decode(CartDraftCodec.encode(original)) {
            product.takeIf { it.productId == product.productId }
        }

        assertEquals(1, restored.items.size)
        assertEquals(4.0, restored.items.single().quantity!!, 0.0)
        assertEquals("Convenience store", restored.label)
        assertEquals("75", restored.actualPaidText)
        assertTrue(restored.excludeCostFromBudget)
    }

    @Test fun corruptOrMissingProductsRestoreAsEmptyCart() = runTest {
        assertTrue(CartDraftCodec.decode("not-json") { null }.items.isEmpty())
        val product = ProductEntity(productId = "gone", name = "Gone")
        val encoded = CartDraftCodec.encode(BulkDraft(LocalDate.now(), listOf(BulkDraftItem(product))))
        assertTrue(CartDraftCodec.decode(encoded) { null }.items.isEmpty())
    }
}

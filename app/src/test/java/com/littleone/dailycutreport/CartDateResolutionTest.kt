package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CartDateResolutionTest {
    private val oldDate = LocalDate.of(2026, 8, 13)
    private val newDate = LocalDate.of(2026, 8, 14)
    private val first = ProductEntity(productId = "first", name = "First", purchaseUnitServings = 2.0)
    private val second = ProductEntity(productId = "second", name = "Second", purchaseUnitServings = 1.0)

    @Test fun `keeping existing date preserves cart and merges pending quantities`() {
        val current = BulkDraft(oldDate, listOf(BulkDraftItem(first, QuantityInputState.forProduct(first, 2.0))))
        val pending = PendingCartAddition(
            newDate,
            listOf(
                BulkDraftItem(first, QuantityInputState.forProduct(first, 2.0)),
                BulkDraftItem(second, QuantityInputState.forProduct(second, 1.0))
            )
        )
        val result = resolveCartAddition(current, pending, CartDateResolution.KEEP_EXISTING)
        assertEquals(oldDate, result.date)
        assertEquals(2, result.items.size)
        assertEquals(4.0, result.items.first { it.product.productId == "first" }.quantity!!, 0.0)
    }

    @Test fun `invalid quantities cannot be silently replaced by another addition`() {
        val input = QuantityInputState.forProduct(first).edit(QuantityUnit.SERVINGS, "-")
        val current = BulkDraft(oldDate, listOf(BulkDraftItem(first, input)))
        assertTrue(runCatching { resolveCartAddition(current, PendingCartAddition(oldDate, listOf(BulkDraftItem(first))), CartDateResolution.KEEP_EXISTING) }.isFailure)
        assertEquals("-", current.items.single().quantityInput.servingsText)
    }

    @Test fun `merging corrected products preserves physical amounts and updates conversion`() {
        val measured = first.copy(quantityMode = "SERVING_AND_WEIGHT", measurePerServing = 40.0)
        val input = QuantityInputState.forProduct(measured).edit(QuantityUnit.GRAMS, "80")
        val corrected = measured.copy(measurePerServing = 20.0)
        val result = resolveCartAddition(BulkDraft(oldDate, listOf(BulkDraftItem(measured, input))),
            PendingCartAddition(oldDate, listOf(BulkDraftItem(corrected))), CartDateResolution.KEEP_EXISTING)
        assertEquals(6.0, result.items.single().quantity!!, 0.0)
        assertEquals(120.0, result.items.single().quantityInput.enteredAmount!!, 0.0)
        assertEquals(corrected.quantitySpec(), result.items.single().quantityInput.spec)
    }

    @Test fun `starting requested date discards prior cart only after confirmation`() {
        val current = BulkDraft(oldDate, listOf(BulkDraftItem(first)))
        val pending = PendingCartAddition(newDate, listOf(BulkDraftItem(second)))
        val result = resolveCartAddition(current, pending, CartDateResolution.START_REQUESTED)
        assertEquals(newDate, result.date)
        assertEquals(listOf("second"), result.items.map { it.product.productId })
        assertEquals(listOf("first"), current.items.map { it.product.productId })
    }
}

package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductDraftJsonTest {
    @Test fun importsCompleteSchemaAndPreservesWorkflowIdentity() {
        val initial = ProductEditorDraft(
            barcode = "draft-code",
            saveTarget = ProductSaveTarget.BULK_CART,
            name = "Draft"
        )
        val imported = initial.withProductJson(
            PRODUCT_JSON_TEMPLATE
                .replace("Required product name", "Photo estimate")
                .replace("\"barcode\": null", "\"barcode\": \"123456\"")
                .replace("\"proteinG\": 0", "\"proteinG\": 18.25")
                .replace("\"itemType\": \"FOOD\"", "\"itemType\": \"DRINK\"")
                .replace("\"fixedPurchaseUnits\": 1", "\"fixedPurchaseUnits\": 4")
        )

        assertEquals(ProductSaveTarget.BULK_CART, imported.saveTarget)
        assertEquals("123456", imported.barcode)
        assertEquals("Photo estimate", imported.name)
        assertEquals("18.25", imported.protein)
        assertEquals(PlannerItemType.DRINK, imported.plannerItemType)
        assertEquals("4", imported.fixedPurchaseUnits)
        assertTrue(imported.extras.contains("Potassium=0 mg"))
    }

    @Test fun missingOptionalFieldsPreserveDraftAndExplicitNullClearsPrice() {
        val imported = ProductEditorDraft(
            barcode = "ABC",
            name = "Old",
            brand = "Existing brand",
            purchasePrice = "12.5",
            includeInPlanner = true,
            alwaysIncludeInPlanner = true
        ).withProductJson(
            """{"schemaVersion":1,"product":{"name":"New","purchasePrice":null,"includeInPlanner":false}}"""
        )

        assertEquals("ABC", imported.barcode)
        assertEquals("Existing brand", imported.brand)
        assertEquals("", imported.purchasePrice)
        assertFalse(imported.includeInPlanner)
        assertFalse(imported.alwaysIncludeInPlanner)
        assertEquals("1", imported.fixedPurchaseUnits)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFixedPurchaseUnitsOutsideSupportedRange() {
        ProductEditorDraft().withProductJson(
            """{"schemaVersion":1,"product":{"name":"Invalid","fixedPurchaseUnits":7}}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNegativeNutrition() {
        ProductEditorDraft().withProductJson(
            """{"schemaVersion":1,"product":{"name":"Invalid","proteinG":-1}}"""
        )
    }
}

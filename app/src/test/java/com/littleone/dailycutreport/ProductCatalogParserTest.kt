package com.littleone.dailycutreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProductCatalogParserTest {
    @Test fun schemaOneSeparatesCustomIdFromRetailBarcode() {
        val parsed = ProductCatalogParser.parse("""{
            "schemaVersion":1,"products":[
              {"barcode":"CUSTOM-FOOD","name":"Custom"},
              {"barcode":"4711089912108","name":"Retail"}
            ]
        }""")
        assertEquals("CUSTOM-FOOD", parsed[0].product.productId)
        assertNull(parsed[0].product.barcode)
        assertEquals("4711089912108", parsed[1].product.barcode)
    }

    @Test fun schemaTwoAcceptsAnOptionalBarcode() {
        val parsed = ProductCatalogParser.parse("""{
            "schemaVersion":2,"products":[
              {"id":"stable-id","barcode":null,"name":"No barcode","extras":[{"name":"BCAA","value":10,"unit":"mg"}]}
            ]
        }""")
        assertEquals("stable-id", parsed.single().product.productId)
        assertNull(parsed.single().product.barcode)
        assertEquals(1, parsed.single().extras.size)
    }
}

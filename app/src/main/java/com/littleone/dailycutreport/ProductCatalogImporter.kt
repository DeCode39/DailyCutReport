package com.littleone.dailycutreport

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class ProductCatalogImporter(
    private val context: Context,
    private val dao: NutritionDao
) {
    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        if (dao.metadata(IMPORT_KEY) == "complete") return@withContext
        val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        dao.importSeedProducts(ProductCatalogParser.parse(json), IMPORT_KEY)
    }

    companion object {
        const val ASSET_NAME = "preloaded_products.json"
        const val IMPORT_KEY = "preloaded_products_v1"
    }
}

object ProductCatalogParser {
    fun parse(json: String): List<ProductWithExtras> {
        val root = JSONObject(json)
        val schema = root.getInt("schemaVersion")
        require(schema == 1 || schema == 2) { "Unsupported catalog schema $schema" }
        val products = root.getJSONArray("products")
        val seenIds = mutableSetOf<String>()
        val seenBarcodes = mutableSetOf<String>()
        return (0 until products.length()).map { index ->
            val item = products.getJSONObject(index)
            val legacyCode = item.optString("barcode").trim()
            val productId = when (schema) {
                1 -> legacyCode
                else -> item.optString("id").trim().ifBlank { legacyCode }
            }
            require(productId.isNotBlank()) { "Product $index has no stable id" }
            require(seenIds.add(productId)) { "Duplicate product id $productId" }
            val barcode = when (schema) {
                1 -> legacyCode.takeIf { it.isRetailBarcode() }
                else -> item.optNullableString("barcode")
            }
            if (barcode != null) require(seenBarcodes.add(barcode)) { "Duplicate barcode $barcode" }
            val name = item.getString("name").trim()
            require(name.isNotBlank()) { "Product $productId has no name" }
            val product = ProductEntity(
                productId = productId,
                barcode = barcode,
                name = name,
                brand = item.optString("brand"),
                servingLabel = item.optString("servingLabel", "1 serving").ifBlank { "1 serving" },
                calories = item.nonNegative("calories"),
                proteinG = item.nonNegative("proteinG"),
                sodiumMg = item.nonNegative("sodiumMg"),
                carbsG = item.nonNegative("carbsG"),
                fatG = item.nonNegative("fatG"),
                sugarG = item.nonNegative("sugarG"),
                fiberG = item.nonNegative("fiberG"),
                saturatedFatG = item.nonNegative("saturatedFatG"),
                notes = item.optString("notes")
            )
            val extrasJson = item.optJSONArray("extras") ?: JSONArray()
            val extras = (0 until extrasJson.length()).map { extraIndex ->
                val extra = extrasJson.getJSONObject(extraIndex)
                ProductExtraNutrientEntity(
                    productId = productId,
                    name = extra.getString("name").trim().also { require(it.isNotBlank()) },
                    value = extra.nonNegative("value"),
                    unit = extra.optString("unit").trim()
                )
            }
            ProductWithExtras(product, extras)
        }
    }

    private fun JSONObject.nonNegative(key: String): Double = optDouble(key, 0.0).also {
        require(it.isFinite() && it >= 0.0) { "$key must be a non-negative number" }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).trim().ifBlank { null }

    private fun String.isRetailBarcode(): Boolean = length in 8..14 && all(Char::isDigit)
}

package com.littleone.dailycutreport

import org.json.JSONArray
import org.json.JSONObject

internal const val PRODUCT_JSON_TEMPLATE = """
{
  "schemaVersion": 1,
  "product": {
    "barcode": null,
    "name": "Required product name",
    "brand": "",
    "servingLabel": "1 serving",
    "calories": 0,
    "proteinG": 0,
    "sodiumMg": 0,
    "carbsG": 0,
    "fatG": 0,
    "sugarG": 0,
    "fiberG": 0,
    "saturatedFatG": 0,
    "purchasePrice": null,
    "purchaseUnitServings": 1,
    "includeInPlanner": true,
    "itemType": "FOOD",
    "fixedInPlanner": false,
    "fixedPurchaseUnits": 1,
    "favorite": false,
    "extras": [
      { "name": "Potassium", "value": 0, "unit": "mg" }
    ]
  }
}
"""

internal fun ProductEditorDraft.withProductJson(input: String): ProductEditorDraft {
    val normalized = input.trim()
        .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        .removeSuffix("```").trim()
    require(normalized.isNotBlank()) { "Paste a product JSON document." }
    val root = JSONObject(normalized)
    require(root.optInt("schemaVersion", -1) == 1) { "Unsupported or missing schemaVersion." }
    val product = root.optJSONObject("product") ?: error("Missing product object.")
    val importedName = product.optString("name").trim()
    require(importedName.isNotBlank()) { "Product name is required." }

    fun numberText(key: String, current: String): String {
        if (!product.has(key)) return current
        require(!product.isNull(key)) { "$key cannot be null." }
        val value = product.getDouble(key)
        require(value.isFinite() && value >= 0.0) { "$key must be a non-negative number." }
        return formatDecimal(value)
    }

    val importedPurchaseServings = if (product.has("purchaseUnitServings")) {
        val value = product.getDouble("purchaseUnitServings")
        require(value.isFinite() && value > 0.0) { "purchaseUnitServings must be greater than zero." }
        formatDecimal(value)
    } else this.purchaseServings
    val price = when {
        !product.has("purchasePrice") -> purchasePrice
        product.isNull("purchasePrice") -> ""
        else -> product.getDouble("purchasePrice").also {
            require(it.isFinite() && it >= 0.0) { "purchasePrice must be a non-negative number or null." }
        }.let(::formatDecimal)
    }
    val itemType = if (product.has("itemType")) {
        runCatching { PlannerItemType.valueOf(product.getString("itemType").trim().uppercase()) }
            .getOrElse { error("itemType must be FOOD or DRINK.") }
    } else plannerItemType
    val include = if (product.has("includeInPlanner")) product.getBoolean("includeInPlanner") else includeInPlanner
    val fixed = if (product.has("fixedInPlanner")) product.getBoolean("fixedInPlanner") else alwaysIncludeInPlanner
    val fixedUnits = if (product.has("fixedPurchaseUnits")) {
        product.getInt("fixedPurchaseUnits").also {
            require(it in 1..6) { "fixedPurchaseUnits must be an integer from 1 to 6." }
        }.toString()
    } else fixedPurchaseUnits

    return copy(
        barcode = if (product.has("barcode")) product.optionalString("barcode") ?: "" else barcode,
        name = importedName,
        brand = product.optionalString("brand") ?: brand,
        servingLabel = product.optionalString("servingLabel")?.ifBlank { "1 serving" } ?: servingLabel,
        calories = numberText("calories", calories),
        protein = numberText("proteinG", protein),
        sodium = numberText("sodiumMg", sodium),
        carbs = numberText("carbsG", carbs),
        fat = numberText("fatG", fat),
        sugar = numberText("sugarG", sugar),
        fiber = numberText("fiberG", fiber),
        saturatedFat = numberText("saturatedFatG", saturatedFat),
        purchasePrice = price,
        purchaseServings = importedPurchaseServings,
        includeInPlanner = include,
        plannerItemType = itemType,
        alwaysIncludeInPlanner = include && fixed,
        fixedPurchaseUnits = fixedUnits,
        favorite = if (product.has("favorite")) product.getBoolean("favorite") else favorite,
        extras = if (product.has("extras")) product.getJSONArray("extras").toEditorText() else extras,
        ocrDraft = null
    )
}

private fun JSONObject.optionalString(key: String): String? = when {
    !has(key) || isNull(key) -> null
    else -> getString(key).trim()
}

private fun JSONArray.toEditorText(): String = buildList {
    for (index in 0 until length()) {
        val extra = getJSONObject(index)
        val name = extra.getString("name").trim()
        val value = extra.getDouble("value")
        val unit = extra.optString("unit").trim()
        require(name.isNotBlank()) { "Extra nutrient names cannot be blank." }
        require(value.isFinite() && value >= 0.0) { "Extra nutrient values must be non-negative numbers." }
        add("$name=${formatDecimal(value)}${unit.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}")
    }
}.joinToString("\n")

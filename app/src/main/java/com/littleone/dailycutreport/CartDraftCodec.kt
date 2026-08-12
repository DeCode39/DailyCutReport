package com.littleone.dailycutreport

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Transient cart persistence. This document is stored in app_metadata and is deliberately not backed up. */
internal object CartDraftCodec {
    private const val SCHEMA_VERSION = 1

    fun encode(draft: BulkDraft): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("date", draft.date?.toString())
        put("label", draft.label)
        put("actualPaidText", draft.actualPaidText)
        put("excludeCostFromBudget", draft.excludeCostFromBudget)
        put("items", JSONArray().apply {
            draft.items.forEach { item ->
                put(JSONObject().apply {
                    put("productId", item.product.productId)
                    put("servingsText", item.quantityInput.servingsText)
                    put("measureText", item.quantityInput.measureText)
                    put("activeUnit", item.quantityInput.activeUnit.name)
                })
            }
        })
    }.toString()

    suspend fun decode(value: String, productLookup: suspend (String) -> ProductEntity?): BulkDraft = try {
        val root = JSONObject(value)
        require(root.optInt("schemaVersion") == SCHEMA_VERSION)
        val itemsJson = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.getJSONObject(index)
                val product = productLookup(item.getString("productId")) ?: continue
                val spec = product.quantitySpec()
                val requestedUnit = QuantityUnit.entries.firstOrNull {
                    it.name == item.optString("activeUnit")
                } ?: product.preferredQuantityUnit()
                add(BulkDraftItem(
                    product = product,
                    quantityInput = QuantityInputState(
                        servingsText = item.optString("servingsText", product.purchaseUnitServings.toDisplay()),
                        measureText = item.optString("measureText", ""),
                        activeUnit = spec.preferredOrFallback(requestedUnit),
                        spec = spec
                    )
                ))
            }
        }
        val restoredDate = root.optString("date").takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.coerceAtMost(LocalDate.now())
        BulkDraft(
            date = restoredDate.takeIf { items.isNotEmpty() },
            items = items,
            label = root.optString("label"),
            actualPaidText = root.optString("actualPaidText"),
            excludeCostFromBudget = root.optBoolean("excludeCostFromBudget")
        )
    } catch (_: Exception) {
        BulkDraft()
    }
}

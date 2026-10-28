package com.littleone.dailycutreport

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Transient cart persistence. This document is stored in app_metadata and is deliberately not backed up. */
internal object CartDraftCodec {
    const val METADATA_KEY = "pending_cart_v1"
    private const val SCHEMA_VERSION = 1

    fun encode(draft: BulkDraft): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("date", draft.date?.toString())
        put("label", draft.label)
        put("actualPaidText", draft.actualPaidText)
        put("excludeCostFromBudget", draft.excludeCostFromBudget)
        put("copySummary", draft.copySummary)
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
        val reviewNotes = mutableListOf<String>()
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.getJSONObject(index)
                val product = productLookup(item.getString("productId")) ?: continue
                val spec = product.quantitySpec()
                val requestedUnit = QuantityUnit.entries.firstOrNull {
                    it.name == item.optString("activeUnit")
                } ?: product.preferredQuantityUnit()
                var input = QuantityInputState(
                        servingsText = item.optString("servingsText", product.purchaseUnitServings.toEntryText()),
                        measureText = item.optString("measureText", ""),
                        activeUnit = requestedUnit,
                        spec = spec
                    )
                if (!spec.supports(requestedUnit)) {
                    val previous = if (requestedUnit == QuantityUnit.SERVINGS) input.servingsText else input.measureText
                    reviewNotes += "${product.name}: quantity settings changed. Previous entry $previous ${requestedUnit.shortLabel}; re-enter the amount."
                    input = input.copy(servingsText = "", measureText = "", activeUnit = spec.preferredOrFallback(requestedUnit))
                } else {
                    val raw = if (requestedUnit == QuantityUnit.SERVINGS) input.servingsText else input.measureText
                    parseEntryNumber(raw)?.let { amount ->
                        spec.servingsFor(amount, requestedUnit)?.let { input = input.withServings(it, requestedUnit) }
                    }
                }
                add(BulkDraftItem(product, input))
            }
        }
        val restoredDate = root.optString("date").takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.coerceAtMost(LocalDate.now()) ?: LocalDate.now()
        BulkDraft(
            date = restoredDate.takeIf { items.isNotEmpty() },
            items = items,
            label = root.optString("label"),
            actualPaidText = root.optString("actualPaidText"),
            excludeCostFromBudget = root.optBoolean("excludeCostFromBudget"),
            copySummary = (listOfNotNull(root.optString("copySummary").takeUnless { it.isBlank() || it == "null" }) + reviewNotes)
                .joinToString("\n").ifBlank { null }
        )
    } catch (_: Exception) {
        BulkDraft()
    }
}

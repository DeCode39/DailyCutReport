package com.littleone.dailycutreport

import org.json.JSONObject
import java.time.LocalDate

/** Transient editor recovery stored in app_metadata and deliberately excluded from backups. */
data class PendingProductDraft(
    val draft: ProductEditorDraft,
    val destinationDate: LocalDate
)

internal object ProductDraftCodec {
    private const val SCHEMA_VERSION = 1

    fun isMeaningful(draft: ProductEditorDraft): Boolean = draft.existing != null || listOf(
        draft.barcode, draft.name, draft.brand, draft.measurePerServing, draft.calories,
        draft.protein, draft.sodium, draft.carbs, draft.fat, draft.sugar, draft.fiber,
        draft.saturatedFat, draft.purchasePrice, draft.extras
    ).any(String::isNotBlank)

    fun encode(value: PendingProductDraft): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("destinationDate", value.destinationDate.toString())
        put("existingProductId", value.draft.existing?.product?.productId)
        put("saveTarget", value.draft.saveTarget.name)
        put("barcode", value.draft.barcode)
        put("name", value.draft.name)
        put("brand", value.draft.brand)
        put("servingLabel", value.draft.servingLabel)
        put("quantityMode", value.draft.quantityMode.name)
        put("measurePerServing", value.draft.measurePerServing)
        put("preferredLogUnit", value.draft.preferredLogUnit.name)
        put("calories", value.draft.calories)
        put("protein", value.draft.protein)
        put("sodium", value.draft.sodium)
        put("carbs", value.draft.carbs)
        put("fat", value.draft.fat)
        put("sugar", value.draft.sugar)
        put("fiber", value.draft.fiber)
        put("saturatedFat", value.draft.saturatedFat)
        put("purchasePrice", value.draft.purchasePrice)
        put("purchaseServings", value.draft.purchaseServings)
        put("purchaseMeasure", value.draft.purchaseMeasure)
        put("includeInPlanner", value.draft.includeInPlanner)
        put("plannerItemType", value.draft.plannerItemType.name)
        put("alwaysIncludeInPlanner", value.draft.alwaysIncludeInPlanner)
        put("fixedPurchaseUnits", value.draft.fixedPurchaseUnits)
        put("favorite", value.draft.favorite)
        put("extras", value.draft.extras)
    }.toString()

    suspend fun decode(
        encoded: String,
        productLookup: suspend (String) -> ProductWithExtras?
    ): PendingProductDraft? = runCatching {
        val root = JSONObject(encoded)
        require(root.optInt("schemaVersion") == SCHEMA_VERSION)
        val date = LocalDate.parse(root.getString("destinationDate")).coerceAtMost(LocalDate.now())
        val existingId = root.optString("existingProductId").takeIf(String::isNotBlank)
        val existing = existingId?.let { requireNotNull(productLookup(it)) }
        val storedTarget = ProductSaveTarget.valueOf(root.getString("saveTarget"))
        val target = if (storedTarget == ProductSaveTarget.MULTI_SCAN_QUEUE) ProductSaveTarget.BULK_CART else storedTarget
        val draft = ProductEditorDraft(
            existing = existing,
            saveTarget = target,
            barcode = root.optString("barcode"),
            name = root.optString("name"),
            brand = root.optString("brand"),
            servingLabel = root.optString("servingLabel", "1 serving"),
            quantityMode = enumValueOrDefault(root.optString("quantityMode"), QuantityMode.SERVING_ONLY),
            measurePerServing = root.optString("measurePerServing"),
            preferredLogUnit = enumValueOrDefault(root.optString("preferredLogUnit"), QuantityUnit.SERVINGS),
            calories = root.optString("calories"),
            protein = root.optString("protein"),
            sodium = root.optString("sodium"),
            carbs = root.optString("carbs"),
            fat = root.optString("fat"),
            sugar = root.optString("sugar"),
            fiber = root.optString("fiber"),
            saturatedFat = root.optString("saturatedFat"),
            purchasePrice = root.optString("purchasePrice"),
            purchaseServings = root.optString("purchaseServings", "1"),
            purchaseMeasure = root.optString("purchaseMeasure"),
            includeInPlanner = root.optBoolean("includeInPlanner", true),
            plannerItemType = enumValueOrDefault(root.optString("plannerItemType"), PlannerItemType.FOOD),
            alwaysIncludeInPlanner = root.optBoolean("alwaysIncludeInPlanner"),
            fixedPurchaseUnits = root.optString("fixedPurchaseUnits", "1"),
            favorite = root.optBoolean("favorite"),
            extras = root.optString("extras"),
            ocrDraft = null
        )
        require(isMeaningful(draft))
        PendingProductDraft(draft, date)
    }.getOrNull()

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, fallback: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: fallback
}

package com.littleone.dailycutreport

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
internal fun BulkDraftCard(
    draft: BulkDraft,
    currencyCode: String,
    viewModel: FoodsViewModel,
    onDiscard: () -> Unit
) {
    val estimate = bulkEstimateMicros(draft)
    val formSpecs = buildList {
        draft.items.forEach { item ->
            add(FormImeSpec("cart-${item.product.productId}-servings", item.quantityInput.spec.servingAvailable))
            add(FormImeSpec("cart-${item.product.productId}-measure", item.quantityInput.spec.measureAvailable))
        }
        add(FormImeSpec("cart-label"))
        add(FormImeSpec("cart-paid"))
    }
    val formFocus = rememberFormFocusCoordinator(*formSpecs.toTypedArray())
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            draft.items.forEach { item ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { viewModel.decrementCartProduct(item.product.productId) },
                                enabled = (item.quantity ?: 0.0) > 1.0
                            ) {
                                Icon(painterResource(R.drawable.ic_remove_circle), contentDescription = "Remove one serving of ${item.product.name}")
                            }
                            Column(Modifier.weight(1f)) {
                                Text(item.product.name, fontWeight = FontWeight.Bold)
                                Text(item.product.servingLabel, style = MaterialTheme.typography.bodySmall)
                            }
                            AcceleratingAddButton(item.product.name) { units ->
                                viewModel.incrementCartProduct(item.product.productId, units)
                            }
                            TextButton(onClick = { viewModel.removeBulkProduct(item.product.productId) }) { Text("Remove") }
                        }
                        QuantityInputFields(
                            state = item.quantityInput,
                            onChange = { unit, value -> viewModel.updateBulkQuantity(item.product.productId, unit, value) },
                            onPurchaseUnit = { viewModel.resetBulkQuantity(item.product.productId) },
                            coordinator = formFocus,
                            fieldKeyPrefix = "cart-${item.product.productId}"
                        )
                        item.quantity?.let { servings ->
                            Text(
                                "${formatCalories(item.product.calories * servings)} kcal · ${formatDecimal(item.product.proteinG * servings)} g protein",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
            if (draft.items.isEmpty()) {
                Text("No products selected. Tap a catalog item to add it.", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                draft.label,
                viewModel::updateBulkLabel,
                label = { Text("Store or group label (optional)") },
                placeholder = { Text("e.g. 7-Eleven") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = formFocus.action("cart-label")),
                keyboardActions = formFocus.actions("cart-label"),
                modifier = Modifier.fillMaxWidth().formImeField("cart-label", formFocus)
            )
            DecimalField(
                "Final checkout total ($currencyCode, optional)", draft.actualPaidText,
                formFocus, "cart-paid", viewModel::updateBulkPaid
            )
            Text(
                estimate?.let { "Catalog estimate: ${formatMoney(it, currencyCode)}" }
                    ?: "Catalog estimate unavailable until every selected item has a price and valid quantity.",
                style = MaterialTheme.typography.bodySmall
            )
            ToggleRow(
                "Log the checkout price but ignore it in budget calculations",
                draft.excludeCostFromBudget,
                onCheckedChange = viewModel::updateBulkBudgetExclusion
            )
            if (draft.items.isNotEmpty()) {
                TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) { Text("Discard cart") }
            }
        }
    }
}

@Composable
private fun AcceleratingAddButton(productName: String, onAdd: (Int) -> Unit) {
    val currentOnAdd by rememberUpdatedState(onAdd)
    Box(
        Modifier
            .size(48.dp)
            .semantics {
                contentDescription = "Add one serving of $productName"
                onClick { currentOnAdd(1); true }
            }
            .pointerInput(productName) {
                detectTapGestures(onPress = {
                    currentOnAdd(1)
                    coroutineScope {
                        val repeater = launch {
                            delay(500)
                            var repeats = 0
                            while (true) {
                                val units = when {
                                    repeats < 3 -> 1
                                    repeats < 6 -> 2
                                    repeats < 9 -> 5
                                    else -> 10
                                }
                                currentOnAdd(units)
                                repeats++
                                delay(250)
                            }
                        }
                        tryAwaitRelease()
                        repeater.cancel()
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(R.drawable.ic_add_circle), contentDescription = null)
    }
}

internal fun bulkEstimateMicros(draft: BulkDraft): Long? {
    if (draft.items.isEmpty()) return null
    var total = 0L
    draft.items.forEach { item ->
        val price = item.product.purchasePriceMicros ?: return null
        val quantity = item.quantity ?: return null
        val estimate = (price.toDouble() * quantity / item.product.purchaseUnitServings).roundToLong()
        total = runCatching { Math.addExact(total, estimate) }.getOrElse { Long.MAX_VALUE }
    }
    return total
}

@Composable
internal fun RecommendationDialog(result: RecommendationResult, currencyCode: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remaining-day suggestions") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                result.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (result.existingViolations.isNotEmpty()) {
                    Text("Already outside target", fontWeight = FontWeight.Bold)
                    result.existingViolations.forEach { violation ->
                        Text(
                            "${violation.label}: ${constraintValue(violation.label, violation.baseline, currencyCode)} " +
                                "(target ${constraintTarget(violation, currencyCode)}, ${"%+.1f".format(violation.percentDifference)}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (result.unpricedProducts > 0) Text(
                    if (result.plans.any { it.balancedFallback }) {
                        "${result.unpricedProducts} unpriced product(s) were eligible for balanced recovery; unknown costs are marked."
                    } else {
                        "${result.unpricedProducts} unpriced product(s) were excluded from strict planning."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                if (result.excludedFromPlanningProducts > 0) Text(
                    "${result.excludedFromPlanningProducts} product(s) are disabled for planning.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (result.spendingIncomplete) Text(
                    "Existing or suggested unknown costs mean budget totals are estimates.",
                    style = MaterialTheme.typography.bodySmall
                )
                result.plans.forEachIndexed { index, plan ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (plan.balancedFallback) "Best balanced option" else "Option ${index + 1}",
                                fontWeight = FontWeight.Bold
                            )
                            plan.items.forEach { item ->
                                Text("${item.purchaseUnits} × ${item.name} · ${item.quantityLabel}" +
                                    when {
                                        item.fixed -> " · fixed"
                                        item.costMicros == null -> " · cost unknown"
                                        else -> ""
                                    })
                            }
                            MetricRow(
                                if (plan.spendingComplete) "Additional cost" else "Known additional cost",
                                formatMoney(plan.totalCostMicros, currencyCode)
                            )
                            MetricRow(
                                if (plan.spendingComplete && !result.spendingIncomplete) "Projected spending" else "Known projected spending",
                                formatMoney(plan.projectedSpendingMicros, currencyCode)
                            )
                            if (plan.unknownCostItems > 0) Text(
                                "${plan.unknownCostItems} suggested item(s) have unknown cost; budget compliance is not evaluated.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            MetricRow("Projected calories", "${formatCalories(plan.nutrition.calories)} kcal")
                            MetricRow("Projected protein", "${formatDecimal(plan.nutrition.proteinG)} g")
                            Text(plan.explanation, style = MaterialTheme.typography.bodySmall)
                            (plan.unmetMinimums + plan.impacts.filterNot { it.withinTolerance })
                                .distinctBy { it.label }
                                .forEach { impact ->
                                    Text(
                                        "${impact.label}: " +
                                            "${constraintValue(impact.label, impact.baseline, currencyCode)} → " +
                                            "${constraintValue(impact.label, impact.projected, currencyCode)} " +
                                            "(target ${constraintTarget(impact, currencyCode)}, " +
                                            "${"%+.1f".format(impact.percentDifference)}%)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                        }
                    }
                }
                if (result.plans.isEmpty()) Text("No suggestions available for the current catalog and goals.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun constraintValue(label: String, value: Double, currencyCode: String): String = when (label) {
    "Budget" -> formatMoney((value * MONEY_MICROS_PER_UNIT).toLong(), currencyCode)
    "Calories" -> "${formatCalories(value)} kcal"
    "Sodium" -> "${formatDecimal(value)} mg"
    else -> "${value.toDisplay()} g"
}

private fun constraintTarget(impact: ConstraintImpact, currencyCode: String): String = when (impact.label) {
    "Budget" -> formatMoney((impact.target * MONEY_MICROS_PER_UNIT).toLong(), currencyCode)
    "Calories" -> "${formatCalories(impact.target)} kcal"
    "Sodium" -> "${formatDecimal(impact.target)} mg"
    else -> "${impact.target.toDisplay()} g"
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProductCatalogRow(product: ProductEntity, currencyCode: String, viewModel: FoodsViewModel) {
    val haptics = LocalHapticFeedback.current
    Card(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { viewModel.addProductToCart(product) },
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.editProduct(product)
                }
            )
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                IconButton(onClick = { viewModel.addProductToCart(product) }) {
                    Icon(painterResource(R.drawable.ic_add_circle), contentDescription = "Add ${product.name} to cart")
                }
                IconButton(onClick = { viewModel.editProduct(product) }) {
                    Icon(painterResource(R.drawable.ic_edit), contentDescription = "Edit ${product.name}")
                }
            }
            Column(Modifier.weight(1f).padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                Text(product.name, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(product.brand.takeIf { it.isNotBlank() }, product.barcode).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { viewModel.toggleFavorite(product) }) {
                        Text(if (product.favorite) "★" else "☆", color = MaterialTheme.colorScheme.primary)
                    }
                }
                product.purchasePriceMicros?.let {
                    Text("${formatMoney(it, currencyCode)} / ${product.purchaseUnitServings.toDisplay()} serving(s)", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    when {
                        !product.includeInPlanner -> "Not used in planning"
                        product.alwaysIncludeInPlanner ->
                            "${product.plannerItemType.lowercase()} · fixed ${product.fixedPurchaseUnits} unit(s)"
                        else -> product.plannerItemType.lowercase().replaceFirstChar(Char::uppercase)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
internal fun ProductSearchField(initialQuery: String, onQueryChange: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initialQuery)) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = field,
        onValueChange = {
            field = it
            onQueryChange(it.text)
        },
        label = { Text("Search saved products") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth().testTag("product_search")
    )
}

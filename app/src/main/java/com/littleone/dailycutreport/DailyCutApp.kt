package com.littleone.dailycutreport

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun DateHeader(
    date: LocalDate,
    dateViewModel: ReportDateViewModel,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
            OutlinedButton(modifier = Modifier.size(48.dp), onClick = dateViewModel::previous) { Text("‹") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    DatePickerDialog(
                        context,
                        { _, year, month, day -> dateViewModel.select(LocalDate.of(year, month + 1, day)) },
                        date.year, date.monthValue - 1, date.dayOfMonth
                    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                }
            ) {
                Text(date.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")))
            }
            OutlinedButton(modifier = Modifier.size(48.dp), onClick = dateViewModel::next, enabled = date < LocalDate.now()) { Text("›") }
            actions()
    }
}

@Composable
internal fun TodayScreen(
    selectedDate: LocalDate,
    dateViewModel: ReportDateViewModel,
    viewModel: TodayViewModel,
    onScan: () -> Unit,
    onEditLog: (FoodLogSnapshot) -> Unit,
    onDeleteLog: (Long) -> Unit,
    onMessage: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var expandedTargets by remember { mutableStateOf(false) }
    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            val saved = viewModel.writeReport(uri)
            onMessage(if (saved) "Report saved" else "Could not save report")
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { onMessage(it); viewModel.clearMessage() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            DateHeader(selectedDate, dateViewModel) {
                FilledIconButton(
                    modifier = Modifier.size(48.dp),
                    onClick = {
                        scope.launch {
                            viewModel.createShareUri()?.let { uri ->
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "image/png"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, "Share daily report"))
                            }
                        }
                    }
                ) { Icon(painterResource(R.drawable.ic_share), contentDescription = "Share report") }
                FilledIconButton(
                    modifier = Modifier.size(48.dp),
                    onClick = {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            createDocumentLauncher.launch("DailyCutReport_${state.report.date}.png")
                        } else scope.launch {
                            val uri = viewModel.saveReport()
                            onMessage(if (uri == null) "Could not save report" else "Report saved")
                        }
                    }
                ) { Icon(painterResource(R.drawable.ic_download), contentDescription = "Save report") }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.report.verdict.label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text(balanceLabel(state.report.energyBalance), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    MetricRow("Burn", "${state.report.finalBurnCalories.roundToInt()} kcal")
                    MetricRow("Food", "${state.report.finalFoodCalories.roundToInt()} kcal")
                    MetricRow("Protein", "${state.report.finalProteinG.roundToInt()} g")
                    MetricRow("Sodium", "${state.report.finalSodiumMg.roundToInt()} mg")
                    Text(
                        if (state.report.finalBurnCalories > 0.0) {
                            "Health data updated ${Instant.ofEpochMilli(state.report.savedAtEpochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM, HH:mm"))}"
                        } else "Health burn data has not been loaded",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan food") }
                }
            }
        }
        item {
            NutritionTargetsCard(
                state.report.nutrition, state.targets, expandedTargets,
                title = if (state.goals.mode == GoalMode.DEFICIT) {
                    "Deficit goal · ${state.goals.desiredDeficitCalories.roundToInt()} kcal"
                } else "Nutrition targets"
            ) { expandedTargets = !expandedTargets }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Daily spending", style = MaterialTheme.typography.titleLarge)
                    MetricRow("Known spending", formatMoney(state.spending.knownTotalMicros, state.goals.currencyCode))
                    MetricRow("Catalog estimate", formatMoney(state.spending.catalogEstimatedMicros, state.goals.currencyCode))
                    if (state.spending.actualPaidEntries > 0) MetricRow(
                        "Actual paid overrides",
                        formatMoney(state.spending.actualPaidMicros, state.goals.currencyCode)
                    )
                    MetricRow("Budget", formatMoney(state.goals.dailyBudgetMicros, state.goals.currencyCode))
                    if (!state.spending.isComplete) Text(
                        "${state.spending.unknownEntries} entr${if (state.spending.unknownEntries == 1) "y has" else "ies have"} unknown cost.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(
                        onClick = viewModel::planRemainingDay,
                        enabled = !state.planning && state.goals.dailyBudgetMicros > 0L,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.planning) "Planning…" else "Plan remaining day") }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Activity", style = MaterialTheme.typography.titleLarge)
                    MetricRow("Steps", state.report.health.steps.toString())
                    MetricRow("Distance", "%.1f km".format(state.report.health.distanceKm))
                    MetricRow("Active burn", "${state.report.health.activeCalories.roundToInt()} kcal")
                    MetricRow("Total burn", "${state.report.health.totalCalories.roundToInt()} kcal")
                    Text(state.report.health.healthConnectStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Food log", style = MaterialTheme.typography.titleLarge) }
        if (state.logs.isEmpty()) item { Text("No food entries for this date.") }
        items(state.logs, key = { it.id }) { log ->
            FoodLogCard(log, state.goals.currencyCode, onEdit = { onEditLog(log) }, onDelete = { onDeleteLog(log.id) })
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    state.recommendations?.let { RecommendationDialog(it, state.goals.currencyCode, viewModel::clearRecommendations) }
}

@Composable
private fun NutritionTargetsCard(
    nutrition: NutritionSummary,
    targets: DailyNutritionTargets,
    expanded: Boolean,
    title: String = "Nutrition targets",
    onToggle: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            TargetRow("Calories", nutrition.calories, targets.calories, "kcal")
            TargetRow("Protein", nutrition.proteinG, targets.proteinG, "g")
            TargetRow("Sodium", nutrition.sodiumMg, targets.sodiumMg, "mg")
            if (expanded) {
                TargetRow("Carbs", nutrition.carbsG, targets.carbsG, "g")
                TargetRow("Fat", nutrition.fatG, targets.fatG, "g")
                TargetRow("Sugar", nutrition.sugarG, targets.sugarG, "g")
                TargetRow("Fiber", nutrition.fiberG, targets.fiberG, "g")
                TargetRow("Saturated fat", nutrition.saturatedFatG, targets.saturatedFatG, "g")
            }
            TextButton(onClick = onToggle) { Text(if (expanded) "Show less" else "Show all nutrients") }
        }
    }
}

@Composable
private fun TargetRow(label: String, value: Double, target: Double, unit: String) {
    val safeValue = value.takeIf(Double::isFinite)?.coerceAtLeast(0.0) ?: 0.0
    val hasTarget = target.isFinite() && target > 0.0
    val progress = targetProgress(safeValue, target)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text(
                if (hasTarget) "${safeValue.roundToInt()} / ${target.roundToInt()} $unit"
                else "${safeValue.roundToInt()} $unit · no target",
                fontWeight = FontWeight.Bold
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
internal fun FoodsScreen(
    selectedDate: LocalDate,
    dateViewModel: ReportDateViewModel,
    viewModel: FoodsViewModel,
    onScan: () -> Unit,
    onOcr: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var manualCode by remember { mutableStateOf("") }
    var showManualTools by remember { mutableStateOf(false) }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = onScan) { Text("Scan") }
    }) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Foods", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Adding to ${selectedDate.format(DateTimeFormatter.ofPattern("EEE, dd MMM"))}", style = MaterialTheme.typography.bodySmall)
                DateHeader(selectedDate, dateViewModel)
            }
            item {
                ProductSearchField(state.query, viewModel::setQuery)
                OutlinedButton(onClick = viewModel::createBulkPurchase, modifier = Modifier.fillMaxWidth()) {
                    Text("Bulk log a purchase")
                }
                TextButton(onClick = { showManualTools = !showManualTools }) {
                    Text(if (showManualTools) "Hide manual options" else "Manual options")
                }
                if (showManualTools) {
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        label = { Text("Barcode / product code") },
                        trailingIcon = { TextButton(onClick = { viewModel.handleBarcode(manualCode) }) { Text("Use") } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = viewModel::createProduct) { Text("Create product manually") }
                }
            }
            if (state.query.isBlank() && state.recentProducts.isNotEmpty()) {
                item { Text("Recent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(state.recentProducts, key = { "recent-${it.productId}" }) { product ->
                    ProductCatalogRow(product, state.goals.currencyCode, viewModel)
                }
                item { Text("All products", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            }
            items(state.products, key = { it.productId }) { product ->
                ProductCatalogRow(product, state.goals.currencyCode, viewModel)
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
internal fun FoodWorkflowDialogs(
    viewModel: FoodsViewModel,
    onOcr: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    when (val workflow = state.workflow) {
        FoodWorkflowState.Idle -> Unit
        is FoodWorkflowState.ConfirmQuantity -> QuantityDialog(
            workflow.product,
            state.goals.currencyCode,
            onDismiss = viewModel::cancelDialogs,
            onConfirm = viewModel::confirmAdd
        )
        is FoodWorkflowState.EditProduct -> ProductEditorDialog(
            initialBarcode = workflow.barcode,
            existing = workflow.product,
            addAfterSave = workflow.addAfterSave,
            ocrDraft = workflow.ocrDraft,
            currencyCode = state.goals.currencyCode,
            onScanNutrition = onOcr,
            onDismiss = viewModel::cancelDialogs,
            onSave = viewModel::saveProduct
        )
        is FoodWorkflowState.EditQuantity -> FoodLogEditDialog(
            workflow.log,
            state.goals.currencyCode,
            onDismiss = viewModel::cancelDialogs,
            onSave = viewModel::saveLogEdit
        )
        is FoodWorkflowState.BuildBulkPurchase -> BulkPurchaseDialog(
            products = workflow.products,
            currencyCode = state.goals.currencyCode,
            onDismiss = viewModel::cancelDialogs,
            onConfirm = viewModel::confirmBulkPurchase
        )
    }
}

@Composable
private fun FoodLogCard(log: FoodLogSnapshot, currencyCode: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${log.quantity.toDisplay()} × ${log.productName}", fontWeight = FontWeight.Bold)
                log.mealName?.let { Text("Bulk log · $it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                Text("${log.calories.roundToInt()} kcal · ${log.proteinG.roundToInt()} g protein", style = MaterialTheme.typography.bodyMedium)
                Text("${log.sodiumMg.roundToInt()} mg sodium · ${log.carbsG.roundToInt()} g carbs", style = MaterialTheme.typography.bodySmall)
                Text(
                    (log.recordedCostMicros?.let { formatMoney(it, currencyCode) } ?: "Cost unknown") +
                        if (log.excludeCostFromBudget) " · ignored in budget" else "",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(painterResource(R.drawable.ic_more), contentDescription = "Food entry actions")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(text = { Text("Edit servings") }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun BulkPurchaseDialog(
    products: List<ProductWithExtras>,
    currencyCode: String,
    onDismiss: () -> Unit,
    onConfirm: (String, List<BulkLogEntryInput>, Long?, Boolean) -> Unit
) {
    var label by remember { mutableStateOf("") }
    var quantities by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var actualPaid by remember { mutableStateOf("") }
    var excludeCostFromBudget by remember { mutableStateOf(false) }
    val parsedPaid = runCatching { parseMoneyMicros(actualPaid) }.getOrNull()
    val entries = products.mapNotNull { product ->
        quantities[product.product.productId]?.numberOrNull()?.takeIf { it > 0.0 }?.let {
            BulkLogEntryInput(product, it)
        }
    }
    val valid = entries.size >= 2 && (actualPaid.isBlank() || parsedPaid != null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bulk log a purchase") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    label,
                    { label = it },
                    label = { Text("Store or group label (optional)") },
                    placeholder = { Text("e.g. 7-Eleven") },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Choose at least two products and enter the final checkout price once. Each product is still logged as its own nutrition entry.",
                    style = MaterialTheme.typography.bodySmall
                )
                products.forEach { product ->
                    val id = product.product.productId
                    val selected = id in quantities
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(product.product.name, fontWeight = FontWeight.Bold)
                                    Text(product.product.servingLabel, style = MaterialTheme.typography.bodySmall)
                                }
                                Switch(selected, onCheckedChange = { checked ->
                                    quantities = if (checked) quantities + (id to "1") else quantities - id
                                })
                            }
                            if (selected) DecimalField("Quantity", quantities[id].orEmpty()) { value ->
                                quantities = quantities + (id to value)
                            }
                        }
                    }
                }
                DecimalField("Total actually paid ($currencyCode, optional)", actualPaid) { actualPaid = it }
                Text(
                    "The exact total is allocated internally using catalog estimates (or quantities when estimates are missing). You do not need to calculate item discounts.",
                    style = MaterialTheme.typography.bodySmall
                )
                ToggleRow("Log the price but ignore it in budget calculations", excludeCostFromBudget) { excludeCostFromBudget = it }
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                onConfirm(label.trim(), entries, parsedPaid, excludeCostFromBudget)
            }) { Text("Log items") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun RecommendationDialog(result: RecommendationResult, currencyCode: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remaining-day suggestions") },
        text = {
            Column(
                Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                result.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                if (result.blockingViolations.isNotEmpty()) {
                    Text("Values preventing a complete plan", fontWeight = FontWeight.Bold)
                    result.blockingViolations.forEach { violation ->
                        Text(
                            "${violation.label}: ${constraintValue(violation, currencyCode)} " +
                                "(target ${constraintTarget(violation, currencyCode)}, ${"%+.1f".format(violation.percentDifference)}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                if (result.excludedUnpricedProducts > 0) Text(
                    "${result.excludedUnpricedProducts} unpriced product(s) were excluded.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (result.excludedFromPlanningProducts > 0) Text(
                    "${result.excludedFromPlanningProducts} product(s) are disabled for planning.",
                    style = MaterialTheme.typography.bodySmall
                )
                if (result.spendingIncomplete) Text(
                    "Existing unknown costs mean budget totals are estimates.",
                    style = MaterialTheme.typography.bodySmall
                )
                result.plans.forEachIndexed { index, plan ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                if (plan.minimumTargetFallback) "Best minimum-target option" else "Option ${index + 1}",
                                fontWeight = FontWeight.Bold
                            )
                            plan.items.forEach { item ->
                                Text("${item.purchaseUnits} × ${item.name} · ${item.servings.toDisplay()} servings" +
                                    if (item.fixed) " · fixed" else "")
                            }
                            MetricRow("Additional cost", formatMoney(plan.totalCostMicros, currencyCode))
                            MetricRow("Projected spending", formatMoney(plan.projectedSpendingMicros, currencyCode))
                            MetricRow("Projected calories", "${plan.nutrition.calories.roundToInt()} kcal")
                            MetricRow("Projected protein", "${plan.nutrition.proteinG.roundToInt()} g")
                            Text(plan.explanation, style = MaterialTheme.typography.bodySmall)
                            plan.deltas.filterNot { it.withinTolerance }.forEach { delta ->
                                Text(
                                    "${delta.label}: ${"%+.1f".format(delta.percentDifference)}% from target",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
                if (result.plans.isEmpty()) Text("No suggestions available for the current catalog and limits.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun constraintValue(delta: ConstraintDelta, currencyCode: String): String = when (delta.label) {
    "Budget" -> formatMoney((delta.actual * MONEY_MICROS_PER_UNIT).toLong(), currencyCode)
    "Calories" -> "${delta.actual.roundToInt()} kcal"
    "Sodium" -> "${delta.actual.roundToInt()} mg"
    else -> "${delta.actual.toDisplay()} g"
}

private fun constraintTarget(delta: ConstraintDelta, currencyCode: String): String = when (delta.label) {
    "Budget" -> formatMoney((delta.target * MONEY_MICROS_PER_UNIT).toLong(), currencyCode)
    "Calories" -> "${delta.target.roundToInt()} kcal"
    "Sodium" -> "${delta.target.roundToInt()} mg"
    else -> "${delta.target.toDisplay()} g"
}

@Composable
private fun ProductCatalogRow(product: ProductEntity, currencyCode: String, viewModel: FoodsViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(product.name, fontWeight = FontWeight.Bold)
                Text(listOfNotNull(product.brand.takeIf { it.isNotBlank() }, product.barcode).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                product.purchasePriceMicros?.let {
                    Text("${formatMoney(it, currencyCode)} / ${product.purchaseUnitServings.toDisplay()} serving(s)", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    when {
                        !product.includeInPlanner -> "Not used in planning"
                        product.alwaysIncludeInPlanner -> "${product.plannerItemType.lowercase()} · fixed in plans"
                        else -> product.plannerItemType.lowercase().replaceFirstChar(Char::uppercase)
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
            TextButton(onClick = { viewModel.editProduct(product) }) { Text("Edit") }
            Button(onClick = { viewModel.selectProduct(product) }) { Text("Add") }
        }
    }
}

@Composable
internal fun ProductSearchField(initialQuery: String, onQueryChange: (String) -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(initialQuery)) }
    OutlinedTextField(
        value = field,
        onValueChange = {
            field = it
            onQueryChange(it.text)
        },
        label = { Text("Search saved products") },
        modifier = Modifier.fillMaxWidth().testTag("product_search")
    )
}

@Composable
internal fun OcrCaptureScreen(
    viewModel: OcrViewModel,
    onCancel: () -> Unit,
    onUse: (OcrNutritionDraft) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val capturedFiles = remember { mutableListOf<File>() }
    var pendingFile by remember { mutableStateOf<File?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var cropSource by remember { mutableStateOf<Uri?>(null) }
    var cropSourceFile by remember { mutableStateOf<File?>(null) }
    var cropRegion by remember { mutableStateOf(CropRegion()) }
    var cropRotation by remember { mutableStateOf(0) }
    var cropPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingFile
        val uri = pendingUri
        if (success && file != null && uri != null) {
            capturedFiles += file
            cropSource = uri
            cropSourceFile = file
            cropRegion = CropRegion()
            cropRotation = 0
        } else file?.delete()
        pendingFile = null
        pendingUri = null
    }
    val openImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            cropSource = it
            cropSourceFile = null
            cropRegion = CropRegion()
            cropRotation = 0
        }
    }
    LaunchedEffect(Unit) {
        viewModel.reset()
        File(context.cacheDir, "ocr_captures").listFiles()?.forEach(File::delete)
    }
    DisposableEffect(Unit) {
        onDispose {
            capturedFiles.forEach(File::delete)
            viewModel.reset()
        }
    }
    LaunchedEffect(cropSource, cropRotation) {
        cropPreview = cropSource?.let { source ->
            runCatching { viewModel.preview(source, cropRotation) }.getOrNull()
        }
    }

    cropSource?.let { source ->
        OcrImagePreparation(
            preview = cropPreview,
            crop = cropRegion,
            rotationDegrees = cropRotation,
            preparing = state.preparing,
            onCropChange = { cropRegion = it.normalized() },
            onRotate = { cropRotation = (cropRotation + 90) % 360 },
            onCancel = {
                cropSourceFile?.let { file -> capturedFiles.remove(file); file.delete() }
                cropSource = null
                cropSourceFile = null
                cropPreview = null
            },
            onPrepare = {
                scope.launch {
                    if (viewModel.prepareImage(source, cropRegion, cropRotation)) {
                        cropSourceFile?.let { file -> capturedFiles.remove(file); file.delete() }
                        cropSource = null
                        cropSourceFile = null
                        cropPreview = null
                    }
                }
            }
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Nutrition label OCR", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Images are processed on-device and discarded when you leave this screen.")
            Text(
                "For cans and bottles, fill the frame with the label and take two overlapping photos so each column is close to the center.",
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            Text("Recognition language", fontWeight = FontWeight.Bold)
            OcrLanguage.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { language ->
                        val selected = state.language == language
                        if (selected) Button(onClick = { viewModel.setLanguage(language) }, modifier = Modifier.weight(1f)) {
                            Text(language.label)
                        } else OutlinedButton(onClick = { viewModel.setLanguage(language) }, modifier = Modifier.weight(1f)) {
                            Text(language.label)
                        }
                    }
                }
            }
        }
        item {
            Text("Images ${state.images.size}/3", fontWeight = FontWeight.Bold)
            state.images.forEachIndexed { index, image ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Label crop ${index + 1}")
                        TextButton(onClick = { viewModel.removeImage(image) }) { Text("Remove") }
                    }
                    image.quality.warnings.forEach { warning ->
                        Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    if (image.quality.warnings.isEmpty()) {
                        Text("Image quality looks suitable.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = state.images.size < 3,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        val directory = File(context.cacheDir, "ocr_captures").apply { mkdirs() }
                        val file = File.createTempFile("label_", ".jpg", directory)
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        pendingFile = file
                        pendingUri = uri
                        takePicture.launch(uri)
                    }
                ) { Text("Camera") }
                OutlinedButton(
                    enabled = state.images.size < 3,
                    modifier = Modifier.weight(1f),
                    onClick = { openImage.launch(arrayOf("image/*")) }
                ) { Text("Gallery") }
            }
        }
        item {
            Button(
                enabled = state.images.isNotEmpty() && !state.processing,
                modifier = Modifier.fillMaxWidth(),
                onClick = viewModel::extract
            ) { Text(if (state.processing) "Reading labels…" else "Extract nutrition") }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        state.review?.let { review ->
            item {
                val defaultBasis = review.availableBases.firstOrNull() ?: OcrBasis.PER_SERVING
                var selectedBasis by remember(review) { mutableStateOf(defaultBasis) }
                Text("Review extracted values", style = MaterialTheme.typography.titleLarge)
                Text("Select the label column to copy into the product editor.")
                review.availableBases.forEach { basis ->
                    if (selectedBasis == basis) Button(onClick = { selectedBasis = basis }, modifier = Modifier.fillMaxWidth()) {
                        Text(basis.label)
                    } else OutlinedButton(onClick = { selectedBasis = basis }, modifier = Modifier.fillMaxWidth()) {
                        Text(basis.label)
                    }
                }
                review.proposals.forEach { proposal ->
                    val key = OcrSelectionKey(proposal.field, selectedBasis)
                    val options = proposal.alternatives[selectedBasis].orEmpty()
                    val selectedId = state.selections[key]
                    if (options.size <= 1) {
                        val candidate = options.firstOrNull() ?: proposal.candidates[selectedBasis]
                        MetricRow(
                            proposal.field.label,
                            candidate?.let { "${it.value.toReviewInput()} ${it.unit}${if (it.converted) " (converted)" else ""}" } ?: "Not found"
                        )
                        candidate?.let {
                            Text("${it.engine.label}: ${it.sourceText}", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text("${proposal.field.label} — choose a result", fontWeight = FontWeight.Bold)
                        options.forEach { candidate ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = selectedId == candidate.candidateId,
                                    onClick = { viewModel.selectCandidate(proposal.field, selectedBasis, candidate.candidateId) }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text("${candidate.value.toReviewInput()} ${candidate.unit} · ${candidate.engine.label}")
                                    Text(candidate.sourceText, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = selectedId == null,
                                onClick = { viewModel.selectCandidate(proposal.field, selectedBasis, null) }
                            )
                            Text("Leave blank")
                        }
                    }
                }
                review.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onUse(review.draftFor(selectedBasis, state.selections)) }
                ) { Text("Use selected values") }
            }
        }
        item { OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") } }
    }
}

@Composable
private fun OcrImagePreparation(
    preview: android.graphics.Bitmap?,
    crop: CropRegion,
    rotationDegrees: Int,
    preparing: Boolean,
    onCropChange: (CropRegion) -> Unit,
    onRotate: () -> Unit,
    onCancel: () -> Unit,
    onPrepare: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Crop nutrition table", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Keep only one product in this OCR session. Crop tightly around the table; use another overlapping crop for the curved edge of a bottle.")
        if (preview == null) {
            Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) { Text("Loading image…") }
        } else {
            val ratio = preview.width.toFloat() / preview.height.coerceAtLeast(1)
            val borderColor = MaterialTheme.colorScheme.primary
            Box(Modifier.fillMaxWidth().aspectRatio(ratio)) {
                Image(preview.asImageBitmap(), null, Modifier.fillMaxSize())
                Canvas(Modifier.fillMaxSize()) {
                    val left = size.width * crop.left
                    val top = size.height * crop.top
                    val right = size.width * crop.right
                    val bottom = size.height * crop.bottom
                    val shade = Color.Black.copy(alpha = 0.45f)
                    drawRect(shade, size = androidx.compose.ui.geometry.Size(size.width, top))
                    drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(0f, bottom), size = androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
                    drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(left, bottom - top))
                    drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(right, top), size = androidx.compose.ui.geometry.Size(size.width - right, bottom - top))
                    drawRect(
                        borderColor,
                        topLeft = androidx.compose.ui.geometry.Offset(left, top),
                        size = androidx.compose.ui.geometry.Size(right - left, bottom - top),
                        style = Stroke(width = 5f)
                    )
                }
            }
        }
        CropSlider("Left", crop.left, 0f..(crop.right - 0.1f).coerceAtLeast(0f)) {
            onCropChange(crop.copy(left = it))
        }
        CropSlider("Right", crop.right, (crop.left + 0.1f).coerceAtMost(1f)..1f) {
            onCropChange(crop.copy(right = it))
        }
        CropSlider("Top", crop.top, 0f..(crop.bottom - 0.1f).coerceAtLeast(0f)) {
            onCropChange(crop.copy(top = it))
        }
        CropSlider("Bottom", crop.bottom, (crop.top + 0.1f).coerceAtMost(1f)..1f) {
            onCropChange(crop.copy(bottom = it))
        }
        OutlinedButton(onClick = onRotate, modifier = Modifier.fillMaxWidth()) { Text("Rotate 90° ($rotationDegrees°)") }
        Button(enabled = preview != null && !preparing, onClick = onPrepare, modifier = Modifier.fillMaxWidth()) {
            Text(if (preparing) "Preparing…" else "Use this crop")
        }
        OutlinedButton(enabled = !preparing, onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel crop") }
    }
}

@Composable
private fun CropSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text("$label ${(value * 100).roundToInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
    }
}

@Composable
internal fun SettingsScreen(
    selectedDate: LocalDate,
    viewModel: SettingsViewModel,
    onMessage: (String) -> Unit,
    onGrantCorePermissions: () -> Unit,
    onGrantNutritionPermission: () -> Unit,
    onGrantNutritionWritePermission: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    var pendingBackupAction by remember { mutableStateOf<Pair<Uri, Boolean>?>(null) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        uri?.let { pendingBackupAction = it to true }
    }
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { pendingBackupAction = it to false }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    LaunchedEffect(state.message) {
        state.message?.let { onMessage(it); viewModel.clearMessage() }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { GoalsSettingsCard(state.goals, viewModel::saveGoals) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Health Connect", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.healthAvailable) "Available" else "Unavailable")
                    Text(if (state.corePermissionsGranted) "Activity permissions granted" else "Activity permissions required")
                    Button(
                        onClick = onGrantCorePermissions,
                        enabled = state.healthAvailable && !state.corePermissionsGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.corePermissionsGranted) "Activity permissions granted" else "Grant activity permissions") }
                    OutlinedButton(
                        onClick = { viewModel.refreshHealth(selectedDate) },
                        enabled = state.healthAvailable && state.corePermissionsGranted && !state.isRefreshingHealth,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.isRefreshingHealth) "Refreshing…" else "Refresh selected date") }
                    Text(if (state.nutritionPermissionGranted) "Optional nutrition read permission granted" else "Optional nutrition read permission not granted")
                    OutlinedButton(
                        onClick = onGrantNutritionPermission,
                        enabled = state.healthAvailable && !state.nutritionPermissionGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.nutritionPermissionGranted) "Nutrition fallback enabled" else "Enable nutrition fallback") }
                    Text(if (state.nutritionWritePermissionGranted) "Optional nutrition write permission granted" else "Optional nutrition write permission not granted")
                    OutlinedButton(
                        onClick = onGrantNutritionWritePermission,
                        enabled = state.healthAvailable && !state.nutritionWritePermissionGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.nutritionWritePermissionGranted) "Automatic nutrition sync enabled" else "Enable automatic nutrition sync") }
                    state.nutritionSyncStatus?.let { Text("Nutrition sync: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Encrypted local backup", style = MaterialTheme.typography.titleLarge)
                    Text("System cloud backup is disabled. Export a password-protected file for device migration.")
                    Button(
                        onClick = { createBackup.launch("DailyCutReport_${LocalDate.now()}.dcrbackup") },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Export full backup") }
                    OutlinedButton(
                        onClick = { openBackup.launch(arrayOf("application/octet-stream", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Restore full backup") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Strictly offline", style = MaterialTheme.typography.titleLarge)
                    Text("The packaged app has no Internet or network-state permission. Barcode recognition runs from the bundled on-device model.")
                    Text("Food data and reports stay in the app's local Room database. Android cloud backup is disabled.")
                    Text("Chinese, English, and Japanese label OCR uses bundled on-device models.")
                    Text("Nutrition-label OCR remains assistive; review recognized values before saving.")
                    Text("The Quick Scan widget opens the same offline scanner directly.")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("DailyCutReport ${packageInfo.versionName}", fontWeight = FontWeight.Bold)
                    Text("Database schema 4 · Build ${packageInfo.longVersionCode}")
                }
            }
        }
    }
    pendingBackupAction?.let { (uri, exporting) ->
        BackupPasswordDialog(
            exporting = exporting,
            onDismiss = { pendingBackupAction = null },
            onConfirm = { password ->
                if (exporting) viewModel.exportBackup(uri, password) else viewModel.restoreBackup(uri, password)
                pendingBackupAction = null
            }
        )
    }
}

@Composable
private fun GoalsSettingsCard(goals: UserGoals, onSave: (UserGoals) -> Unit) {
    var mode by remember(goals) { mutableStateOf(goals.mode) }
    var calories by remember(goals) { mutableStateOf(goals.calories.toInput()) }
    var expectedBurn by remember(goals) { mutableStateOf(goals.expectedBurnCalories.toInput()) }
    var deficit by remember(goals) { mutableStateOf(goals.desiredDeficitCalories.toInput()) }
    var protein by remember(goals) { mutableStateOf(goals.proteinG.toInput()) }
    var sodium by remember(goals) { mutableStateOf(goals.sodiumMg.toInput()) }
    var carbs by remember(goals) { mutableStateOf(goals.carbsG.toInput()) }
    var fat by remember(goals) { mutableStateOf(goals.fatG.toInput()) }
    var sugar by remember(goals) { mutableStateOf(goals.sugarG.toInput()) }
    var fiber by remember(goals) { mutableStateOf(goals.fiberG.toInput()) }
    var saturated by remember(goals) { mutableStateOf(goals.saturatedFatG.toInput()) }
    var currency by remember(goals) { mutableStateOf(goals.currencyCode) }
    var budget by remember(goals) { mutableStateOf(goals.dailyBudgetMicros.toMoneyInput()) }
    val numbers = listOf(calories, expectedBurn, deficit, protein, sodium, carbs, fat, sugar, fiber, saturated)
    val budgetMicros = runCatching { parseMoneyMicros(budget) }.getOrNull()
    val candidate = runCatching {
        UserGoals(
            mode = mode,
            calories = calories.numberOrNull() ?: error("Enter calories"),
            expectedBurnCalories = expectedBurn.numberOrNull() ?: error("Enter expected burn"),
            desiredDeficitCalories = deficit.numberOrNull() ?: error("Enter deficit"),
            proteinG = protein.numberOrNull() ?: error("Enter protein"),
            sodiumMg = sodium.numberOrNull() ?: error("Enter sodium"),
            carbsG = carbs.numberOrNull() ?: error("Enter carbs"),
            fatG = fat.numberOrNull() ?: error("Enter fat"),
            sugarG = sugar.numberOrNull() ?: error("Enter sugar"),
            fiberG = fiber.numberOrNull() ?: error("Enter fiber"),
            saturatedFatG = saturated.numberOrNull() ?: error("Enter saturated fat"),
            currencyCode = currency.trim().uppercase(),
            dailyBudgetMicros = budgetMicros ?: error("Enter budget")
        ).requireValid()
    }.getOrNull()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Goals and daily budget", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = mode == GoalMode.CALORIE, onClick = { mode = GoalMode.CALORIE })
                Text("Calorie goal")
                RadioButton(selected = mode == GoalMode.DEFICIT, onClick = { mode = GoalMode.DEFICIT })
                Text("Deficit goal")
            }
            if (mode == GoalMode.CALORIE) DecimalField("Calories kcal", calories) { calories = it }
            else {
                DecimalField("Expected daily burn kcal", expectedBurn) { expectedBurn = it }
                DecimalField("Desired deficit kcal", deficit) { deficit = it }
                candidate?.let { Text("Effective allowance: ${it.effectiveCalorieTarget.roundToInt()} kcal", style = MaterialTheme.typography.bodySmall) }
            }
            DecimalField("Protein minimum g", protein) { protein = it }
            DecimalField("Sodium maximum mg", sodium) { sodium = it }
            DecimalField("Carbs maximum g", carbs) { carbs = it }
            DecimalField("Fat maximum g", fat) { fat = it }
            DecimalField("Sugar maximum g", sugar) { sugar = it }
            DecimalField("Fiber minimum g", fiber) { fiber = it }
            DecimalField("Saturated fat maximum g", saturated) { saturated = it }
            OutlinedTextField(
                value = currency,
                onValueChange = { currency = it.take(3).uppercase() },
                label = { Text("Currency code") },
                modifier = Modifier.fillMaxWidth()
            )
            DecimalField("Daily budget", budget) { budget = it }
            Text("Targets allow 10% planning tolerance; plans up to 10% over budget rank lower.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = candidate != null && numbers.all { it.isNotBlank() }, onClick = { candidate?.let(onSave) }, modifier = Modifier.fillMaxWidth()) {
                Text("Save goals")
            }
        }
    }
}

@Composable
private fun BackupPasswordDialog(exporting: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    val valid = password.length >= 8 && (!exporting || password == confirmation)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (exporting) "Protect backup" else "Restore backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!exporting) Text("Restoring replaces all current products, reports, and food logs after validation.")
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (exporting) OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Confirm password") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(if (exporting) "Use at least 8 characters. The password cannot be recovered." else "Enter the password used when this backup was created.")
            }
        },
        confirmButton = { Button(enabled = valid, onClick = { onConfirm(password) }) { Text(if (exporting) "Export" else "Replace and restore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun QuantityDialog(
    product: ProductWithExtras,
    currencyCode: String,
    onDismiss: () -> Unit,
    onConfirm: (Double, Long?, Boolean) -> Unit
) {
    var quantity by remember { mutableStateOf("1") }
    var actualPaid by remember { mutableStateOf("") }
    var excludeCostFromBudget by remember { mutableStateOf(false) }
    val parsedQuantity = quantity.numberOrNull()?.takeIf { it > 0.0 }
    val parsedPaid = runCatching { parseMoneyMicros(actualPaid) }.getOrNull()
    val priceValid = actualPaid.isBlank() || parsedPaid != null
    val estimated = product.product.purchasePriceMicros?.let { price ->
        parsedQuantity?.let { (price / product.product.purchaseUnitServings * it).toLong() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(product.product.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(product.product.servingLabel)
                DecimalField("Quantity", quantity) { quantity = it }
                estimated?.let { Text("Catalog estimate: ${formatMoney(it, currencyCode)}", style = MaterialTheme.typography.bodySmall) }
                DecimalField("Actual paid total (optional)", actualPaid) { actualPaid = it }
                Text("Leave blank for the catalog estimate. Enter 0 for a free item.", style = MaterialTheme.typography.bodySmall)
                ToggleRow("Ignore this price in budget calculations", excludeCostFromBudget) { excludeCostFromBudget = it }
            }
        },
        confirmButton = { Button(enabled = parsedQuantity != null && priceValid, onClick = { parsedQuantity?.let { onConfirm(it, parsedPaid, excludeCostFromBudget) } }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ProductEditorDialog(
    initialBarcode: String?,
    existing: ProductWithExtras?,
    addAfterSave: Boolean,
    ocrDraft: OcrNutritionDraft?,
    currencyCode: String,
    onScanNutrition: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (ProductEntity, List<ProductExtraNutrientEntity>, Double) -> Unit
) {
    val product = existing?.product
    var barcode by remember(initialBarcode, existing) { mutableStateOf(product?.barcode ?: initialBarcode.orEmpty()) }
    var name by remember(existing) { mutableStateOf(product?.name.orEmpty()) }
    var brand by remember(existing) { mutableStateOf(product?.brand.orEmpty()) }
    var serving by remember(existing, ocrDraft) { mutableStateOf(ocrDraft?.servingLabel ?: product?.servingLabel ?: "1 serving") }
    var calories by remember(existing, ocrDraft) { mutableStateOf(ocrDraft?.values?.get(OcrField.CALORIES)?.toInput() ?: product?.calories?.toInput().orEmpty()) }
    var protein by remember(existing, ocrDraft) { mutableStateOf(ocrDraft?.values?.get(OcrField.PROTEIN)?.toInput() ?: product?.proteinG?.toInput().orEmpty()) }
    var sodium by remember(existing, ocrDraft) { mutableStateOf(ocrDraft?.values?.get(OcrField.SODIUM)?.toInput() ?: product?.sodiumMg?.toInput().orEmpty()) }
    var carbs by remember(existing, ocrDraft) { mutableStateOf(ocrDraft?.values?.get(OcrField.CARBS)?.toInput() ?: product?.carbsG?.toInput().orEmpty()) }
    var fat by remember(existing, ocrDraft) { mutableStateOf(ocrDraft?.values?.get(OcrField.FAT)?.toInput() ?: product?.fatG?.toInput().orEmpty()) }
    var sugar by remember(existing, ocrDraft) { mutableStateOf(ocrDraft?.values?.get(OcrField.SUGAR)?.toInput() ?: product?.sugarG?.toInput().orEmpty()) }
    var fiber by remember(existing, ocrDraft) { mutableStateOf(ocrDraft?.values?.get(OcrField.FIBER)?.toInput() ?: product?.fiberG?.toInput().orEmpty()) }
    var saturated by remember(existing, ocrDraft) { mutableStateOf(ocrDraft?.values?.get(OcrField.SATURATED_FAT)?.toInput() ?: product?.saturatedFatG?.toInput().orEmpty()) }
    var purchasePrice by remember(existing) { mutableStateOf(product?.purchasePriceMicros?.toMoneyInput().orEmpty()) }
    var purchaseServings by remember(existing) { mutableStateOf(product?.purchaseUnitServings?.toInput() ?: "1") }
    var includeInPlanner by remember(existing) { mutableStateOf(product?.includeInPlanner ?: true) }
    var plannerItemType by remember(existing) {
        mutableStateOf(PlannerItemType.entries.firstOrNull { it.name == product?.plannerItemType } ?: PlannerItemType.FOOD)
    }
    var alwaysIncludeInPlanner by remember(existing) { mutableStateOf(product?.alwaysIncludeInPlanner ?: false) }
    var extras by remember(existing) { mutableStateOf(existing?.extras?.joinToString("\n") { "${it.name}=${it.value} ${it.unit}" }.orEmpty()) }
    val nutrientInputs = listOf(calories, protein, sodium, carbs, fat, sugar, fiber, saturated)
    val parsedPrice = runCatching { parseMoneyMicros(purchasePrice) }.getOrNull()
    val parsedPurchaseServings = purchaseServings.numberOrNull()?.takeIf { it > 0.0 }
    val valid = name.isNotBlank() && nutrientInputs.all { it.isBlank() || it.numberOrNull() != null } &&
        (purchasePrice.isBlank() || parsedPrice != null) && parsedPurchaseServings != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (product == null) "New product" else "Edit product") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(barcode, { barcode = it }, label = { Text("Barcode (optional)") })
                OutlinedTextField(name, { name = it }, label = { Text("Product name") })
                OutlinedTextField(brand, { brand = it }, label = { Text("Brand") })
                OutlinedTextField(serving, { serving = it }, label = { Text("Serving label") })
                OutlinedButton(onClick = onScanNutrition, modifier = Modifier.fillMaxWidth()) { Text("Scan nutrition label") }
                ocrDraft?.let { Text("OCR values: ${it.basis.label}. Review before saving.", style = MaterialTheme.typography.bodySmall) }
                DecimalField("Calories", calories) { calories = it }
                DecimalField("Protein g", protein) { protein = it }
                DecimalField("Sodium mg", sodium) { sodium = it }
                DecimalField("Carbs g", carbs) { carbs = it }
                DecimalField("Fat g", fat) { fat = it }
                DecimalField("Sugar g", sugar) { sugar = it }
                DecimalField("Fiber g", fiber) { fiber = it }
                DecimalField("Saturated fat g", saturated) { saturated = it }
                DecimalField("Purchase price ($currencyCode, optional)", purchasePrice) { purchasePrice = it }
                DecimalField("Minimum purchase servings", purchaseServings) { purchaseServings = it }
                ToggleRow("Include in daily planning", includeInPlanner) {
                    includeInPlanner = it
                    if (!it) alwaysIncludeInPlanner = false
                }
                Text("Planner item type", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = plannerItemType == PlannerItemType.FOOD, onClick = { plannerItemType = PlannerItemType.FOOD })
                    Text("Solid food", Modifier.weight(1f))
                    RadioButton(selected = plannerItemType == PlannerItemType.DRINK, onClick = { plannerItemType = PlannerItemType.DRINK })
                    Text("Drink")
                }
                ToggleRow("Always include one purchase unit in plans", alwaysIncludeInPlanner, enabled = includeInPlanner) {
                    alwaysIncludeInPlanner = it
                }
                OutlinedTextField(extras, { extras = it }, label = { Text("Extra nutrients: Name=12 unit") })
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val productId = product?.productId ?: UUID.randomUUID().toString()
                val entity = ProductEntity(
                    productId = productId,
                    barcode = barcode.trim().ifBlank { null },
                    name = name.trim(),
                    brand = brand.trim(),
                    servingLabel = serving.ifBlank { "1 serving" },
                    calories = calories.number(),
                    proteinG = protein.number(),
                    sodiumMg = sodium.number(),
                    carbsG = carbs.number(),
                    fatG = fat.number(),
                    sugarG = sugar.number(),
                    fiberG = fiber.number(),
                    saturatedFatG = saturated.number(),
                    purchasePriceMicros = parsedPrice,
                    purchaseUnitServings = parsedPurchaseServings ?: 1.0,
                    includeInPlanner = includeInPlanner,
                    plannerItemType = plannerItemType.name,
                    alwaysIncludeInPlanner = alwaysIncludeInPlanner,
                    notes = product?.notes.orEmpty(),
                    createdAt = product?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                onSave(entity, parseExtras(productId, extras), 1.0)
            }) { Text(if (addAfterSave) "Save and continue" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FoodLogEditDialog(log: FoodLogSnapshot, currencyCode: String, onDismiss: () -> Unit, onSave: (FoodQuantityEdit) -> Unit) {
    var quantity by remember { mutableStateOf(log.quantity.toInput()) }
    var actualPaid by remember { mutableStateOf(log.actualPaidTotalMicros?.toMoneyInput().orEmpty()) }
    var excludeCostFromBudget by remember { mutableStateOf(log.excludeCostFromBudget) }
    val parsedQuantity = quantity.numberOrNull()?.takeIf { it > 0.0 }
    val parsedPaid = runCatching { parseMoneyMicros(actualPaid) }.getOrNull()
    val priceValid = actualPaid.isBlank() || parsedPaid != null
    val estimate = log.catalogCostPerServingMicros?.let { cost -> parsedQuantity?.let { (cost * it).toLong() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit servings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(log.productName, fontWeight = FontWeight.Bold)
                Text(log.servingLabel, style = MaterialTheme.typography.bodySmall)
                DecimalField("Quantity", quantity) { quantity = it }
                estimate?.let { Text("Catalog estimate: ${formatMoney(it, currencyCode)}", style = MaterialTheme.typography.bodySmall) }
                DecimalField("Actual paid total (optional)", actualPaid) { actualPaid = it }
                Text("Blank uses the catalog estimate; 0 records a free item.", style = MaterialTheme.typography.bodySmall)
                ToggleRow("Ignore this price in budget calculations", excludeCostFromBudget) { excludeCostFromBudget = it }
            }
        },
        confirmButton = {
            Button(enabled = parsedQuantity != null && priceValid, onClick = {
                parsedQuantity?.let { onSave(log.quantityEdit(it, parsedPaid, excludeCostFromBudget)) }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DecimalField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

private fun String.numberOrNull(): Double? = trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
private fun String.number(): Double = numberOrNull() ?: 0.0
private fun Double.toInput(): String = if (this == 0.0) "" else toString()
private fun Double.toReviewInput(): String = if (this == 0.0) "0" else toString()
private fun Double.toDisplay(): String = if (this == toLong().toDouble()) toLong().toString() else toString()
private fun balanceLabel(balance: EnergyBalance): String = when (balance) {
    EnergyBalance.Unavailable -> "Add Health Connect burn data"
    is EnergyBalance.Cut -> "−${balance.calories.roundToInt()} kcal"
    is EnergyBalance.Surplus -> "+${balance.calories.roundToInt()} kcal"
    is EnergyBalance.Maintenance -> when {
        balance.calories > 0 -> "−${balance.calories.roundToInt()} kcal"
        balance.calories < 0 -> "+${-balance.calories.roundToInt()} kcal"
        else -> "0 kcal"
    }
}
private fun parseExtras(productId: String, value: String): List<ProductExtraNutrientEntity> = value.lineSequence().mapNotNull { line ->
    val parts = line.split('=', limit = 2)
    if (parts.size != 2) return@mapNotNull null
    val amount = parts[1].trim().split(Regex("\\s+"), limit = 2)
    val number = amount.firstOrNull()?.replace(',', '.')?.toDoubleOrNull() ?: return@mapNotNull null
    val name = parts[0].trim()
    if (name.isBlank()) null else ProductExtraNutrientEntity(productId, name, number, amount.getOrNull(1).orEmpty())
}.toList()

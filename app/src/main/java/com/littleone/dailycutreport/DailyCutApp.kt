package com.littleone.dailycutreport

import android.app.DatePickerDialog
import android.app.TimePickerDialog
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
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
import java.time.LocalTime
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
    onDeleteGroup: (String) -> Unit,
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

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onScan) { Text("Scan") }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
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
                    MetricRow("Burn", "${formatCalories(state.report.finalBurnCalories)} kcal")
                    MetricRow("Food", "${formatCalories(state.report.finalFoodCalories)} kcal")
                    MetricRow("Protein", "${formatDecimal(state.report.finalProteinG)} g")
                    MetricRow("Sodium", "${formatDecimal(state.report.finalSodiumMg)} mg")
                    Text(
                        if (state.report.finalBurnCalories > 0.0) {
                            "Health data updated ${Instant.ofEpochMilli(state.report.savedAtEpochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM, HH:mm"))}"
                        } else "Health burn data has not been loaded",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        item {
            NutritionTargetsCard(
                state.report.nutrition, state.targets, expandedTargets,
                title = if (state.goals.mode == GoalMode.DEFICIT) {
                    "Dynamic deficit goal"
                } else "Nutrition targets",
                subtitle = if (state.goals.mode == GoalMode.DEFICIT) {
                    state.calorieAllowance?.let { allowance ->
                        "${state.report.projectedBurnCalories?.roundToInt()} projected burn − " +
                            "${state.goals.desiredDeficitCalories.roundToInt()} deficit = ${allowance.roundToInt()} kcal allowance"
                    } ?: "Refresh Health Connect to load a projected burn allowance."
                } else null
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
                        enabled = !state.planning && state.goals.dailyBudgetMicros > 0L && state.planningAvailable,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.planning) "Planning…" else if (state.planningAvailable) "Plan remaining day" else "Projected burn required") }
                }
            }
        }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Activity", style = MaterialTheme.typography.titleLarge)
                    MetricRow("Steps", formatInteger(state.report.health.steps))
                    MetricRow("Distance", "${formatDecimal(state.report.health.distanceKm)} km")
                    MetricRow("Active burn", "${formatCalories(state.report.health.activeCalories)} kcal")
                    MetricRow("Total burn", "${formatCalories(state.report.health.totalCalories)} kcal")
                    Text(state.report.health.healthConnectStatus, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item { Text("Food log", style = MaterialTheme.typography.titleLarge) }
        if (state.logs.isEmpty()) item { Text("No food entries for this date.") }
        items(state.logs.groupForDisplay(), key = FoodLogGroup::key) { group ->
            when (group) {
                is FoodLogGroup.Single -> FoodLogCard(
                    group.log, state.goals.currencyCode,
                    onEdit = { onEditLog(group.log) },
                    onDelete = { onDeleteLog(group.log.id) }
                )
                is FoodLogGroup.Bulk -> BulkFoodLogCard(
                    group, state.goals.currencyCode, onEditLog, onDeleteLog,
                    onDeleteGroup = { onDeleteGroup(group.mealId) }
                )
            }
        }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    state.recommendations?.let { RecommendationDialog(it, state.goals.currencyCode, viewModel::clearRecommendations) }
}

@Composable
private fun NutritionTargetsCard(
    nutrition: NutritionSummary,
    targets: DailyNutritionTargets,
    expanded: Boolean,
    title: String = "Nutrition targets",
    subtitle: String? = null,
    onToggle: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
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
                if (hasTarget) "${if (unit == "kcal") formatCalories(safeValue) else formatDecimal(safeValue)} / " +
                    "${if (unit == "kcal") formatCalories(target) else formatDecimal(target)} $unit"
                else "${if (unit == "kcal") formatCalories(safeValue) else formatDecimal(safeValue)} $unit · no target",
                fontWeight = FontWeight.Bold
            )
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FoodsScreen(
    selectedDate: LocalDate,
    dateViewModel: ReportDateViewModel,
    viewModel: FoodsViewModel,
    onScan: (ScanTarget) -> Unit,
    onOcr: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var manualCode by remember { mutableStateOf("") }
    var showManualTools by remember { mutableStateOf(false) }
    var showCart by remember { mutableStateOf(false) }

    Scaffold(floatingActionButton = {
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.mode == FoodMode.BULK || state.bulkDraft.items.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showCart = true },
                    icon = { Text("●") },
                    text = {
                        Text(
                            "Cart ${state.bulkDraft.items.size}" +
                                (bulkEstimateMicros(state.bulkDraft)?.let { " · ${formatMoney(it, state.goals.currencyCode)}" } ?: "")
                        )
                    }
                )
            }
            FloatingActionButton(onClick = { onScan(viewModel.scanTarget()) }) { Text("Scan") }
        }
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
                FilterChip(
                    selected = state.mode == FoodMode.BULK,
                    onClick = {
                        viewModel.setMode(if (state.mode == FoodMode.BULK) FoodMode.NORMAL else FoodMode.BULK)
                    },
                    label = { Text(if (state.mode == FoodMode.BULK) "Bulk cart active" else "Bulk cart") }
                )
                if (state.mode == FoodMode.BULK) Text(
                    "Search and add products below, then enter one final checkout total.",
                    style = MaterialTheme.typography.bodySmall
                )
                ProductSearchField(state.query, viewModel::setQuery)
                TextButton(onClick = { showManualTools = !showManualTools }) {
                    Text(if (showManualTools) "Hide manual options" else "Manual options")
                }
                if (showManualTools) {
                    OutlinedTextField(
                        value = manualCode,
                        onValueChange = { manualCode = it },
                        label = { Text("Barcode / product code") },
                        trailingIcon = { TextButton(onClick = { viewModel.handleBarcode(manualCode, viewModel.scanTarget()) }) { Text("Use") } },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = viewModel::createProduct) { Text("Create product manually") }
                }
            }
            if (state.query.isBlank()) {
                val favoriteIds = state.favoriteProducts.map(ProductEntity::productId).toSet()
                if (state.favoriteProducts.isNotEmpty()) {
                    item { Text("Favorites", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                    items(state.favoriteProducts, key = { "favorite-${it.productId}" }) { product ->
                        ProductCatalogRow(product, state.goals.currencyCode, viewModel, state)
                    }
                }
                val recent = state.recentProducts.filterNot { it.productId in favoriteIds }.take(5)
                item { Text("Recently used", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                if (recent.isEmpty()) {
                    item { Text("Search the catalog to add your first food.", style = MaterialTheme.typography.bodySmall) }
                } else items(recent, key = { "recent-${it.productId}" }) { product ->
                    ProductCatalogRow(product, state.goals.currencyCode, viewModel, state)
                }
            } else {
                item { Text("Search results", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                if (state.products.isEmpty()) {
                    item { Text("No matching products.", style = MaterialTheme.typography.bodySmall) }
                } else items(state.products, key = { it.productId }) { product ->
                    ProductCatalogRow(product, state.goals.currencyCode, viewModel, state)
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (showCart) {
        val maximumCartHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp
        val cartScrollState = rememberScrollState()
        ModalBottomSheet(onDismissRequest = { showCart = false }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maximumCartHeight)
                    .verticalScroll(cartScrollState)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
            ) {
                BulkDraftCard(state.bulkDraft, state.goals.currencyCode, viewModel)
                Spacer(Modifier.height(24.dp))
            }
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
            draft = workflow.draft,
            currencyCode = state.goals.currencyCode,
            onDraftChange = viewModel::updateProductDraft,
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
        is FoodWorkflowState.ReviewMultiScan -> MultiScanReviewDialog(
            workflow.items,
            currencyCode = state.goals.currencyCode,
            onDismiss = viewModel::cancelDialogs,
            onQuantity = viewModel::updateMultiScanQuantity,
            onActualPaid = viewModel::updateMultiScanActualPaid,
            onBudgetExclusion = viewModel::updateMultiScanBudgetExclusion,
            onConfirm = viewModel::confirmMultiScan
        )
    }
}

@Composable
private fun MultiScanReviewDialog(
    items: List<MultiScanItem>,
    currencyCode: String,
    onDismiss: () -> Unit,
    onQuantity: (String, QuantityUnit, String) -> Unit,
    onActualPaid: (String, String) -> Unit,
    onBudgetExclusion: (String, Boolean) -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Review scanned items") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items.forEach { item ->
                    Text(item.product.name, fontWeight = FontWeight.Bold)
                    QuantityInputFields(
                        state = item.quantityInput,
                        onChange = { unit, value -> onQuantity(item.product.productId, unit, value) },
                        onPurchaseUnit = {
                            onQuantity(item.product.productId, QuantityUnit.SERVINGS, item.product.purchaseUnitServings.toDisplay())
                        }
                    )
                    item.quantity?.let { servings ->
                        Text(
                            "${formatCalories(item.product.calories * servings)} kcal · ${formatDecimal(item.product.proteinG * servings)} g protein",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    DecimalField("Actual paid total ($currencyCode, optional)", item.actualPaidText) {
                        onActualPaid(item.product.productId, it)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = item.excludeCostFromBudget,
                            onCheckedChange = { onBudgetExclusion(item.product.productId, it) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Ignore price in daily budget")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = items.isNotEmpty() && items.all { it.quantity != null && it.actualPaidValid },
                onClick = onConfirm
            ) {
                Text("Log ${items.size} products")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FoodLogCard(log: FoodLogSnapshot, currencyCode: String, onEdit: () -> Unit, onDelete: () -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(log.productName, fontWeight = FontWeight.Bold)
                Text(log.loggedQuantity().displayParts().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                log.mealName?.let { Text("Bulk log · $it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
                Text("${formatCalories(log.calories)} kcal · ${formatDecimal(log.proteinG)} g protein", style = MaterialTheme.typography.bodyMedium)
                Text("${formatDecimal(log.sodiumMg)} mg sodium · ${formatDecimal(log.carbsG)} g carbs", style = MaterialTheme.typography.bodySmall)
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
                    DropdownMenuItem(text = { Text("Edit amount") }, onClick = { menuExpanded = false; onEdit() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuExpanded = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun BulkFoodLogCard(
    group: FoodLogGroup.Bulk,
    currencyCode: String,
    onEdit: (FoodLogSnapshot) -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteGroup: () -> Unit
) {
    var expanded by remember(group.mealId) { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(group.label, fontWeight = FontWeight.Bold)
                    Text(
                        "${group.logs.size} items · ${formatCalories(group.calories)} kcal · ${formatDecimal(group.proteinG)} g protein",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        group.recordedCostMicros?.let { formatMoney(it, currencyCode) } ?: "Cost incomplete",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Collapse" else "Details") }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(painterResource(R.drawable.ic_more), contentDescription = "Bulk order actions")
                    }
                    DropdownMenu(menuExpanded, { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Delete entire order") },
                            onClick = { menuExpanded = false; onDeleteGroup() }
                        )
                    }
                }
            }
            if (expanded) group.logs.forEach { log ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(log.productName, fontWeight = FontWeight.SemiBold)
                        Text(log.loggedQuantity().displayParts().joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${formatCalories(log.calories)} kcal · ${log.recordedCostMicros?.let { formatMoney(it, currencyCode) } ?: "unknown cost"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    TextButton(onClick = { onEdit(log) }) { Text("Edit") }
                    TextButton(onClick = { onDelete(log.id) }) { Text("Delete") }
                }
            }
        }
    }
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
internal fun HealthScreen(
    selectedDate: LocalDate,
    dateViewModel: ReportDateViewModel,
    viewModel: HealthViewModel,
    onMessage: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dashboard = state.dashboard
    var showWeightEntry by remember { mutableStateOf(false) }
    var showWeightManager by remember { mutableStateOf(false) }
    var explanationExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(state.message) {
        state.message?.let { onMessage(it); viewModel.clearMessage() }
    }
    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showWeightEntry = true }) { Text("Weight") }
        }
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
        item {
            Text("Health", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            DateHeader(selectedDate, dateViewModel)
        }
        if (dashboard == null) {
            item { Text("Loading local health history…") }
        } else {
            item { DeficitSummaryCard(dashboard) }
            item { WeightAndProjectionCard(dashboard, onManage = { showWeightManager = true }) }
            item { WalkingGuidanceCard(dashboard) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("28-day trend", style = MaterialTheme.typography.titleLarge)
                        HealthTrendChart(dashboard.trends, dashboard.profile.weightUnit)
                        Text("Each series uses its own scale so calorie and weight changes remain readable.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Data quality: ${dashboard.projection.quality.name.lowercase()}", style = MaterialTheme.typography.titleMedium)
                        Text("${dashboard.projection.validDeficitDays} valid deficit days · ${dashboard.projection.weightDays} weight days")
                        dashboard.historyLastSynced?.let { Text("History synced $it", style = MaterialTheme.typography.bodySmall) }
                        Button(
                            onClick = viewModel::refreshHistory,
                            enabled = !state.refreshing,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (state.refreshing) "Refreshing…" else "Refresh Health history") }
                        TextButton(onClick = { explanationExpanded = !explanationExpanded }) {
                            Text(if (explanationExpanded) "Hide calculation" else "How this is calculated")
                        }
                        if (explanationExpanded) Text(
                            "Energy estimates blend your desired deficit with valid recent burn and nutrition days. " +
                                "Weight trends use a robust slope and are limited to 35% of the estimate. Ranges widen for variable or conflicting data. " +
                                "Walking guidance uses plausible personal sessions when available, otherwise body-weight-based estimates. These are planning estimates, not medical predictions.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
    if (showWeightEntry && dashboard != null) {
        WeightEntryDialog(
            selectedDate = selectedDate,
            dashboard = dashboard,
            onDismiss = { showWeightEntry = false },
            onSave = {
                viewModel.saveManualWeight(it.first, it.second)
                showWeightEntry = false
            }
        )
    }
    if (showWeightManager && dashboard != null) {
        WeightRecordingsDialog(
            dashboard,
            onDismiss = { showWeightManager = false },
            onDelete = viewModel::deleteManualWeight
        )
    }
}

@Composable
private fun WeightEntryDialog(
    selectedDate: LocalDate,
    dashboard: HealthDashboard,
    onDismiss: () -> Unit,
    onSave: (Pair<Double, LocalTime>) -> Unit
) {
    val context = LocalContext.current
    var weightInput by remember(selectedDate, dashboard.profile.weightUnit) { mutableStateOf("") }
    var time by remember(selectedDate) { mutableStateOf(if (selectedDate == LocalDate.now()) LocalTime.now() else LocalTime.NOON) }
    val parsed = weightInput.numberOrNull()?.takeIf { it > 0.0 }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weight for $selectedDate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField("Weight (${dashboard.profile.weightUnit.label})", weightInput) { weightInput = it }
                OutlinedButton(
                    onClick = {
                        TimePickerDialog(context, { _, hour, minute -> time = LocalTime.of(hour, minute) }, time.hour, time.minute, true).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Recorded at ${time.format(DateTimeFormatter.ofPattern("HH:mm"))}") }
                Text("Multiple measurements are combined using the daily median for trends.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(enabled = parsed != null, onClick = { parsed?.let { onSave(it to time) } }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DeficitSummaryCard(dashboard: HealthDashboard) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Projected deficit", style = MaterialTheme.typography.titleLarge)
            val deficit = dashboard.projectedDeficitCalories
            Text(
                when {
                    deficit == null -> "Unavailable"
                    deficit >= 0.0 -> "${formatCalories(deficit)} kcal deficit"
                    else -> "${formatCalories(-deficit)} kcal surplus"
                },
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            MetricRow("Projected final burn", dashboard.projectedBurnCalories?.let { "${formatCalories(it)} kcal" } ?: "Unavailable")
            MetricRow("Logged intake", if (dashboard.intakePresent) "${formatCalories(dashboard.intakeCalories)} kcal" else "Unavailable")
            MetricRow("Desired deficit", "${formatCalories(dashboard.desiredDeficitCalories)} kcal")
            MetricRow("Remaining gap", dashboard.remainingDeficitGap?.let { "${formatCalories(it)} kcal" } ?: "Unavailable")
        }
    }
}

@Composable
private fun WeightAndProjectionCard(dashboard: HealthDashboard, onManage: () -> Unit) {
    val projection = dashboard.projection
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Weight and expected change", style = MaterialTheme.typography.titleLarge)
            val latest = dashboard.latestWeight
            if (latest == null) {
                Text("No weight recorded. Manual weights work without Health Connect permission.")
            } else {
                MetricRow(
                    "Latest",
                    "${formatDecimal(dashboard.profile.weightUnit.fromKg(latest.weightKg))} ${dashboard.profile.weightUnit.label} · " +
                        Instant.ofEpochMilli(latest.recordedAtEpochMs).atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("HH:mm"))
                )
                Text(
                    latest.source.name.lowercase().replace('_', ' ') +
                        if (dashboard.selectedDateWeights.size > 1) " · latest of ${dashboard.selectedDateWeights.size} recordings" else "",
                    style = MaterialTheme.typography.bodySmall
                )
                dashboard.selectedDateMedianKg?.takeIf { dashboard.selectedDateWeights.size > 1 }?.let {
                    MetricRow("Daily median", "${formatDecimal(dashboard.profile.weightUnit.fromKg(it))} ${dashboard.profile.weightUnit.label}")
                }
                TextButton(onClick = onManage) { Text("Manage recordings") }
            }
            if (projection.weeklyChangeKg == null) {
                Text("More valid burn, intake, or weight history is needed.")
            } else {
                fun displayKg(value: Double) = "${formatDecimal(dashboard.profile.weightUnit.fromKg(value))} ${dashboard.profile.weightUnit.label}"
                fun change(value: Double) = when {
                    value > 0.0 -> "${displayKg(value)} loss"
                    value < 0.0 -> "${displayKg(-value)} gain"
                    else -> "No expected change"
                }
                MetricRow("Per week", change(projection.weeklyChangeKg))
                MetricRow("Weekly range", "${displayKg(projection.weeklyLowKg ?: 0.0)} to ${displayKg(projection.weeklyHighKg ?: 0.0)}")
                MetricRow("Four weeks", change(projection.fourWeekChangeKg ?: 0.0))
                MetricRow("Four-week range", "${displayKg(projection.fourWeekLowKg ?: 0.0)} to ${displayKg(projection.fourWeekHighKg ?: 0.0)}")
                Text("Range values use positive numbers for loss and negative numbers for gain.", style = MaterialTheme.typography.bodySmall)
                when {
                    projection.targetReached -> Text("Target weight reached.", color = MaterialTheme.colorScheme.primary)
                    projection.targetDateEarly != null -> Text(
                        "Estimated target range: ${projection.targetDateEarly}" +
                            (projection.targetDateLate?.let { " to $it" } ?: " or later")
                    )
                }
            }
        }
    }
}

@Composable
private fun WeightRecordingsDialog(
    dashboard: HealthDashboard,
    onDismiss: () -> Unit,
    onDelete: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Weight recordings") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                dashboard.selectedDateWeights.forEach { entry ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${formatDecimal(dashboard.profile.weightUnit.fromKg(entry.weightKg))} ${dashboard.profile.weightUnit.label}")
                            Text(
                                "${Instant.ofEpochMilli(entry.recordedAtEpochMs).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))} · " +
                                    entry.source.name.lowercase().replace('_', ' '),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        if (entry.source == WeightSource.MANUAL) {
                            TextButton(onClick = { onDelete(entry.entryId) }) { Text("Delete") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun WalkingGuidanceCard(dashboard: HealthDashboard) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Close today’s gap", style = MaterialTheme.typography.titleLarge)
            when {
                dashboard.selectedDate != LocalDate.now() -> Text("Walking guidance is available only for today.")
                dashboard.remainingDeficitGap == null -> Text("Burn and intake data are needed before estimating a walk.")
                dashboard.remainingDeficitGap <= 0.0 -> Text("Your desired deficit is already met. No additional walk suggested.")
                dashboard.walkingEstimate == null -> Text("Add a body weight or at least three complete walking sessions to estimate a walk.")
                else -> with(dashboard.walkingEstimate) {
                    Text("Walk ${formatDecimal(distanceKm)} km", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    MetricRow("Time", "${minutes.roundToInt()} min")
                    MetricRow("Steps", formatInteger(steps))
                    MetricRow("Estimated burn", "${formatCalories(estimatedBurn)} kcal")
                    if (capped && remainingGap > 0.0) Text("Capped at 90 minutes; about ${formatCalories(remainingGap)} kcal would remain.")
                    Text("Estimate source: ${source.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun HealthTrendChart(points: List<HealthTrendPoint>, weightUnit: WeightUnit) {
    val validDeficits = points.mapNotNull(HealthTrendPoint::deficitCalories)
    val validWeights = points.mapNotNull(HealthTrendPoint::weightKg).map(weightUnit::fromKg)
    if (points.size < 2 || validDeficits.isEmpty() && validWeights.isEmpty()) {
        Text("More history is needed to draw a trend.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val gridColor = MaterialTheme.colorScheme.onSurface
    val deficitColor = MaterialTheme.colorScheme.primary
    val surplusColor = MaterialTheme.colorScheme.error
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (validDeficits.isNotEmpty()) {
            val maxDeficit = validDeficits.maxOfOrNull { abs(it) }?.coerceAtLeast(1.0) ?: 1.0
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Daily deficit / surplus", fontWeight = FontWeight.Bold)
                Text("±${formatCalories(maxDeficit)} kcal", style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth().height(112.dp)) {
                Column(
                    Modifier.width(48.dp).height(112.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    listOf(maxDeficit, 0.0, -maxDeficit).forEach {
                        Text(formatCalories(it), style = MaterialTheme.typography.labelSmall)
                    }
                }
                Canvas(Modifier.weight(1f).height(112.dp).padding(start = 6.dp)) {
                    val middle = size.height / 2f
                    repeat(3) { tick ->
                        val y = size.height * tick / 2f
                        drawLine(gridColor.copy(alpha = if (tick == 1) 0.35f else 0.12f),
                            androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                    }
                    val slot = size.width / points.size.coerceAtLeast(1)
                    val barWidth = (slot * 0.58f).coerceAtLeast(2.dp.toPx())
                    points.forEachIndexed { index, point ->
                        point.deficitCalories?.let { value ->
                            val height = (abs(value) / maxDeficit * middle * 0.88).toFloat().coerceAtLeast(1.dp.toPx())
                            val top = if (value >= 0.0) middle - height else middle
                            drawRect(
                                color = if (value >= 0.0) deficitColor else surplusColor,
                                topLeft = androidx.compose.ui.geometry.Offset(index * slot + (slot - barWidth) / 2f, top),
                                size = androidx.compose.ui.geometry.Size(barWidth, height)
                            )
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Deficit", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                Text("Surplus", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (validWeights.isNotEmpty()) {
            val minWeight = validWeights.minOrNull() ?: 0.0
            val maxWeight = validWeights.maxOrNull() ?: minWeight
            val range = (maxWeight - minWeight).coerceAtLeast(0.5)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Smoothed weight", fontWeight = FontWeight.Bold)
                Text("${formatDecimal(minWeight)}–${formatDecimal(maxWeight)} ${weightUnit.label}", style = MaterialTheme.typography.bodySmall)
            }
            val axisMin = minWeight - range * 0.125
            val axisMax = maxWeight + range * 0.125
            Row(Modifier.fillMaxWidth().height(112.dp)) {
                Column(
                    Modifier.width(48.dp).height(112.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    Text(formatDecimal(axisMax), style = MaterialTheme.typography.labelSmall)
                    Text(formatDecimal(axisMin), style = MaterialTheme.typography.labelSmall)
                }
                Canvas(Modifier.weight(1f).height(112.dp).padding(start = 6.dp)) {
                    val xStep = size.width / (points.size - 1).coerceAtLeast(1)
                    repeat(2) { line ->
                        val y = size.height * line
                        drawLine(gridColor.copy(alpha = 0.12f),
                            androidx.compose.ui.geometry.Offset(0f, y),
                            androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                    }
                    var previous: androidx.compose.ui.geometry.Offset? = null
                    points.forEachIndexed { index, point ->
                        point.weightKg?.let(weightUnit::fromKg)?.let { value ->
                            val current = androidx.compose.ui.geometry.Offset(
                                index * xStep,
                                size.height - ((value - axisMin) / (axisMax - axisMin) * size.height).toFloat()
                            )
                            previous?.let { drawLine(Color(0xFF70D680), it, current, 3.dp.toPx()) }
                            drawCircle(Color(0xFF70D680), 2.5.dp.toPx(), current)
                            previous = current
                        } ?: run { previous = null }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(points.first().date.format(DateTimeFormatter.ofPattern("dd MMM")), style = MaterialTheme.typography.labelSmall)
            Text(points.last().date.format(DateTimeFormatter.ofPattern("dd MMM")), style = MaterialTheme.typography.labelSmall)
        }
    }
}

internal enum class SettingsPage(val route: String, val title: String, val summary: String) {
    GOALS("settings/goals", "Goals & budget", "Calories, deficit, macros, currency, and weight target"),
    PLANNER("settings/planner", "Planner", "Choose included, fixed, food, and drink products"),
    HEALTH_CONNECT("settings/health-connect", "Health Connect", "Permissions, refresh, bootstrap, and nutrition sync"),
    PRODUCT_JSON("settings/product-json", "Product JSON", "Copy the schema for fast AI-assisted product entry"),
    BACKUP("settings/backup", "Backup & restore", "Encrypted local export and device migration"),
    PRIVACY("settings/privacy", "Privacy & offline", "Offline guarantees and local data handling"),
    ABOUT("settings/about", "About", "Version and database information")
}

@Composable
internal fun SettingsScreen(
    selectedDate: LocalDate,
    viewModel: SettingsViewModel,
    page: SettingsPage?,
    onNavigate: (SettingsPage) -> Unit,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    onGrantCorePermissions: () -> Unit,
    onGrantNutritionPermission: () -> Unit,
    onGrantNutritionWritePermission: () -> Unit,
    onGrantWeightPermission: () -> Unit
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
        if (page == null) {
            item { Text("Settings", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            SettingsPage.entries.forEach { destination ->
                item(destination.route) {
                    SettingsMenuButton(destination.title, destination.summary) { onNavigate(destination) }
                }
            }
        } else {
            item { SettingsPageHeader(page.title, onBack) }
        }
        when (page) {
            null -> Unit
            SettingsPage.GOALS -> item {
                GoalsSettingsCard(state.goals, state.healthProfile, viewModel::saveGoalsAndProfile)
            }
            SettingsPage.PLANNER -> Unit
            SettingsPage.HEALTH_CONNECT -> item {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text(if (state.weightPermissionGranted) "Optional weight permission granted" else "Optional weight permission not granted")
                    OutlinedButton(
                        onClick = onGrantWeightPermission,
                        enabled = state.healthAvailable && !state.weightPermissionGranted,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.weightPermissionGranted) "Weight import enabled" else "Enable weight import") }
                    state.healthHistoryStatus?.let { Text("28-day history: $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
            SettingsPage.PRODUCT_JSON -> item {
            val clipboard = LocalClipboardManager.current
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Copy this schema when asking an AI tool to estimate nutrition from a photo, then import its response in the manual product editor.")
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(PRODUCT_JSON_TEMPLATE))
                            onMessage("Product JSON schema copied.")
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Copy product JSON schema") }
                }
            }
            SettingsPage.BACKUP -> item {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            SettingsPage.PRIVACY -> item {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("The packaged app has no Internet or network-state permission. Barcode recognition runs from the bundled on-device model.")
                    Text("Food data and reports stay in the app's local Room database. Android cloud backup is disabled.")
                    Text("Chinese, English, and Japanese label OCR uses bundled on-device models.")
                    Text("Nutrition-label OCR remains assistive; review recognized values before saving.")
                    Text("The Today summary widget shows local progress and opens the same offline scanner directly.")
                }
            }
            SettingsPage.ABOUT -> item {
                Column(Modifier.padding(18.dp)) {
                    Text("DailyCutReport ${packageInfo.versionName}", fontWeight = FontWeight.Bold)
                    Text("Database schema 9 · Build ${packageInfo.longVersionCode}")
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
private fun SettingsMenuButton(title: String, summary: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp)) {
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(summary, style = MaterialTheme.typography.bodySmall)
        }
        Text("›", style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun SettingsPageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Back") }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun PlannerSettingsScreen(
    viewModel: PlannerSettingsViewModel,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.events.collect(onMessage) }
    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { SettingsPageHeader(SettingsPage.PLANNER.title, onBack) }
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                label = { Text("Search planner products") },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "${state.visibleProducts.size} product${if (state.visibleProducts.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        items(state.visibleProducts, key = ProductEntity::productId) { product ->
            val itemType = PlannerItemType.entries.firstOrNull { it.name == product.plannerItemType }
                ?: PlannerItemType.FOOD
            val fixedText = state.amountDrafts[product.productId] ?: product.fixedPurchaseUnits.toString()
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(product.name, fontWeight = FontWeight.Bold)
                    product.brand.takeIf(String::isNotBlank)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "One purchase unit = ${product.purchaseUnitServings.toDisplay()} serving(s)",
                        style = MaterialTheme.typography.bodySmall
                    )
                    ToggleRow("Include in planning", product.includeInPlanner) {
                        viewModel.setIncluded(product, it)
                    }
                    Text("Item type", style = MaterialTheme.typography.labelLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = itemType == PlannerItemType.FOOD,
                            onClick = { viewModel.setItemType(product, PlannerItemType.FOOD) }
                        )
                        Text("Food", Modifier.weight(1f))
                        RadioButton(
                            selected = itemType == PlannerItemType.DRINK,
                            onClick = { viewModel.setItemType(product, PlannerItemType.DRINK) }
                        )
                        Text("Drink")
                    }
                    ToggleRow(
                        "Fixed in strict plans",
                        product.alwaysIncludeInPlanner,
                        enabled = product.includeInPlanner
                    ) { viewModel.setFixed(product, it) }
                    if (product.alwaysIncludeInPlanner) {
                        DecimalField("Fixed purchase units (1–6)", fixedText) {
                            viewModel.setFixedUnitsText(product, it)
                        }
                        state.errors[product.productId]?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun GoalsSettingsCard(
    goals: UserGoals,
    profile: HealthProfile,
    onSave: (UserGoals, HealthProfile) -> Unit
) {
    var mode by remember(goals) { mutableStateOf(goals.mode) }
    var calories by remember(goals) { mutableStateOf(goals.calories.toInput()) }
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
    var weightUnit by remember(profile) { mutableStateOf(profile.weightUnit) }
    var targetWeight by remember(profile) {
        mutableStateOf(profile.targetWeightKg?.let(profile.weightUnit::fromKg)?.let(::formatDecimal).orEmpty())
    }
    val numbers = listOf(calories, deficit, protein, sodium, carbs, fat, sugar, fiber, saturated)
    val budgetMicros = runCatching { parseMoneyMicros(budget) }.getOrNull()
    val candidate = runCatching {
        UserGoals(
            mode = mode,
            calories = calories.numberOrNull() ?: error("Enter calories"),
            expectedBurnCalories = goals.expectedBurnCalories,
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
    val targetWeightValue = targetWeight.numberOrNull()
    val targetWeightValid = targetWeight.isBlank() || targetWeightValue?.let { it > 0.0 } == true
    val profileCandidate = if (targetWeightValid) {
        HealthProfile(weightUnit, targetWeightValue?.let(weightUnit::toKg))
    } else null
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
                DecimalField("Desired deficit kcal", deficit) { deficit = it }
                Text(
                    "Daily allowance is calculated automatically from Health Connect projected burn minus this deficit.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            DecimalField("Protein minimum g", protein) { protein = it }
            DecimalField("Sodium maximum mg", sodium) { sodium = it }
            DecimalField("Carbs maximum g", carbs) { carbs = it }
            DecimalField("Fat maximum g", fat) { fat = it }
            DecimalField("Sugar maximum g", sugar) { sugar = it }
            DecimalField("Fiber minimum g", fiber) { fiber = it }
            DecimalField("Saturated fat maximum g", saturated) { saturated = it }
            Text("Weight goal", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeightUnit.entries.forEach { unit ->
                    RadioButton(
                        selected = weightUnit == unit,
                        onClick = {
                            if (weightUnit != unit) {
                                targetWeight.numberOrNull()?.let { value ->
                                    targetWeight = formatDecimal(unit.fromKg(weightUnit.toKg(value)))
                                }
                                weightUnit = unit
                            }
                        }
                    )
                    Text(unit.label, Modifier.padding(end = 12.dp))
                }
            }
            DecimalField("Target weight (${weightUnit.label}, optional)", targetWeight) { targetWeight = it }
            OutlinedTextField(
                value = currency,
                onValueChange = { currency = it.take(3).uppercase() },
                label = { Text("Currency code") },
                modifier = Modifier.fillMaxWidth()
            )
            DecimalField("Daily budget", budget) { budget = it }
            Text("Targets allow 10% planning tolerance; plans up to 10% over budget rank lower.", style = MaterialTheme.typography.bodySmall)
            Button(
                enabled = candidate != null && profileCandidate != null && numbers.all { it.isNotBlank() },
                onClick = { candidate?.let { goalsValue -> profileCandidate?.let { onSave(goalsValue, it) } } },
                modifier = Modifier.fillMaxWidth()
            ) {
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

@Composable internal fun MetricRow(label: String, value: String) {
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
    onConfirm: (LoggedQuantity, Long?, Boolean) -> Unit
) {
    var quantity by remember(product.product.productId) {
        mutableStateOf(QuantityInputState.forProduct(product.product))
    }
    var actualPaid by remember { mutableStateOf("") }
    var excludeCostFromBudget by remember { mutableStateOf(false) }
    val parsedQuantity = quantity.servings
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
                QuantityInputFields(
                    state = quantity,
                    onChange = { unit, value -> quantity = quantity.edit(unit, value) },
                    onPurchaseUnit = { quantity = quantity.withServings(product.product.purchaseUnitServings) }
                )
                parsedQuantity?.let { servings ->
                    Text(
                        "${formatCalories(product.product.calories * servings)} kcal · ${formatDecimal(product.product.proteinG * servings)} g protein",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                estimated?.let { Text("Catalog estimate: ${formatMoney(it, currencyCode)}", style = MaterialTheme.typography.bodySmall) }
                DecimalField("Actual paid total (optional)", actualPaid) { actualPaid = it }
                Text("Leave blank for the catalog estimate. Enter 0 for a free item.", style = MaterialTheme.typography.bodySmall)
                ToggleRow("Ignore this price in budget calculations", excludeCostFromBudget) { excludeCostFromBudget = it }
            }
        },
        confirmButton = { Button(enabled = quantity.valid && priceValid, onClick = {
            val servings = parsedQuantity
            val entered = quantity.enteredAmount
            if (servings != null && entered != null) {
                onConfirm(LoggedQuantity(servings, quantity.activeUnit, entered, quantity.spec), parsedPaid, excludeCostFromBudget)
            }
        }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ProductEditorDialog(
    draft: ProductEditorDraft,
    currencyCode: String,
    onDraftChange: (ProductEditorDraft) -> Unit,
    onScanNutrition: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (ProductEntity, List<ProductExtraNutrientEntity>, Double) -> Unit
) {
    val product = draft.existing?.product
    var showJsonImport by remember { mutableStateOf(false) }
    var quantityModeExpanded by remember { mutableStateOf(false) }
    var jsonInput by remember { mutableStateOf("") }
    var jsonError by remember { mutableStateOf<String?>(null) }
    val nutrientInputs = listOf(draft.calories, draft.protein, draft.sodium, draft.carbs, draft.fat, draft.sugar, draft.fiber, draft.saturatedFat)
    val parsedPrice = runCatching { parseMoneyMicros(draft.purchasePrice) }.getOrNull()
    val parsedPurchaseServings = draft.purchaseServings.numberOrNull()?.takeIf { it > 0.0 }
    val parsedMeasure = draft.measurePerServing.numberOrNull()?.takeIf { it > 0.0 }
    val parsedFixedUnits = draft.fixedPurchaseUnits.toIntOrNull()?.takeIf { it in 1..6 }
    val quantitySpec = ProductQuantitySpec(draft.quantityMode, parsedMeasure)
    val purchaseInput = QuantityInputState(
        servingsText = draft.purchaseServings,
        measureText = draft.purchaseMeasure,
        activeUnit = quantitySpec.preferredOrFallback(draft.preferredLogUnit),
        spec = quantitySpec
    )
    val valid = draft.name.isNotBlank() && nutrientInputs.all { it.isBlank() || it.numberOrNull() != null } &&
        (draft.purchasePrice.isBlank() || parsedPrice != null) && parsedPurchaseServings != null &&
        (!draft.quantityMode.measureAvailable || parsedMeasure != null) &&
        parsedFixedUnits != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (product == null) "New product" else "Edit product") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(draft.barcode, { onDraftChange(draft.copy(barcode = it)) }, label = { Text("Barcode (optional)") })
                OutlinedTextField(draft.name, { onDraftChange(draft.copy(name = it)) }, label = { Text("Product name") })
                OutlinedTextField(draft.brand, { onDraftChange(draft.copy(brand = it)) }, label = { Text("Brand") })
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { quantityModeExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Quantity basis · ${draft.quantityMode.editorLabel}")
                    }
                    DropdownMenu(expanded = quantityModeExpanded, onDismissRequest = { quantityModeExpanded = false }) {
                        QuantityMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.editorLabel) },
                                onClick = {
                                    val measure = if (mode.measureAvailable && draft.measurePerServing.isBlank()) "100" else draft.measurePerServing
                                    val preferred = ProductQuantitySpec(mode, measure.numberOrNull())
                                        .preferredOrFallback(draft.preferredLogUnit)
                                    val spec = ProductQuantitySpec(mode, measure.numberOrNull())
                                    val servingLabel = when (mode) {
                                        QuantityMode.WEIGHT_ONLY -> "${measure.ifBlank { "100" }} g"
                                        QuantityMode.VOLUME_ONLY -> "${measure.ifBlank { "100" }} ml"
                                        else -> draft.servingLabel
                                    }
                                    onDraftChange(draft.copy(
                                        quantityMode = mode,
                                        measurePerServing = measure,
                                        servingLabel = servingLabel,
                                        preferredLogUnit = preferred,
                                        purchaseMeasure = spec.measureUnit
                                            ?.let { spec.amountFor(draft.purchaseServings.numberOrNull() ?: 1.0, it)?.toDisplay() }.orEmpty()
                                    ))
                                    quantityModeExpanded = false
                                }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    draft.servingLabel,
                    { onDraftChange(draft.copy(servingLabel = it)) },
                    enabled = draft.quantityMode.servingAvailable,
                    label = { Text("Serving label") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (draft.quantityMode.measureAvailable) {
                    DecimalField(
                        if (draft.quantityMode.servingAvailable) "${draft.quantityMode.measureUnit?.shortLabel} per serving"
                        else "Nutrition basis amount (${draft.quantityMode.measureUnit?.shortLabel})",
                        draft.measurePerServing
                    ) { value ->
                        val measure = value.numberOrNull()?.takeIf { it > 0.0 }
                        val servingLabel = when (draft.quantityMode) {
                            QuantityMode.WEIGHT_ONLY -> value.numberOrNull()?.takeIf { it > 0.0 }?.let { "${it.toDisplay()} g" } ?: draft.servingLabel
                            QuantityMode.VOLUME_ONLY -> value.numberOrNull()?.takeIf { it > 0.0 }?.let { "${it.toDisplay()} ml" } ?: draft.servingLabel
                            else -> draft.servingLabel
                        }
                        onDraftChange(draft.copy(
                            measurePerServing = value,
                            servingLabel = servingLabel,
                            purchaseMeasure = measure?.let { amount ->
                                draft.purchaseServings.numberOrNull()?.let { servings -> (amount * servings).toDisplay() }
                            } ?: draft.purchaseMeasure
                        ))
                    }
                }
                if (product == null) {
                    OutlinedButton(onClick = { showJsonImport = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("Import product JSON")
                    }
                }
                OutlinedButton(onClick = onScanNutrition, modifier = Modifier.fillMaxWidth()) { Text("Scan nutrition label") }
                draft.ocrDraft?.let { Text("OCR values: ${it.basis.label}. Review before saving.", style = MaterialTheme.typography.bodySmall) }
                DecimalField("Calories", draft.calories) { onDraftChange(draft.copy(calories = it)) }
                DecimalField("Protein g", draft.protein) { onDraftChange(draft.copy(protein = it)) }
                DecimalField("Sodium mg", draft.sodium) { onDraftChange(draft.copy(sodium = it)) }
                DecimalField("Carbs g", draft.carbs) { onDraftChange(draft.copy(carbs = it)) }
                DecimalField("Fat g", draft.fat) { onDraftChange(draft.copy(fat = it)) }
                DecimalField("Sugar g", draft.sugar) { onDraftChange(draft.copy(sugar = it)) }
                DecimalField("Fiber g", draft.fiber) { onDraftChange(draft.copy(fiber = it)) }
                DecimalField("Saturated fat g", draft.saturatedFat) { onDraftChange(draft.copy(saturatedFat = it)) }
                DecimalField("Purchase price ($currencyCode, optional)", draft.purchasePrice) { onDraftChange(draft.copy(purchasePrice = it)) }
                Text("One purchase unit", style = MaterialTheme.typography.labelLarge)
                QuantityInputFields(
                    state = purchaseInput,
                    onChange = { unit, value ->
                        val updated = purchaseInput.edit(unit, value)
                        onDraftChange(draft.copy(
                            purchaseServings = updated.servingsText,
                            purchaseMeasure = updated.measureText
                        ))
                    },
                    onPurchaseUnit = null
                )
                ToggleRow("Favorite", draft.favorite) { onDraftChange(draft.copy(favorite = it)) }
                ToggleRow("Include in daily planning", draft.includeInPlanner) {
                    onDraftChange(draft.copy(includeInPlanner = it, alwaysIncludeInPlanner = if (it) draft.alwaysIncludeInPlanner else false))
                }
                Text("Planner item type", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = draft.plannerItemType == PlannerItemType.FOOD, onClick = { onDraftChange(draft.copy(plannerItemType = PlannerItemType.FOOD)) })
                    Text("Solid food", Modifier.weight(1f))
                    RadioButton(selected = draft.plannerItemType == PlannerItemType.DRINK, onClick = { onDraftChange(draft.copy(plannerItemType = PlannerItemType.DRINK)) })
                    Text("Drink")
                }
                ToggleRow("Always include in plans", draft.alwaysIncludeInPlanner, enabled = draft.includeInPlanner) {
                    onDraftChange(draft.copy(alwaysIncludeInPlanner = it))
                }
                if (draft.alwaysIncludeInPlanner) {
                    DecimalField("Fixed purchase units (1–6)", draft.fixedPurchaseUnits) {
                        onDraftChange(draft.copy(fixedPurchaseUnits = it))
                    }
                }
                OutlinedTextField(draft.extras, { onDraftChange(draft.copy(extras = it)) }, label = { Text("Extra nutrients: Name=12 unit") })
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val productId = product?.productId ?: UUID.randomUUID().toString()
                val entity = ProductEntity(
                    productId = productId,
                    barcode = draft.barcode.trim().ifBlank { null },
                    name = draft.name.trim(),
                    brand = draft.brand.trim(),
                    servingLabel = draft.servingLabel.ifBlank { "1 serving" },
                    quantityMode = draft.quantityMode.name,
                    measurePerServing = parsedMeasure,
                    preferredLogUnit = ProductQuantitySpec(draft.quantityMode, parsedMeasure)
                        .preferredOrFallback(draft.preferredLogUnit).name,
                    calories = draft.calories.number(),
                    proteinG = draft.protein.number(),
                    sodiumMg = draft.sodium.number(),
                    carbsG = draft.carbs.number(),
                    fatG = draft.fat.number(),
                    sugarG = draft.sugar.number(),
                    fiberG = draft.fiber.number(),
                    saturatedFatG = draft.saturatedFat.number(),
                    purchasePriceMicros = parsedPrice,
                    purchaseUnitServings = parsedPurchaseServings ?: 1.0,
                    includeInPlanner = draft.includeInPlanner,
                    plannerItemType = draft.plannerItemType.name,
                    alwaysIncludeInPlanner = draft.alwaysIncludeInPlanner,
                    fixedPurchaseUnits = parsedFixedUnits ?: 1,
                    favorite = draft.favorite,
                    notes = product?.notes.orEmpty(),
                    createdAt = product?.createdAt ?: System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                onSave(entity, parseExtras(productId, draft.extras), 1.0)
            }) { Text(if (draft.saveTarget == ProductSaveTarget.CATALOG_ONLY) "Save" else "Save and continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (showJsonImport) {
        AlertDialog(
            onDismissRequest = { showJsonImport = false },
            title = { Text("Import product JSON") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste JSON using the schema available in Settings. Existing draft fields are updated only after validation.")
                    OutlinedTextField(
                        value = jsonInput,
                        onValueChange = { jsonInput = it; jsonError = null },
                        label = { Text("Product JSON") },
                        minLines = 8,
                        maxLines = 14,
                        modifier = Modifier.fillMaxWidth()
                    )
                    jsonError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                Button(
                    enabled = jsonInput.isNotBlank(),
                    onClick = {
                        runCatching { draft.withProductJson(jsonInput) }
                            .onSuccess {
                                onDraftChange(it)
                                showJsonImport = false
                                jsonError = null
                            }
                            .onFailure { jsonError = it.message ?: "Invalid product JSON." }
                    }
                ) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { showJsonImport = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun FoodLogEditDialog(log: FoodLogSnapshot, currencyCode: String, onDismiss: () -> Unit, onSave: (FoodQuantityEdit) -> Unit) {
    var quantity by remember(log.id) { mutableStateOf(QuantityInputState.forLog(log)) }
    var actualPaid by remember { mutableStateOf(log.actualPaidTotalMicros?.toMoneyInput().orEmpty()) }
    var excludeCostFromBudget by remember { mutableStateOf(log.excludeCostFromBudget) }
    val parsedQuantity = quantity.servings
    val parsedPaid = runCatching { parseMoneyMicros(actualPaid) }.getOrNull()
    val priceValid = actualPaid.isBlank() || parsedPaid != null
    val estimate = log.catalogCostPerServingMicros?.let { cost -> parsedQuantity?.let { (cost * it).toLong() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit amount") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(log.productName, fontWeight = FontWeight.Bold)
                Text(log.servingLabel, style = MaterialTheme.typography.bodySmall)
                QuantityInputFields(
                    state = quantity,
                    onChange = { unit, value -> quantity = quantity.edit(unit, value) },
                    onPurchaseUnit = null
                )
                parsedQuantity?.let { servings ->
                    Text(
                        "${formatCalories(log.caloriesPerServing * servings)} kcal · ${formatDecimal(log.proteinGPerServing * servings)} g protein",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                estimate?.let { Text("Catalog estimate: ${formatMoney(it, currencyCode)}", style = MaterialTheme.typography.bodySmall) }
                DecimalField("Actual paid total (optional)", actualPaid) { actualPaid = it }
                Text("Blank uses the catalog estimate; 0 records a free item.", style = MaterialTheme.typography.bodySmall)
                ToggleRow("Ignore this price in budget calculations", excludeCostFromBudget) { excludeCostFromBudget = it }
            }
        },
        confirmButton = {
            Button(enabled = quantity.valid && priceValid, onClick = {
                parsedQuantity?.let {
                    onSave(log.quantityEdit(
                        quantity = it,
                        enteredUnit = quantity.activeUnit,
                        enteredAmount = requireNotNull(quantity.enteredAmount),
                        actualPaidTotalMicros = parsedPaid,
                        excludeCostFromBudget = excludeCostFromBudget
                    ))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
internal fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun DecimalField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun QuantityInputFields(
    state: QuantityInputState,
    onChange: (QuantityUnit, String) -> Unit,
    onPurchaseUnit: (() -> Unit)?
) {
    val measureUnit = state.spec.measureUnit
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.servingsText,
            onValueChange = { onChange(QuantityUnit.SERVINGS, it) },
            enabled = state.spec.servingAvailable,
            label = { Text("Servings") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
            supportingText = if (!state.spec.servingAvailable) ({ Text("Unavailable") }) else null
        )
        OutlinedTextField(
            value = state.measureText,
            onValueChange = { value -> measureUnit?.let { onChange(it, value) } },
            enabled = state.spec.measureAvailable,
            label = { Text(measureUnit?.shortLabel ?: "g/ml") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            modifier = Modifier.weight(1f),
            supportingText = if (!state.spec.measureAvailable) ({ Text("Not configured") }) else null
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("Logging in ${state.activeUnit.shortLabel}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        onPurchaseUnit?.let { TextButton(onClick = it) { Text("1 purchase unit") } }
    }
}

private fun String.numberOrNull(): Double? = trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
private fun String.number(): Double = numberOrNull() ?: 0.0
private fun Double.toInput(): String = if (this == 0.0) "" else toString()
private fun Double.toReviewInput(): String = if (this == 0.0) "0" else toString()
internal fun Double.toDisplay(): String = formatDecimal(this)
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

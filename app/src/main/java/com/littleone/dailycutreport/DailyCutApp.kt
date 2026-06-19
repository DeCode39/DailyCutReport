package com.littleone.dailycutreport

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

private enum class Destination(val route: String, val label: String) {
    TODAY("today", "Today"), FOODS("foods", "Foods"), SETTINGS("settings", "Settings")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyCutApp(
    dateViewModel: ReportDateViewModel,
    todayViewModel: TodayViewModel,
    foodsViewModel: FoodsViewModel,
    settingsViewModel: SettingsViewModel
) {
    MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF235F82))) {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route ?: Destination.TODAY.route
        val selectedDate by dateViewModel.selectedDate.collectAsStateWithLifecycle()
        val healthPermissionLauncher = rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) {
            settingsViewModel.refresh()
            todayViewModel.refreshHealth(selectedDate)
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Daily Cut Report", fontWeight = FontWeight.Bold) }) },
            bottomBar = {
                if (route != "scanner") {
                    NavigationBar {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = route == destination.route,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Text(destination.label.take(1)) },
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = Destination.TODAY.route,
                modifier = Modifier.padding(padding)
            ) {
                composable(Destination.TODAY.route) {
                    TodayScreen(
                        selectedDate,
                        dateViewModel,
                        todayViewModel,
                        onGrantPermissions = { healthPermissionLauncher.launch(HealthConnectManager.PERMISSIONS) }
                    )
                }
                composable(Destination.FOODS.route) {
                    FoodsScreen(
                        selectedDate,
                        dateViewModel,
                        foodsViewModel,
                        onScan = { navController.navigate("scanner") }
                    )
                }
                composable(Destination.SETTINGS.route) {
                    SettingsScreen(settingsViewModel) {
                        healthPermissionLauncher.launch(HealthConnectManager.PERMISSIONS)
                    }
                }
                composable("scanner") {
                    BarcodeScannerScreen(
                        onResult = { result ->
                            navController.popBackStack()
                            if (result is ScannerResult.Found) foodsViewModel.handleBarcode(result.barcode)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DateHeader(date: LocalDate, dateViewModel: ReportDateViewModel) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = dateViewModel::previous) { Text("‹") }
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
            OutlinedButton(onClick = dateViewModel::next, enabled = date < LocalDate.now()) { Text("›") }
        }
    }
}

@Composable
private fun TodayScreen(
    selectedDate: LocalDate,
    dateViewModel: ReportDateViewModel,
    viewModel: TodayViewModel,
    onGrantPermissions: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showOverrides by remember { mutableStateOf(false) }
    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            val saved = viewModel.writeReport(uri)
            Toast.makeText(context, if (saved) "Report saved" else "Could not save report", Toast.LENGTH_LONG).show()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground(selectedDate) }
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.clearMessage() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { DateHeader(selectedDate, dateViewModel) }
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.report.verdict.label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    val deficit = state.report.deficitCalories
                    Text(
                        if (deficit >= 0) "−${abs(deficit).roundToInt()} kcal" else "+${abs(deficit).roundToInt()} kcal",
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold
                    )
                    MetricRow("Burn", "${state.report.finalBurnCalories.roundToInt()} kcal")
                    MetricRow("Food", "${state.report.finalFoodCalories.roundToInt()} kcal")
                    MetricRow("Protein", "${state.report.finalProteinG.roundToInt()} g")
                    MetricRow("Sodium", "${state.report.finalSodiumMg.roundToInt()} mg")
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
                    Button(
                        onClick = { viewModel.refreshHealth(selectedDate) },
                        enabled = !state.isRefreshing,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.isRefreshing) "Refreshing…" else "Refresh Health Connect") }
                    TextButton(onClick = onGrantPermissions, modifier = Modifier.fillMaxWidth()) { Text("Grant permissions") }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showOverrides = true }, modifier = Modifier.weight(1f)) { Text("Overrides") }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            createDocumentLauncher.launch("DailyCutReport_${state.report.date}.png")
                        } else scope.launch {
                            val uri = viewModel.saveReport()
                            Toast.makeText(context, if (uri == null) "Could not save report" else "Report saved", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text("Save PNG") }
            }
        }
        item {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
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
            ) { Text("Share report") }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    if (showOverrides) {
        OverridesDialog(state.report.manual, onDismiss = { showOverrides = false }) {
            viewModel.saveOverrides(selectedDate, it)
            showOverrides = false
        }
    }
}

@Composable
private fun FoodsScreen(
    selectedDate: LocalDate,
    dateViewModel: ReportDateViewModel,
    viewModel: FoodsViewModel,
    onScan: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var manualCode by remember { mutableStateOf("") }
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.clearMessage() }
    }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = onScan) { Text("Scan") }
    }) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { DateHeader(selectedDate, dateViewModel) }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Daily nutrition", style = MaterialTheme.typography.titleLarge)
                        MetricRow("Entries", state.nutrition.entries.toString())
                        MetricRow("Calories", "${state.nutrition.calories.roundToInt()} kcal")
                        MetricRow("Protein", "${state.nutrition.proteinG.roundToInt()} g")
                        MetricRow("Sodium", "${state.nutrition.sodiumMg.roundToInt()} mg")
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = manualCode,
                    onValueChange = { manualCode = it },
                    label = { Text("Barcode / product code") },
                    trailingIcon = { TextButton(onClick = { viewModel.handleBarcode(manualCode) }) { Text("Use") } },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    label = { Text("Search saved products") },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = viewModel::createProduct) { Text("Create product manually") }
            }
            items(state.products, key = { it.barcode }) { product ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(product.name, fontWeight = FontWeight.Bold)
                            Text(listOf(product.brand, product.barcode).filter { it.isNotBlank() }.joinToString(" · "))
                        }
                        TextButton(onClick = { viewModel.editProduct(product) }) { Text("Edit") }
                        Button(onClick = { viewModel.selectProduct(product) }) { Text("Add") }
                    }
                }
            }
            item { Text("Food log", style = MaterialTheme.typography.titleLarge) }
            if (state.logs.isEmpty()) item { Text("No food entries for this date.") }
            items(state.logs, key = { it.id }) { log ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${log.quantity} × ${log.productName}", fontWeight = FontWeight.Bold)
                        Text("${log.calories.roundToInt()} kcal · ${log.proteinG.roundToInt()} g protein · ${log.sodiumMg.roundToInt()} mg sodium")
                        Row {
                            TextButton(onClick = { viewModel.edit(log) }) { Text("Edit") }
                            TextButton(onClick = { viewModel.delete(log.id) }) { Text("Delete") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    state.pendingProduct?.let { product ->
        QuantityDialog(product, onDismiss = viewModel::cancelDialogs, onConfirm = viewModel::confirmAdd)
    }
    if (state.editorBarcode != null) {
        ProductEditorDialog(
            initialBarcode = state.editorBarcode,
            existing = state.editorProduct,
            addAfterSave = state.editorAddsAfterSave,
            onDismiss = viewModel::cancelDialogs,
            onSave = viewModel::saveProduct
        )
    }
    state.editingLog?.let { log ->
        FoodLogEditDialog(log, onDismiss = viewModel::cancelDialogs, onSave = viewModel::saveLogEdit)
    }
}

@Composable
private fun SettingsScreen(viewModel: SettingsViewModel, onGrantPermissions: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val packageInfo = remember {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Health Connect", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.healthAvailable) "Available" else "Unavailable")
                    Text(if (state.permissionsGranted) "Permissions granted" else "Permissions required")
                    Button(onClick = onGrantPermissions, modifier = Modifier.fillMaxWidth()) { Text("Grant / update permissions") }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Strictly offline", style = MaterialTheme.typography.titleLarge)
                    Text("The packaged app has no Internet or network-state permission. Barcode recognition runs from the bundled on-device model.")
                    Text("Food data and reports stay in the app's local Room database.")
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text("DailyCutReport ${packageInfo.versionName}", fontWeight = FontWeight.Bold)
                    Text("Database schema 2 · Build ${packageInfo.longVersionCode}")
                }
            }
        }
    }
}

@Composable private fun MetricRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun OverridesDialog(initial: ManualOverrides, onDismiss: () -> Unit, onSave: (ManualOverrides) -> Unit) {
    var food by remember { mutableStateOf(initial.foodCalories?.toString().orEmpty()) }
    var protein by remember { mutableStateOf(initial.proteinG?.toString().orEmpty()) }
    var sodium by remember { mutableStateOf(initial.sodiumMg?.toString().orEmpty()) }
    var burn by remember { mutableStateOf(initial.burnCalories?.toString().orEmpty()) }
    var notes by remember { mutableStateOf(initial.notes) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Manual overrides") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DecimalField("Food calories", food) { food = it }
                DecimalField("Protein g", protein) { protein = it }
                DecimalField("Sodium mg", sodium) { sodium = it }
                DecimalField("Final burn calories", burn) { burn = it }
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(ManualOverrides(food.numberOrNull(), protein.numberOrNull(), sodium.numberOrNull(), burn.numberOrNull(), notes.trim())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun QuantityDialog(product: ProductWithExtras, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var quantity by remember { mutableStateOf("1") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(product.product.name) },
        text = { Column { Text(product.product.servingLabel); DecimalField("Quantity", quantity) { quantity = it } } },
        confirmButton = { Button(onClick = { onConfirm(quantity.numberOrNull()?.takeIf { it > 0 } ?: 1.0) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ProductEditorDialog(
    initialBarcode: String?,
    existing: ProductWithExtras?,
    addAfterSave: Boolean,
    onDismiss: () -> Unit,
    onSave: (ProductEntity, List<ProductExtraNutrientEntity>, Double) -> Unit
) {
    val product = existing?.product
    var barcode by remember(initialBarcode, existing) { mutableStateOf(product?.barcode ?: initialBarcode.orEmpty()) }
    var name by remember(existing) { mutableStateOf(product?.name.orEmpty()) }
    var brand by remember(existing) { mutableStateOf(product?.brand.orEmpty()) }
    var serving by remember(existing) { mutableStateOf(product?.servingLabel ?: "1 serving") }
    var quantity by remember { mutableStateOf("1") }
    var calories by remember(existing) { mutableStateOf(product?.calories?.toInput().orEmpty()) }
    var protein by remember(existing) { mutableStateOf(product?.proteinG?.toInput().orEmpty()) }
    var sodium by remember(existing) { mutableStateOf(product?.sodiumMg?.toInput().orEmpty()) }
    var carbs by remember(existing) { mutableStateOf(product?.carbsG?.toInput().orEmpty()) }
    var fat by remember(existing) { mutableStateOf(product?.fatG?.toInput().orEmpty()) }
    var sugar by remember(existing) { mutableStateOf(product?.sugarG?.toInput().orEmpty()) }
    var fiber by remember(existing) { mutableStateOf(product?.fiberG?.toInput().orEmpty()) }
    var saturated by remember(existing) { mutableStateOf(product?.saturatedFatG?.toInput().orEmpty()) }
    var extras by remember(existing) { mutableStateOf(existing?.extras?.joinToString("\n") { "${it.name}=${it.value} ${it.unit}" }.orEmpty()) }
    val valid = barcode.isNotBlank() && name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (product == null) "New product" else "Edit product") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(barcode, { barcode = it }, label = { Text("Barcode") }, enabled = product == null)
                OutlinedTextField(name, { name = it }, label = { Text("Product name") })
                OutlinedTextField(brand, { brand = it }, label = { Text("Brand") })
                OutlinedTextField(serving, { serving = it }, label = { Text("Serving label") })
                if (addAfterSave) DecimalField("Quantity", quantity) { quantity = it }
                DecimalField("Calories", calories) { calories = it }
                DecimalField("Protein g", protein) { protein = it }
                DecimalField("Sodium mg", sodium) { sodium = it }
                DecimalField("Carbs g", carbs) { carbs = it }
                DecimalField("Fat g", fat) { fat = it }
                DecimalField("Sugar g", sugar) { sugar = it }
                DecimalField("Fiber g", fiber) { fiber = it }
                DecimalField("Saturated fat g", saturated) { saturated = it }
                OutlinedTextField(extras, { extras = it }, label = { Text("Extra nutrients: Name=12 unit") })
            }
        },
        confirmButton = {
            Button(enabled = valid, onClick = {
                val entity = ProductEntity(
                    barcode.trim(), name.trim(), brand.trim(), serving.ifBlank { "1 serving" },
                    calories.number(), protein.number(), sodium.number(), carbs.number(), fat.number(),
                    sugar.number(), fiber.number(), saturated.number(), product?.notes.orEmpty(),
                    product?.createdAt ?: System.currentTimeMillis(), System.currentTimeMillis()
                )
                onSave(entity, parseExtras(barcode.trim(), extras), quantity.numberOrNull()?.takeIf { it > 0 } ?: 1.0)
            }) { Text(if (addAfterSave) "Save and add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FoodLogEditDialog(log: FoodLogSnapshot, onDismiss: () -> Unit, onSave: (FoodLogEdit) -> Unit) {
    var quantity by remember { mutableStateOf(log.quantity.toInput()) }
    var serving by remember { mutableStateOf(log.servingLabel) }
    var calories by remember { mutableStateOf(log.caloriesPerServing.toInput()) }
    var protein by remember { mutableStateOf(log.proteinGPerServing.toInput()) }
    var sodium by remember { mutableStateOf(log.sodiumMgPerServing.toInput()) }
    var carbs by remember { mutableStateOf(log.carbsGPerServing.toInput()) }
    var fat by remember { mutableStateOf(log.fatGPerServing.toInput()) }
    var sugar by remember { mutableStateOf(log.sugarGPerServing.toInput()) }
    var fiber by remember { mutableStateOf(log.fiberGPerServing.toInput()) }
    var saturated by remember { mutableStateOf(log.saturatedFatGPerServing.toInput()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${log.productName}") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DecimalField("Quantity", quantity) { quantity = it }
                OutlinedTextField(serving, { serving = it }, label = { Text("Serving label") })
                DecimalField("Calories per serving", calories) { calories = it }
                DecimalField("Protein g", protein) { protein = it }
                DecimalField("Sodium mg", sodium) { sodium = it }
                DecimalField("Carbs g", carbs) { carbs = it }
                DecimalField("Fat g", fat) { fat = it }
                DecimalField("Sugar g", sugar) { sugar = it }
                DecimalField("Fiber g", fiber) { fiber = it }
                DecimalField("Saturated fat g", saturated) { saturated = it }
            }
        },
        confirmButton = { Button(onClick = { onSave(FoodLogEdit(log.id, quantity.number().coerceAtLeast(0.01), serving, calories.number(), protein.number(), sodium.number(), carbs.number(), fat.number(), sugar.number(), fiber.number(), saturated.number())) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
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
private fun parseExtras(barcode: String, value: String): List<ProductExtraNutrientEntity> = value.lineSequence().mapNotNull { line ->
    val parts = line.split('=', limit = 2)
    if (parts.size != 2) return@mapNotNull null
    val amount = parts[1].trim().split(Regex("\\s+"), limit = 2)
    val number = amount.firstOrNull()?.replace(',', '.')?.toDoubleOrNull() ?: return@mapNotNull null
    val name = parts[0].trim()
    if (name.isBlank()) null else ProductExtraNutrientEntity(barcode, name, number, amount.getOrNull(1).orEmpty())
}.toList()

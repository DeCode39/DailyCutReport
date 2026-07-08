package com.littleone.dailycutreport

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
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
    settingsViewModel: SettingsViewModel,
    ocrViewModel: OcrViewModel,
    scannerLaunchRequests: State<Int>? = null
) {
    DailyCutTheme {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route ?: Destination.TODAY.route
        val selectedDate by dateViewModel.selectedDate.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val foodsState by foodsViewModel.uiState.collectAsStateWithLifecycle()
        val externalScannerLaunch = scannerLaunchRequests?.value ?: 0
        LaunchedEffect(externalScannerLaunch) {
            if (externalScannerLaunch > 0 && route != "scanner") navController.navigate("scanner")
        }
        LaunchedEffect(foodsState.thresholdMessage) {
            foodsState.thresholdMessage?.let {
                snackbarHostState.showSnackbar(it)
                foodsViewModel.clearThresholdMessage()
            }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_START) {
            todayViewModel.refreshTodayOnAppOpen()
            settingsViewModel.refresh()
        }
        val healthPermissionLauncher = rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) {
            settingsViewModel.refresh()
        }
        val nutritionWritePermissionLauncher = rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) {
            settingsViewModel.refresh()
        }

        Scaffold(
            topBar = { TopAppBar(title = { Text("Daily Cut Report", fontWeight = FontWeight.Bold) }) },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (route != "scanner" && route != "ocr") {
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
                        onScan = { navController.navigate("scanner") },
                        onEditLog = foodsViewModel::edit,
                        onDeleteLog = foodsViewModel::delete
                    )
                }
                composable(Destination.FOODS.route) {
                    FoodsScreen(
                        selectedDate,
                        dateViewModel,
                        foodsViewModel,
                        onScan = { navController.navigate("scanner") },
                        onOcr = { navController.navigate("ocr") }
                    )
                }
                composable(Destination.SETTINGS.route) {
                    SettingsScreen(
                        selectedDate = selectedDate,
                        viewModel = settingsViewModel,
                        onGrantCorePermissions = { healthPermissionLauncher.launch(HealthConnectManager.CORE_PERMISSIONS) },
                        onGrantNutritionPermission = { healthPermissionLauncher.launch(setOf(HealthConnectManager.NUTRITION_PERMISSION)) },
                        onGrantNutritionWritePermission = { nutritionWritePermissionLauncher.launch(setOf(HealthConnectManager.NUTRITION_WRITE_PERMISSION)) }
                    )
                }
                composable("scanner") {
                    BarcodeScannerScreen(
                        onResult = { result ->
                            navController.popBackStack()
                            if (result is ScannerResult.Found) foodsViewModel.handleBarcode(result.barcode)
                        }
                    )
                }
                composable("ocr") {
                    OcrCaptureScreen(
                        viewModel = ocrViewModel,
                        onCancel = { navController.popBackStack() },
                        onUse = { draft ->
                            foodsViewModel.applyOcr(draft)
                            navController.popBackStack()
                        }
                    )
                }
            }
            if (route != "scanner" && route != "ocr") {
                FoodWorkflowDialogs(
                    viewModel = foodsViewModel,
                    onOcr = { navController.navigate("ocr") }
                )
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
    onScan: () -> Unit,
    onEditLog: (FoodLogSnapshot) -> Unit,
    onDeleteLog: (Long) -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val createDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        if (uri != null) scope.launch {
            val saved = viewModel.writeReport(uri)
            Toast.makeText(context, if (saved) "Report saved" else "Could not save report", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.clearMessage() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Today", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                FilledIconButton(
                    modifier = Modifier.size(40.dp),
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
                ) { Text("↗") }
                FilledIconButton(
                    modifier = Modifier.size(40.dp),
                    onClick = {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                            createDocumentLauncher.launch("DailyCutReport_${state.report.date}.png")
                        } else scope.launch {
                            val uri = viewModel.saveReport()
                            Toast.makeText(context, if (uri == null) "Could not save report" else "Report saved", Toast.LENGTH_LONG).show()
                        }
                    }
                ) { Text("↓") }
            }
        }
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
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth()) { Text("Scan food") }
                }
            }
        }
        item { NutritionTargetsCard(state.report.nutrition, state.targets) }
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
            FoodLogCard(log, onEdit = { onEditLog(log) }, onDelete = { onDeleteLog(log.id) })
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun NutritionTargetsCard(nutrition: NutritionSummary, targets: DailyNutritionTargets) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Nutrition targets", style = MaterialTheme.typography.titleLarge)
            TargetRow("Calories", nutrition.calories, targets.calories, "kcal")
            TargetRow("Protein", nutrition.proteinG, targets.proteinG, "g")
            TargetRow("Sodium", nutrition.sodiumMg, targets.sodiumMg, "mg")
            TargetRow("Carbs", nutrition.carbsG, targets.carbsG, "g")
            TargetRow("Fat", nutrition.fatG, targets.fatG, "g")
            TargetRow("Sugar", nutrition.sugarG, targets.sugarG, "g")
            TargetRow("Fiber", nutrition.fiberG, targets.fiberG, "g")
            TargetRow("Saturated fat", nutrition.saturatedFatG, targets.saturatedFatG, "g")
        }
    }
}

@Composable
private fun TargetRow(label: String, value: Double, target: Double, unit: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label)
            Text("${value.roundToInt()} / ${target.roundToInt()} $unit", fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { (value / target).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FoodsScreen(
    selectedDate: LocalDate,
    dateViewModel: ReportDateViewModel,
    viewModel: FoodsViewModel,
    onScan: () -> Unit,
    onOcr: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var manualCode by remember { mutableStateOf("") }

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
                ProductSearchField(state.query, viewModel::setQuery)
                TextButton(onClick = viewModel::createProduct) { Text("Create product manually") }
            }
            items(state.products, key = { it.productId }) { product ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(product.name, fontWeight = FontWeight.Bold)
                            Text(listOfNotNull(product.brand.takeIf { it.isNotBlank() }, product.barcode).joinToString(" · "))
                        }
                        TextButton(onClick = { viewModel.editProduct(product) }) { Text("Edit") }
                        Button(onClick = { viewModel.selectProduct(product) }) { Text("Add") }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun FoodWorkflowDialogs(
    viewModel: FoodsViewModel,
    onOcr: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.message) {
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.clearMessage() }
    }
    state.pendingProduct?.let { product ->
        QuantityDialog(product, onDismiss = viewModel::cancelDialogs, onConfirm = viewModel::confirmAdd)
    }
    if (state.editorBarcode != null) {
        ProductEditorDialog(
            initialBarcode = state.editorBarcode,
            existing = state.editorProduct,
            addAfterSave = state.editorAddsAfterSave,
            ocrDraft = state.ocrDraft,
            onScanNutrition = onOcr,
            onDismiss = viewModel::cancelDialogs,
            onSave = viewModel::saveProduct
        )
    }
    state.editingLog?.let { log ->
        FoodLogEditDialog(log, onDismiss = viewModel::cancelDialogs, onSave = viewModel::saveLogEdit)
    }
}

@Composable
private fun FoodLogCard(log: FoodLogSnapshot, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${log.quantity} × ${log.productName}", fontWeight = FontWeight.Bold)
            Text("${log.calories.roundToInt()} kcal · ${log.proteinG.roundToInt()} g protein · ${log.sodiumMg.roundToInt()} mg sodium")
            Text("${log.carbsG.roundToInt()} g carbs · ${log.fatG.roundToInt()} g fat · ${log.sugarG.roundToInt()} g sugar", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Delete") }
            }
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
private fun OcrCaptureScreen(
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
private fun SettingsScreen(
    selectedDate: LocalDate,
    viewModel: SettingsViewModel,
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
        state.message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); viewModel.clearMessage() }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Health Connect", style = MaterialTheme.typography.titleLarge)
                    Text(if (state.healthAvailable) "Available" else "Unavailable")
                    Text(if (state.corePermissionsGranted) "Activity permissions granted" else "Activity permissions required")
                    Button(onClick = onGrantCorePermissions, modifier = Modifier.fillMaxWidth()) { Text("Grant activity permissions") }
                    OutlinedButton(
                        onClick = { viewModel.refreshHealth(selectedDate) },
                        enabled = !state.isRefreshingHealth,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (state.isRefreshingHealth) "Refreshing…" else "Refresh selected date") }
                    Text(if (state.nutritionPermissionGranted) "Optional nutrition read permission granted" else "Optional nutrition read permission not granted")
                    OutlinedButton(onClick = onGrantNutritionPermission, modifier = Modifier.fillMaxWidth()) { Text("Enable nutrition fallback") }
                    Text(if (state.nutritionWritePermissionGranted) "Optional nutrition write permission granted" else "Optional nutrition write permission not granted")
                    OutlinedButton(onClick = onGrantNutritionWritePermission, modifier = Modifier.fillMaxWidth()) { Text("Enable nutrition write") }
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
                    Text("Database schema 3 · Build ${packageInfo.longVersionCode}")
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
    ocrDraft: OcrNutritionDraft?,
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
    var extras by remember(existing) { mutableStateOf(existing?.extras?.joinToString("\n") { "${it.name}=${it.value} ${it.unit}" }.orEmpty()) }
    val valid = name.isNotBlank()

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
private fun FoodLogEditDialog(log: FoodLogSnapshot, onDismiss: () -> Unit, onSave: (FoodLogEdit) -> Unit) {
    var quantity by remember { mutableStateOf(log.quantity.toInput()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit servings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(log.productName, fontWeight = FontWeight.Bold)
                Text(log.servingLabel, style = MaterialTheme.typography.bodySmall)
                DecimalField("Quantity", quantity) { quantity = it }
            }
        },
        confirmButton = {
            Button(onClick = {
                onSave(log.quantityEdit(quantity.number().coerceAtLeast(0.01)))
            }) { Text("Save") }
        },
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
private fun Double.toReviewInput(): String = if (this == 0.0) "0" else toString()
private fun parseExtras(productId: String, value: String): List<ProductExtraNutrientEntity> = value.lineSequence().mapNotNull { line ->
    val parts = line.split('=', limit = 2)
    if (parts.size != 2) return@mapNotNull null
    val amount = parts[1].trim().split(Regex("\\s+"), limit = 2)
    val number = amount.firstOrNull()?.replace(',', '.')?.toDoubleOrNull() ?: return@mapNotNull null
    val name = parts[0].trim()
    if (name.isBlank()) null else ProductExtraNutrientEntity(productId, name, number, amount.getOrNull(1).orEmpty())
}.toList()

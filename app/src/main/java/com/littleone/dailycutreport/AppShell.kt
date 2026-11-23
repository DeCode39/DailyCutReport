package com.littleone.dailycutreport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

private enum class Destination(val route: String, val label: String, val icon: Int) {
    TODAY("today", "Today", R.drawable.ic_today),
    FOODS("foods", "Foods", R.drawable.ic_foods),
    HEALTH("health", "Health", R.drawable.ic_health),
    SETTINGS("settings", "Settings", R.drawable.ic_settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DailyCutApp(
    dateViewModel: ReportDateViewModel,
    todayViewModel: TodayViewModel,
    foodsViewModel: FoodsViewModel,
    healthViewModel: HealthViewModel,
    settingsViewModel: SettingsViewModel,
    plannerSettingsViewModel: PlannerSettingsViewModel,
    ocrViewModel: OcrViewModel,
    scannerLaunchRequests: State<Int>? = null
) {
    DailyCutTheme {
        val navController = rememberNavController()
        val backStack by navController.currentBackStackEntryAsState()
        val route = backStack?.destination?.route ?: Destination.TODAY.route
        val selectedDate by dateViewModel.selectedDate.collectAsStateWithLifecycle()
        val snackbarHostState = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var snackbarJob by remember { mutableStateOf<Job?>(null) }
        var scanContext by remember {
            mutableStateOf(ScanLaunchContext(ScanTarget.BULK_CART, selectedDate))
        }
        val scannerSession by foodsViewModel.scannerSession.collectAsStateWithLifecycle()
        val foodState by foodsViewModel.uiState.collectAsStateWithLifecycle()
        val showSnackbar: (String, FoodUndo?) -> Unit = { message, undo ->
            snackbarJob?.cancel()
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarJob = scope.launch {
                var undoAvailable = undo != null
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = undo?.let { "Undo" },
                    withDismissAction = undo != null,
                    duration = if (undo == null) SnackbarDuration.Short else SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed && undoAvailable) {
                    undoAvailable = false
                    undo?.let(foodsViewModel::undo)
                }
            }
        }
        val showMessage: (String) -> Unit = { showSnackbar(it, null) }
        val externalScannerLaunch = scannerLaunchRequests?.value ?: 0
        LaunchedEffect(externalScannerLaunch) {
            if (externalScannerLaunch > 0 && route != "scanner") {
                if (route == "product-editor") {
                    foodsViewModel.cancelDialogs()
                    navController.popBackStack()
                }
                scanContext = ScanLaunchContext(ScanTarget.BULK_CART, java.time.LocalDate.now(), externalLaunch = true)
                foodsViewModel.beginScanner(scanContext)
                navController.navigate("scanner")
            }
        }
        LaunchedEffect(Unit) {
            foodsViewModel.events.collect { event ->
                when (event) {
                    is FoodUiEvent.Threshold -> showSnackbar(event.text, null)
                    is FoodUiEvent.Message -> showSnackbar(event.text, event.undo)
                    FoodUiEvent.OpenProductEditor -> {
                        if (navController.currentDestination?.route == "scanner") navController.popBackStack()
                        if (navController.currentDestination?.route != "product-editor") navController.navigate("product-editor")
                    }
                    FoodUiEvent.CloseProductEditor -> {
                        if (navController.currentDestination?.route == "product-editor") navController.popBackStack()
                    }
                    FoodUiEvent.ResumeScanner -> {
                        if (navController.currentDestination?.route == "product-editor") navController.popBackStack()
                        if (navController.currentDestination?.route != "scanner") navController.navigate("scanner")
                    }
                    FoodUiEvent.ShowDraftReplacement -> {
                        if (navController.currentDestination?.route == "scanner") navController.popBackStack()
                        if (navController.currentDestination?.route == "product-editor") navController.popBackStack()
                    }
                }
            }
        }
        val healthPermissionLauncher = rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { settingsViewModel.permissionsChanged() }
        val nutritionWritePermissionLauncher = rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { settingsViewModel.permissionsChanged() }

        Scaffold(
            topBar = { if (Destination.entries.any { it.route == route }) LedgerMasthead() },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                if (route != "scanner" && route != "ocr" && route != "product-editor") {
                    NavigationBar(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                        Destination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = route == destination.route ||
                                    (destination == Destination.SETTINGS && route.startsWith("settings/")),
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(painterResource(destination.icon), contentDescription = destination.label) },
                                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                    selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                    indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                                ),
                                label = { Text(destination.label) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            val cartAction: @Composable () -> Unit = {
                if (foodState.bulkDraft.items.isNotEmpty() && !foodState.cartVisible) {
                    androidx.compose.material3.OutlinedButton(onClick = foodsViewModel::openCart) {
                        Text("Cart · ${foodState.bulkDraft.items.size}")
                    }
                }
            }
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            NavHost(
                navController = navController,
                modifier = if (Destination.entries.any { it.route == route } || route.startsWith("settings/"))
                    Modifier.align(Alignment.TopCenter).widthIn(max = 640.dp).fillMaxSize() else Modifier.fillMaxSize(),
                startDestination = Destination.TODAY.route
            ) {
                composable(Destination.TODAY.route) {
                    TodayScreen(
                        selectedDate, dateViewModel, todayViewModel,
                        onScan = {
                            scanContext = ScanLaunchContext(ScanTarget.BULK_CART, selectedDate)
                            foodsViewModel.beginScanner(scanContext)
                            navController.navigate("scanner")
                        },
                        cart = cartAction,
                        onEditLog = foodsViewModel::edit,
                        onDeleteLog = foodsViewModel::delete,
                        onDeleteGroup = foodsViewModel::deleteGroup,
                        onMessage = showMessage,
                        onCopyDay = { foodsViewModel.requestCopyDayToCart(selectedDate.minusDays(1)) },
                        onCopyGroup = { foodsViewModel.requestCopyDayToCart(selectedDate, it) }
                    )
                }
                composable(Destination.FOODS.route) {
                    FoodsScreen(
                        selectedDate, dateViewModel, foodsViewModel,
                        cart = cartAction,
                        onCreateProduct = foodsViewModel::createProduct
                    )
                }
                composable(Destination.SETTINGS.route) {
                    SettingsScreen(
                        selectedDate = selectedDate,
                        viewModel = settingsViewModel,
                        page = null,
                        onNavigate = { navController.navigate(it.route) },
                        onBack = { navController.popBackStack() },
                        onMessage = showMessage,
                        onGrantCorePermissions = { healthPermissionLauncher.launch(HealthConnectManager.CORE_PERMISSIONS) },
                        onGrantNutritionPermission = { healthPermissionLauncher.launch(setOf(HealthConnectManager.NUTRITION_PERMISSION)) },
                        onGrantNutritionWritePermission = { nutritionWritePermissionLauncher.launch(setOf(HealthConnectManager.NUTRITION_WRITE_PERMISSION)) },
                        onGrantWeightPermission = { healthPermissionLauncher.launch(setOf(HealthConnectManager.WEIGHT_PERMISSION)) },
                        onGrantWeightWritePermission = { healthPermissionLauncher.launch(setOf(HealthConnectManager.WEIGHT_WRITE_PERMISSION)) }
                    )
                }
                SettingsPage.entries.filterNot { it == SettingsPage.PLANNER }.forEach { page ->
                    composable(page.route) {
                        SettingsScreen(
                            selectedDate = selectedDate,
                            viewModel = settingsViewModel,
                            page = page,
                            onNavigate = { navController.navigate(it.route) },
                            onBack = { navController.popBackStack() },
                            onMessage = showMessage,
                            onGrantCorePermissions = { healthPermissionLauncher.launch(HealthConnectManager.CORE_PERMISSIONS) },
                            onGrantNutritionPermission = { healthPermissionLauncher.launch(setOf(HealthConnectManager.NUTRITION_PERMISSION)) },
                            onGrantNutritionWritePermission = { nutritionWritePermissionLauncher.launch(setOf(HealthConnectManager.NUTRITION_WRITE_PERMISSION)) },
                            onGrantWeightPermission = { healthPermissionLauncher.launch(setOf(HealthConnectManager.WEIGHT_PERMISSION)) },
                            onGrantWeightWritePermission = { healthPermissionLauncher.launch(setOf(HealthConnectManager.WEIGHT_WRITE_PERMISSION)) }
                        )
                    }
                }
                composable(SettingsPage.PLANNER.route) {
                    PlannerSettingsScreen(
                        viewModel = plannerSettingsViewModel,
                        onBack = { navController.popBackStack() },
                        onMessage = showMessage,
                        onEdit = foodsViewModel::editProduct,
                        onDelete = foodsViewModel::requestDeleteProduct
                    )
                }
                composable(Destination.HEALTH.route) {
                    HealthScreen(
                        selectedDate = selectedDate,
                        dateViewModel = dateViewModel,
                        cart = cartAction,
                        viewModel = healthViewModel,
                        onMessage = showMessage
                    )
                }
                composable("scanner") {
                    BarcodeScannerScreen(
                        multiAllowed = scanContext.target != ScanTarget.PRODUCT_DRAFT_BARCODE,
                        multiEnabled = scannerSession.multiEnabled,
                        queueCount = scannerSession.items.size,
                        sessionStatus = scannerSession.status,
                        onMultiChange = { enabled ->
                            if (!enabled && scannerSession.items.isNotEmpty()) {
                                foodsViewModel.finishMultiScan()
                                navController.popBackStack()
                            } else foodsViewModel.setMultiScan(enabled)
                        },
                        onFound = { barcode ->
                            if (scanContext.target == ScanTarget.PRODUCT_DRAFT_BARCODE) {
                                navController.popBackStack()
                                foodsViewModel.applyScannedBarcodeToDraft(barcode)
                            } else if (scannerSession.multiEnabled) foodsViewModel.handleMultiScanBarcode(barcode)
                            else {
                                navController.popBackStack()
                                foodsViewModel.handleBarcode(barcode, scanContext)
                            }
                        },
                        onDone = {
                            foodsViewModel.finishMultiScan()
                            navController.popBackStack()
                        },
                        onCancel = { navController.popBackStack() }
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
                composable("product-editor") {
                    val foodState by foodsViewModel.uiState.collectAsStateWithLifecycle()
                    val editor = foodState.workflow as? FoodWorkflowState.EditProduct
                    var linkedUsage by remember { mutableStateOf(ProductUsage()) }
                    LaunchedEffect(editor?.draft?.existing?.product?.productId) {
                        linkedUsage = runCatching { editor?.draft?.existing?.product?.productId?.let { foodsViewModel.productUsage(it) } }
                            .getOrNull() ?: ProductUsage()
                    }
                    if (editor != null) ProductEditorScreen(
                        draft = editor.draft,
                        currencyCode = foodState.goals.currencyCode,
                        linkedUsage = linkedUsage,
                        onDraftChange = foodsViewModel::updateProductDraft,
                        onScanBarcode = {
                            scanContext = ScanLaunchContext(ScanTarget.PRODUCT_DRAFT_BARCODE, selectedDate)
                            foodsViewModel.beginScanner(scanContext)
                            navController.navigate("scanner")
                        },
                        onScanNutrition = { navController.navigate("ocr") },
                        onDismiss = {
                            foodsViewModel.cancelProductEditor()
                            navController.popBackStack()
                        },
                        onBack = {
                            foodsViewModel.leaveProductEditor()
                            navController.popBackStack()
                        },
                        onSave = foodsViewModel::saveProduct
                    )
                }
            }
                if (
                    route.startsWith("settings") &&
                    foodState.bulkDraft.items.isNotEmpty() && !foodState.cartVisible
                ) {
                    ExtendedFloatingActionButton(
                        onClick = foodsViewModel::openCart,
                        modifier = Modifier.align(Alignment.BottomStart).padding(16.dp),
                        text = {
                            Text(
                                "Cart · ${foodState.bulkDraft.items.size}"
                            )
                        },
                        icon = { Icon(painterResource(R.drawable.ic_foods), null) },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            if (route != "scanner" && route != "ocr" && route != "product-editor") {
                FoodWorkflowDialogs(foodsViewModel)
            }
        }
    }
}

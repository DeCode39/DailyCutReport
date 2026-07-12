package com.littleone.dailycutreport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch

private enum class Destination(val route: String, val label: String, val icon: Int) {
    TODAY("today", "Today", R.drawable.ic_today),
    FOODS("foods", "Foods", R.drawable.ic_foods),
    SETTINGS("settings", "Settings", R.drawable.ic_settings)
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
        val scope = rememberCoroutineScope()
        val showMessage: (String) -> Unit = { message ->
            scope.launch { snackbarHostState.showSnackbar(message) }
        }
        val externalScannerLaunch = scannerLaunchRequests?.value ?: 0
        LaunchedEffect(externalScannerLaunch) {
            if (externalScannerLaunch > 0 && route != "scanner") navController.navigate("scanner")
        }
        LaunchedEffect(Unit) {
            foodsViewModel.events.collect { event ->
                when (event) {
                    is FoodUiEvent.Threshold -> snackbarHostState.showSnackbar(event.text)
                    is FoodUiEvent.Message -> {
                        val result = snackbarHostState.showSnackbar(
                            message = event.text,
                            actionLabel = event.undo?.let { "Undo" }
                        )
                        if (result == SnackbarResult.ActionPerformed) event.undo?.let(foodsViewModel::undoDelete)
                    }
                }
            }
        }
        val healthPermissionLauncher = rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { settingsViewModel.refresh() }
        val nutritionWritePermissionLauncher = rememberLauncherForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { settingsViewModel.refresh() }

        Scaffold(
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
                                icon = { Icon(painterResource(destination.icon), contentDescription = destination.label) },
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
                        selectedDate, dateViewModel, todayViewModel,
                        onScan = { navController.navigate("scanner") },
                        onEditLog = foodsViewModel::edit,
                        onDeleteLog = foodsViewModel::delete,
                        onMessage = showMessage
                    )
                }
                composable(Destination.FOODS.route) {
                    FoodsScreen(
                        selectedDate, dateViewModel, foodsViewModel,
                        onScan = { navController.navigate("scanner") },
                        onOcr = { navController.navigate("ocr") }
                    )
                }
                composable(Destination.SETTINGS.route) {
                    SettingsScreen(
                        selectedDate = selectedDate,
                        viewModel = settingsViewModel,
                        onMessage = showMessage,
                        onGrantCorePermissions = { healthPermissionLauncher.launch(HealthConnectManager.CORE_PERMISSIONS) },
                        onGrantNutritionPermission = { healthPermissionLauncher.launch(setOf(HealthConnectManager.NUTRITION_PERMISSION)) },
                        onGrantNutritionWritePermission = { nutritionWritePermissionLauncher.launch(setOf(HealthConnectManager.NUTRITION_WRITE_PERMISSION)) }
                    )
                }
                composable("scanner") {
                    BarcodeScannerScreen { result ->
                        navController.popBackStack()
                        if (result is ScannerResult.Found) foodsViewModel.handleBarcode(result.barcode)
                    }
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
                FoodWorkflowDialogs(foodsViewModel) { navController.navigate("ocr") }
            }
        }
    }
}

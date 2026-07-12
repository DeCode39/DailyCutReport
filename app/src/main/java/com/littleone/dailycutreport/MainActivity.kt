package com.littleone.dailycutreport

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.ViewModelProvider

const val EXTRA_OPEN_SCANNER = "com.littleone.dailycutreport.OPEN_SCANNER"

class MainActivity : ComponentActivity() {
    private val scannerLaunchRequests = mutableStateOf(0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
        if (intent?.getBooleanExtra(EXTRA_OPEN_SCANNER, false) == true) scannerLaunchRequests.value += 1
        val container = (application as DailyCutApplication).container
        val repository = container.repository
        val dateViewModel = ViewModelProvider(this, AppViewModelFactory(repository, ocr = container.nutritionLabelOcr))[ReportDateViewModel::class.java]
        val screenFactory = AppViewModelFactory(
            repository = repository,
            selectedDate = dateViewModel.selectedDate,
            ocr = container.nutritionLabelOcr,
            preprocessor = container.nutritionImagePreprocessor
        )
        val todayViewModel = ViewModelProvider(this, screenFactory)[TodayViewModel::class.java]
        val foodsViewModel = ViewModelProvider(this, screenFactory)[FoodsViewModel::class.java]
        val settingsViewModel = ViewModelProvider(this, screenFactory)[SettingsViewModel::class.java]
        val ocrViewModel = ViewModelProvider(this, screenFactory)[OcrViewModel::class.java]
        setContent {
            DailyCutApp(dateViewModel, todayViewModel, foodsViewModel, settingsViewModel, ocrViewModel, scannerLaunchRequests)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_SCANNER, false)) scannerLaunchRequests.value += 1
    }

    private fun applySystemBars() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}

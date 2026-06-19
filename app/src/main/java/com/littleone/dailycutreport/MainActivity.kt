package com.littleone.dailycutreport

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = (application as DailyCutApplication).container.repository
        val dateViewModel = ViewModelProvider(this, AppViewModelFactory(repository))[ReportDateViewModel::class.java]
        val screenFactory = AppViewModelFactory(repository, dateViewModel.selectedDate)
        val todayViewModel = ViewModelProvider(this, screenFactory)[TodayViewModel::class.java]
        val foodsViewModel = ViewModelProvider(this, screenFactory)[FoodsViewModel::class.java]
        val settingsViewModel = ViewModelProvider(this, screenFactory)[SettingsViewModel::class.java]

        setContent {
            DailyCutApp(dateViewModel, todayViewModel, foodsViewModel, settingsViewModel)
        }
    }
}


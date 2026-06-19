package com.littleone.dailycutreport

import android.app.Application
import android.content.Context

class DailyCutApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}

interface AppContainer {
    val repository: DailyCutRepository
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val database = NutritionDatabase.get(context)
    private val dao = database.nutritionDao()

    override val repository: DailyCutRepository = DefaultDailyCutRepository(
        dao = dao,
        healthConnect = HealthConnectManager(context),
        legacyImporter = LegacyReportImporter(context, dao),
        exporter = ReportImageExporter(context)
    )
}


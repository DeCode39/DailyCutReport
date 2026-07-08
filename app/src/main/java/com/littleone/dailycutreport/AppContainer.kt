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
    val nutritionLabelOcr: NutritionLabelOcr
    val nutritionImagePreprocessor: NutritionImagePreprocessor
}

class DefaultAppContainer(context: Context) : AppContainer {
    private val database = NutritionDatabase.get(context)
    private val dao = database.nutritionDao()
    override val nutritionImagePreprocessor: NutritionImagePreprocessor = AndroidNutritionImagePreprocessor(context)
    override val nutritionLabelOcr: NutritionLabelOcr = DefaultNutritionLabelOcr(
        MlKitTextRecognizerEngine(context),
        nutritionImagePreprocessor
    )

    override val repository: DailyCutRepository = DefaultDailyCutRepository(
        context = context.applicationContext,
        dao = dao,
        healthConnect = HealthConnectManager(context),
        legacyImporter = LegacyReportImporter(context, dao),
        catalogImporter = ProductCatalogImporter(context, dao),
        exporter = ReportImageExporter(context),
        backupManager = EncryptedAppBackupManager(context, dao)
    )
}

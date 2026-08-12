package com.littleone.dailycutreport

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDate

class DailyCutApplication : Application() {
    lateinit var container: AppContainer
        private set
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                applicationScope.launch {
                    val repository = container.repository
                    runCatching {
                        repository.initialize()
                        if (repository.healthConnectAvailable() && repository.healthCorePermissionsGranted()) {
                            repository.ensureHealthBootstrap()
                            repository.refreshHealth(LocalDate.now())
                            repository.syncHealthHistory(force = false)
                        }
                        repository.retryPendingNutritionSync()
                    }
                }
            }
        })
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
        backupManager = EncryptedAppBackupManager(context, dao)
    )
}

package com.littleone.dailycutreport

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.DecimalFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private enum class Tab { TODAY, FOODS, SETTINGS }

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var store: LocalStore
    private lateinit var exporter: ReportImageExporter
    private lateinit var nutritionDao: NutritionDao

    private var selectedDate: LocalDate = LocalDate.now()
    private var currentReport: DailyReport = DailyReport(selectedDate)
    private var currentTab: Tab = Tab.TODAY
    private var autoImportSessionDone = false
    private var autoImportInProgress = false
    private var currentLogs: List<DailyFoodLogEntity> = emptyList()

    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout

    private var foodInput: EditText? = null
    private var proteinInput: EditText? = null
    private var sodiumInput: EditText? = null
    private var manualBurnInput: EditText? = null
    private var notesInput: EditText? = null

    private var barcodeInput: EditText? = null
    private var quantityInput: EditText? = null
    private var productNameInput: EditText? = null
    private var brandInput: EditText? = null
    private var servingInput: EditText? = null
    private var productCaloriesInput: EditText? = null
    private var productProteinInput: EditText? = null
    private var productSodiumInput: EditText? = null
    private var productCarbsInput: EditText? = null
    private var productFatInput: EditText? = null
    private var productSugarInput: EditText? = null
    private var productFiberInput: EditText? = null
    private var productSatFatInput: EditText? = null
    private var productExtrasInput: EditText? = null

    private val numberFmt = DecimalFormat("#,##0")
    private val oneFmt = DecimalFormat("#,##0.0")
    private val dateFmt = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy")

    private val requestHealthPermissions = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { _ ->
        lifecycleScope.launch { refreshFromHealthConnect() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthConnectManager = HealthConnectManager(this)
        store = LocalStore(this)
        exporter = ReportImageExporter(this)
        nutritionDao = NutritionDatabase.get(this).nutritionDao()
        scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(32))
            setBackgroundColor(Color.rgb(246, 247, 246))
        }
        scroll.addView(root)
        setContentView(scroll)
        loadDate(LocalDate.now())
    }

    override fun onStart() {
        super.onStart()
        autoImportOnOpen()
    }

    override fun onStop() {
        super.onStop()
        autoImportSessionDone = false
    }

    private fun autoImportOnOpen() {
        if (autoImportSessionDone || autoImportInProgress) return
        autoImportSessionDone = true
        autoImportInProgress = true
        lifecycleScope.launch {
            var changed = false
            val healthSummary = runCatching {
                if (healthConnectManager.isAvailable() && healthConnectManager.hasAllPermissions()) {
                    healthConnectManager.readDailySummary(selectedDate)
                } else null
            }.getOrNull()
            if (healthSummary != null) {
                currentReport = store.mergeHealth(selectedDate, healthSummary)
                changed = true
            }
            refreshLocalNutrition(renderAfter = false)
            changed = true
            autoImportInProgress = false
            if (changed) {
                render()
                Toast.makeText(this@MainActivity, "Offline data refreshed.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun render() {
        root.removeAllViews()
        clearScreenInputs()
        renderHeader()
        when (currentTab) {
            Tab.TODAY -> renderTodayTab()
            Tab.FOODS -> renderFoodsTab()
            Tab.SETTINGS -> renderSettingsTab()
        }
    }

    private fun renderHeader() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        header.addView(ImageView(this).apply {
            setImageResource(resources.getIdentifier("ic_dailycut_logo", "drawable", packageName))
        }, LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(12) })

        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = "Daily Cut Report"
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(22, 28, 32))
        })
        titleBox.addView(TextView(this).apply {
            text = "Offline nutrition + daily image exporter"
            textSize = 13f
            setTextColor(Color.rgb(92, 99, 105))
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, dp(14))
        }
        tabs.addView(tabButton("Today", currentTab == Tab.TODAY) { currentTab = Tab.TODAY; render() }, weight = 1f)
        tabs.addView(tabButton("Foods", currentTab == Tab.FOODS) { currentTab = Tab.FOODS; render() }, weight = 1f)
        tabs.addView(tabButton("Settings", currentTab == Tab.SETTINGS) { currentTab = Tab.SETTINGS; render() }, weight = 1f)
        root.addView(tabs)
    }

    private fun renderTodayTab() {
        root.addView(dateCard())
        root.addView(summaryCard())
        root.addView(importCard())
        root.addView(manualCard())
        root.addView(exportCard())
    }

    private fun renderFoodsTab() {
        root.addView(foodEntryCard())
        root.addView(foodTotalsCard())
        root.addView(foodLogCard())
    }

    private fun renderSettingsTab() {
        root.addView(settingsStatusCard())
        root.addView(healthConnectSettingsCard())
        root.addView(legacyFatSecretCard())
    }

    private fun dateCard(): View = card {
        addView(sectionTitle("Report date"))
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(smallButton("◀") { loadDate(selectedDate.minusDays(1)) }, weight = 0.8f)
        row.addView(TextView(context).apply {
            text = selectedDate.format(dateFmt)
            textSize = 16f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 35, 35))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2.6f))
        row.addView(smallButton("Today") { loadDate(LocalDate.now()) }, weight = 1.2f)
        row.addView(smallButton("▶") { loadDate(selectedDate.plusDays(1)) }, weight = 0.8f)
        addView(row)
    }

    private fun summaryCard(): View = card {
        addView(sectionTitle("Final daily result"))
        val deficit = currentReport.deficitCalories
        val verdict = when {
            deficit >= 300 -> "Cut day"
            deficit <= -200 -> "Surplus day"
            else -> "Maintenance-ish"
        }
        addView(bigResult(verdict, if (deficit >= 0) "−${numberFmt.format(deficit.roundToInt())} kcal" else "+${numberFmt.format(abs(deficit).roundToInt())} kcal"))
        addView(twoColumnMetrics(
            "Burn" to "${numberFmt.format(currentReport.finalBurnCalories.roundToInt())} kcal",
            "Food" to "${numberFmt.format(currentReport.finalFoodCalories.roundToInt())} kcal",
            "Protein" to "${numberFmt.format(currentReport.finalProteinG.roundToInt())} g",
            "Sodium" to "${numberFmt.format(currentReport.finalSodiumMg.roundToInt())} mg"
        ))
        addView(body("Burn source: ${currentReport.burnSource}"))
        addView(body("Food source: ${currentReport.nutritionSource}"))
    }

    private fun importCard(): View = card {
        addView(sectionTitle("Daily imports"))
        val h = currentReport.health
        addView(body("Auto refresh runs once whenever the app is opened. It does not run in the background."))
        addView(body("Health Connect: ${h.healthConnectStatus}"))
        addView(twoColumnMetrics(
            "Steps" to numberFmt.format(h.steps),
            "Distance" to "${oneFmt.format(h.distanceKm)} km",
            "Active" to "${numberFmt.format(h.activeCalories.roundToInt())} kcal",
            "Total" to "${numberFmt.format(h.totalCalories.roundToInt())} kcal"
        ))
        addView(body("Exercises: ${h.exerciseSessions} sessions, ${h.exerciseMinutes} min"))
        addView(body("Offline food logs: ${currentReport.localNutrition.entries}"))
        addView(spacer(8))
        addView(primaryButton("Refresh Health Connect") { lifecycleScope.launch { refreshFromHealthConnect() } })
        addView(primaryButton("Refresh offline food totals") { lifecycleScope.launch { refreshLocalNutrition(renderAfter = true) } })
    }

    private fun manualCard(): View = card {
        addView(sectionTitle("Manual overrides"))
        addView(body("Use only for corrections. Blank nutrition fields use offline barcode totals first."))
        foodInput = input("Food calories, kcal").also { addView(label("Food calories")); addView(it) }
        proteinInput = input("Protein, g").also { addView(label("Protein")); addView(it) }
        sodiumInput = input("Sodium, mg").also { addView(label("Sodium")); addView(it) }
        manualBurnInput = input("Final burn override, kcal").also { addView(label("Final burn override")); addView(it) }
        notesInput = input("Notes", multiline = true, text = true).also { addView(label("Notes")); addView(it) }
        fillInputs(currentReport.manual)
        addView(primaryButton("Save overrides") { saveManualEntries() })
    }

    private fun exportCard(): View = card {
        addView(sectionTitle("Export"))
        addView(body("Creates a local PNG in Pictures/DailyCutReport. Export now includes default nutrition fields and extra nutrient totals."))
        addView(primaryButton("Save PNG") { exportImage(share = false) })
        addView(secondaryButton("Save and share PNG") { exportImage(share = true) })
    }

    private fun foodEntryCard(): View = card {
        addView(sectionTitle("Add product / barcode"))
        addView(body("Manual barcode entry for now. Camera scan and OCR can be layered on this Room database later."))
        barcodeInput = input("Barcode / product code", text = true).also { addView(label("Barcode")); addView(it) }
        quantityInput = input("Quantity", text = false).also { it.setText("1"); addView(label("Quantity")); addView(it) }
        addView(secondaryButton("Load saved product") { loadProductFromBarcode() })

        productNameInput = input("Product name", text = true).also { addView(label("Product name")); addView(it) }
        brandInput = input("Brand / store", text = true).also { addView(label("Brand / store")); addView(it) }
        servingInput = input("Serving label, e.g. 1 pack or 100 g", text = true).also { addView(label("Serving label")); addView(it) }
        productCaloriesInput = input("Calories per serving").also { addView(label("Calories")); addView(it) }
        productProteinInput = input("Protein g per serving").also { addView(label("Protein")); addView(it) }
        productSodiumInput = input("Sodium mg per serving").also { addView(label("Sodium")); addView(it) }
        productCarbsInput = input("Carbs g per serving").also { addView(label("Carbs")); addView(it) }
        productFatInput = input("Fat g per serving").also { addView(label("Fat")); addView(it) }
        productSugarInput = input("Sugar g per serving").also { addView(label("Sugar")); addView(it) }
        productFiberInput = input("Fiber g per serving").also { addView(label("Fiber")); addView(it) }
        productSatFatInput = input("Saturated fat g per serving").also { addView(label("Saturated fat")); addView(it) }
        productExtrasInput = input("Extra nutrients: one per line, e.g. Potassium=240 mg", multiline = true, text = true).also { addView(label("Optional extra fields")); addView(it) }

        addView(primaryButton("Save product") { saveProductOnly() })
        addView(primaryButton("Save product and add to today") { saveProductAndAddToToday() })
    }

    private fun foodTotalsCard(): View = card {
        val n = currentReport.localNutrition
        addView(sectionTitle("Offline nutrition totals"))
        addView(twoColumnMetrics(
            "Entries" to n.entries.toString(),
            "Calories" to "${numberFmt.format(n.calories.roundToInt())} kcal",
            "Protein" to "${numberFmt.format(n.proteinG.roundToInt())} g",
            "Sodium" to "${numberFmt.format(n.sodiumMg.roundToInt())} mg",
            "Carbs" to "${numberFmt.format(n.carbsG.roundToInt())} g",
            "Fat" to "${numberFmt.format(n.fatG.roundToInt())} g",
            "Sugar" to "${numberFmt.format(n.sugarG.roundToInt())} g",
            "Fiber" to "${numberFmt.format(n.fiberG.roundToInt())} g",
            "Sat. fat" to "${numberFmt.format(n.saturatedFatG.roundToInt())} g"
        ))
        if (n.extras.isNotEmpty()) {
            addView(body("Extra totals:"))
            n.extras.forEach { (name, value) -> addView(body("$name: $value")) }
        }
    }

    private fun foodLogCard(): View = card {
        addView(sectionTitle("Today's food log"))
        if (currentLogs.isEmpty()) {
            addView(body("No offline food entries for this date yet."))
        } else {
            currentLogs.forEach { log ->
                val row = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                row.addView(body("${log.quantity} × ${log.productName} ${if (log.brand.isBlank()) "" else "(${log.brand})"}"))
                row.addView(body("${numberFmt.format(log.calories.roundToInt())} kcal | ${numberFmt.format(log.proteinG.roundToInt())} g protein | ${numberFmt.format(log.sodiumMg.roundToInt())} mg sodium"))
                row.addView(secondaryButton("Delete this entry") { deleteFoodLog(log.id) })
                addView(row)
                addView(spacer(8))
            }
        }
    }

    private fun settingsStatusCard(): View = card {
        addView(sectionTitle("Settings status"))
        addView(body("Health Connect: ${currentReport.health.healthConnectStatus}"))
        addView(body("Offline database: ${currentReport.localNutrition.entries} food logs today"))
        addView(body("FatSecret is phased out. Current food source is the local Room database."))
    }

    private fun healthConnectSettingsCard(): View = card {
        addView(sectionTitle("Health Connect"))
        addView(body("Grant read permissions for steps, distance, calories, exercises, and optional nutrition."))
        addView(primaryButton("Grant / update Health Connect permissions") {
            requestHealthPermissions.launch(HealthConnectManager.PERMISSIONS)
        })
        addView(secondaryButton("Refresh Health Connect") { lifecycleScope.launch { refreshFromHealthConnect() } })
    }

    private fun legacyFatSecretCard(): View = card {
        addView(sectionTitle("Legacy FatSecret"))
        addView(body("FatSecret import is no longer part of the active workflow. Existing app-local FatSecret credentials are not used by this version."))
        addView(body("Next cleanup can remove FatSecretClient.kt and the internet permission entirely after this offline workflow is verified."))
    }

    private fun loadDate(date: LocalDate) {
        selectedDate = date
        currentReport = store.load(date) ?: DailyReport(date = date)
        render()
        lifecycleScope.launch { refreshLocalNutrition(renderAfter = true) }
    }

    private suspend fun refreshFromHealthConnect() {
        if (!healthConnectManager.isAvailable()) {
            Toast.makeText(this, healthConnectManager.availabilityMessage(), Toast.LENGTH_LONG).show()
            currentReport = currentReport.copy(
                health = currentReport.health.copy(healthConnectStatus = healthConnectManager.availabilityMessage())
            )
            render()
            return
        }
        if (!healthConnectManager.hasAllPermissions()) {
            Toast.makeText(this, "Health Connect permissions are not fully granted.", Toast.LENGTH_LONG).show()
            requestHealthPermissions.launch(HealthConnectManager.PERMISSIONS)
            return
        }
        val summary = runCatching { healthConnectManager.readDailySummary(selectedDate) }
            .getOrElse { HealthSummary(healthConnectStatus = "Read failed: ${it.message ?: it::class.java.simpleName}") }
        currentReport = store.mergeHealth(selectedDate, summary)
        render()
        Toast.makeText(this, "Health Connect refreshed.", Toast.LENGTH_SHORT).show()
    }

    private suspend fun refreshLocalNutrition(renderAfter: Boolean) {
        val totals = nutritionDao.totalsForDate(selectedDate.toString())
        val extras = nutritionDao.extraTotalsForDate(selectedDate.toString())
        currentLogs = nutritionDao.logsForDate(selectedDate.toString())
        val summary = LocalNutritionSummary(
            calories = totals.calories,
            proteinG = totals.proteinG,
            sodiumMg = totals.sodiumMg,
            carbsG = totals.carbsG,
            fatG = totals.fatG,
            sugarG = totals.sugarG,
            fiberG = totals.fiberG,
            saturatedFatG = totals.saturatedFatG,
            entries = totals.entries,
            extras = extras.associate { it.name to "${oneFmt.format(it.value)} ${it.unit}" }
        )
        currentReport = store.mergeLocalNutrition(selectedDate, summary, currentReport.health)
        if (renderAfter) render()
    }

    private fun loadProductFromBarcode() {
        val barcode = barcodeInput?.text?.toString()?.trim().orEmpty()
        if (barcode.isBlank()) {
            Toast.makeText(this, "Enter a barcode first.", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val product = nutritionDao.productByBarcode(barcode)
            if (product == null) {
                Toast.makeText(this@MainActivity, "Unknown barcode. Create it once, then future adds are automatic.", Toast.LENGTH_LONG).show()
                return@launch
            }
            fillProductInputs(product, nutritionDao.extrasForProduct(barcode))
            Toast.makeText(this@MainActivity, "Loaded ${product.name}.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveProductOnly() {
        lifecycleScope.launch {
            val product = productFromInputs() ?: return@launch
            val extras = extrasFromInput(product.barcode)
            nutritionDao.saveProductWithExtras(product, extras)
            Toast.makeText(this@MainActivity, "Product saved locally.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveProductAndAddToToday() {
        lifecycleScope.launch {
            val product = productFromInputs() ?: return@launch
            val extras = extrasFromInput(product.barcode)
            nutritionDao.saveProductWithExtras(product, extras)
            val quantity = quantityInput?.toDoubleValue()?.takeIf { it > 0.0 } ?: 1.0
            nutritionDao.addProductToDate(selectedDate.toString(), product, quantity, extras)
            refreshLocalNutrition(renderAfter = true)
            Toast.makeText(this@MainActivity, "Added ${product.name} to today.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteFoodLog(id: Long) {
        lifecycleScope.launch {
            nutritionDao.deleteLog(id)
            refreshLocalNutrition(renderAfter = true)
            Toast.makeText(this@MainActivity, "Deleted food entry.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun productFromInputs(): ProductEntity? {
        val barcode = barcodeInput?.text?.toString()?.trim().orEmpty()
        val name = productNameInput?.text?.toString()?.trim().orEmpty()
        if (barcode.isBlank() || name.isBlank()) {
            Toast.makeText(this, "Barcode and product name are required.", Toast.LENGTH_LONG).show()
            return null
        }
        return ProductEntity(
            barcode = barcode,
            name = name,
            brand = brandInput?.text?.toString()?.trim().orEmpty(),
            servingLabel = servingInput?.text?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "1 serving",
            calories = productCaloriesInput?.toDoubleValue() ?: 0.0,
            proteinG = productProteinInput?.toDoubleValue() ?: 0.0,
            sodiumMg = productSodiumInput?.toDoubleValue() ?: 0.0,
            carbsG = productCarbsInput?.toDoubleValue() ?: 0.0,
            fatG = productFatInput?.toDoubleValue() ?: 0.0,
            sugarG = productSugarInput?.toDoubleValue() ?: 0.0,
            fiberG = productFiberInput?.toDoubleValue() ?: 0.0,
            saturatedFatG = productSatFatInput?.toDoubleValue() ?: 0.0,
            notes = ""
        )
    }

    private fun extrasFromInput(barcode: String): List<ProductExtraNutrientEntity> {
        val raw = productExtrasInput?.text?.toString().orEmpty()
        return raw.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isBlank()) return@mapNotNull null
            val split = trimmed.split("=", limit = 2)
            if (split.size != 2) return@mapNotNull null
            val name = split[0].trim()
            val valueUnit = split[1].trim().split(Regex("\\s+"), limit = 2)
            val value = valueUnit.firstOrNull()?.toDoubleOrNull() ?: return@mapNotNull null
            val unit = valueUnit.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() } ?: ""
            if (name.isBlank()) null else ProductExtraNutrientEntity(barcode, name, value, unit)
        }.toList()
    }

    private fun fillProductInputs(product: ProductEntity, extras: List<ProductExtraNutrientEntity>) {
        productNameInput?.setText(product.name)
        brandInput?.setText(product.brand)
        servingInput?.setText(product.servingLabel)
        productCaloriesInput?.setText(if (product.calories == 0.0) "" else product.calories.roundToInt().toString())
        productProteinInput?.setText(if (product.proteinG == 0.0) "" else product.proteinG.roundToInt().toString())
        productSodiumInput?.setText(if (product.sodiumMg == 0.0) "" else product.sodiumMg.roundToInt().toString())
        productCarbsInput?.setText(if (product.carbsG == 0.0) "" else product.carbsG.roundToInt().toString())
        productFatInput?.setText(if (product.fatG == 0.0) "" else product.fatG.roundToInt().toString())
        productSugarInput?.setText(if (product.sugarG == 0.0) "" else product.sugarG.roundToInt().toString())
        productFiberInput?.setText(if (product.fiberG == 0.0) "" else product.fiberG.roundToInt().toString())
        productSatFatInput?.setText(if (product.saturatedFatG == 0.0) "" else product.saturatedFatG.roundToInt().toString())
        productExtrasInput?.setText(extras.joinToString("\n") { "${it.name}=${oneFmt.format(it.value)} ${it.unit}" })
    }

    private fun saveManualEntries() {
        val manual = ManualEntry(
            foodCalories = foodInput?.toDoubleValue() ?: currentReport.manual.foodCalories,
            proteinG = proteinInput?.toDoubleValue() ?: currentReport.manual.proteinG,
            sodiumMg = sodiumInput?.toDoubleValue() ?: currentReport.manual.sodiumMg,
            manualBurnCalories = manualBurnInput?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
            notes = notesInput?.text?.toString()?.trim().orEmpty()
        )
        currentReport = store.mergeManual(selectedDate, manual, currentReport.health)
        render()
        Toast.makeText(this, "Saved locally.", Toast.LENGTH_SHORT).show()
    }

    private fun exportImage(share: Boolean) {
        saveManualEntries()
        val uri = exporter.saveReportToPictures(currentReport)
        if (uri == null) {
            Toast.makeText(this, "Could not save PNG.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "PNG saved to Pictures/DailyCutReport.", Toast.LENGTH_LONG).show()
        if (share) exporter.shareImage(uri)
    }

    private fun fillInputs(manual: ManualEntry) {
        foodInput?.setText(if (manual.foodCalories == 0.0) "" else manual.foodCalories.roundToInt().toString())
        proteinInput?.setText(if (manual.proteinG == 0.0) "" else manual.proteinG.roundToInt().toString())
        sodiumInput?.setText(if (manual.sodiumMg == 0.0) "" else manual.sodiumMg.roundToInt().toString())
        manualBurnInput?.setText(manual.manualBurnCalories?.roundToInt()?.toString() ?: "")
        notesInput?.setText(manual.notes)
    }

    private fun clearScreenInputs() {
        foodInput = null
        proteinInput = null
        sodiumInput = null
        manualBurnInput = null
        notesInput = null
        barcodeInput = null
        quantityInput = null
        productNameInput = null
        brandInput = null
        servingInput = null
        productCaloriesInput = null
        productProteinInput = null
        productSodiumInput = null
        productCarbsInput = null
        productFatInput = null
        productSugarInput = null
        productFiberInput = null
        productSatFatInput = null
        productExtrasInput = null
    }

    private fun card(build: LinearLayout.() -> Unit): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(Color.WHITE, dp(18))
        elevation = dp(2).toFloat()
        build()
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(14)
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(29, 42, 50))
        setPadding(0, 0, 0, dp(8))
    }

    private fun bigResult(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(2), 0, dp(12))
        addView(TextView(context).apply {
            text = label
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(35, 95, 130))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(context).apply {
            text = value
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(Color.rgb(35, 35, 35))
        })
    }

    private fun twoColumnMetrics(vararg items: Pair<String, String>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (chunk in items.toList().chunked(2)) {
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (item in chunk) row.addView(metricTile(item.first, item.second), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6); bottomMargin = dp(8) })
            if (chunk.size == 1) row.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            addView(row)
        }
    }

    private fun metricTile(label: String, value: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = rounded(Color.rgb(244, 246, 247), dp(14))
        addView(TextView(context).apply { text = label; textSize = 12f; setTextColor(Color.rgb(99, 105, 110)) })
        addView(TextView(context).apply { text = value; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(31, 35, 38)) })
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(Color.rgb(84, 91, 97))
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.rgb(88, 96, 102))
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun input(hint: String, multiline: Boolean = false, text: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setSingleLine(!multiline)
        minLines = if (multiline) 2 else 1
        inputType = when {
            multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            text -> InputType.TYPE_CLASS_TEXT
            else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        setPadding(dp(12), dp(9), dp(12), dp(9))
        background = rounded(Color.rgb(250, 250, 250), dp(12), Color.rgb(224, 228, 230))
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = button(text, Color.rgb(35, 95, 130), Color.WHITE, onClick)
    private fun secondaryButton(text: String, onClick: () -> Unit) = button(text, Color.rgb(230, 236, 239), Color.rgb(35, 95, 130), onClick)
    private fun smallButton(text: String, onClick: () -> Unit) = button(text, Color.rgb(230, 236, 239), Color.rgb(29, 42, 50), onClick)
    private fun tabButton(text: String, selected: Boolean, onClick: () -> Unit) = button(text, if (selected) Color.rgb(35, 95, 130) else Color.rgb(230, 236, 239), if (selected) Color.WHITE else Color.rgb(35, 95, 130), onClick)

    private fun button(text: String, bg: Int, fg: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        setTextColor(fg)
        background = rounded(bg, dp(14))
        setOnClickListener { onClick() }
        setPadding(dp(8), dp(8), dp(8), dp(8))
    }

    private fun LinearLayout.addView(view: View, weight: Float) {
        addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = dp(3)
            marginEnd = dp(3)
        })
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun EditText.toDoubleValue(): Double = text.toString().trim().toDoubleOrNull() ?: 0.0
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

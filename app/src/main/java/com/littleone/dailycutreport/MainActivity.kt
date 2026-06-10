package com.littleone.dailycutreport

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var fatSecretClient: FatSecretClient
    private lateinit var store: LocalStore
    private lateinit var exporter: ReportImageExporter

    private var selectedDate: LocalDate = LocalDate.now()
    private var currentReport: DailyReport = DailyReport(selectedDate)

    private lateinit var root: LinearLayout
    private lateinit var dateText: TextView
    private lateinit var statusText: TextView
    private lateinit var stepsText: TextView
    private lateinit var distanceText: TextView
    private lateinit var activeCaloriesText: TextView
    private lateinit var totalCaloriesText: TextView
    private lateinit var exercisesText: TextView
    private lateinit var nutritionText: TextView
    private lateinit var fatSecretStatusText: TextView
    private lateinit var finalBurnText: TextView
    private lateinit var finalFoodText: TextView
    private lateinit var finalProteinText: TextView
    private lateinit var finalSodiumText: TextView
    private lateinit var deficitText: TextView

    private lateinit var consumerKeyInput: EditText
    private lateinit var consumerSecretInput: EditText
    private lateinit var verifierInput: EditText
    private lateinit var foodInput: EditText
    private lateinit var proteinInput: EditText
    private lateinit var sodiumInput: EditText
    private lateinit var manualBurnInput: EditText
    private lateinit var notesInput: EditText

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
        fatSecretClient = FatSecretClient(this)
        store = LocalStore(this)
        exporter = ReportImageExporter(this)
        buildUi()
        loadDate(LocalDate.now())
    }

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(40))
            setBackgroundColor(Color.rgb(248, 248, 246))
        }
        scroll.addView(root)
        setContentView(scroll)

        root.addView(title("Daily Cut Report"))
        root.addView(body("Health data comes from Health Connect. Food can come from FatSecret API, Health Connect nutrition, or manual override."))
        root.addView(spacer(16))

        val dateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dateRow.addView(button("◀") { loadDate(selectedDate.minusDays(1)) }, weight = 1f)
        dateText = sectionText("", center = true)
        dateRow.addView(dateText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 3f))
        dateRow.addView(button("Today") { loadDate(LocalDate.now()) }, weight = 1.2f)
        dateRow.addView(button("▶") { loadDate(selectedDate.plusDays(1)) }, weight = 1f)
        root.addView(dateRow)
        root.addView(spacer(10))

        statusText = body("")
        root.addView(statusText)
        root.addView(spacer(10))

        val permissionButton = button("Grant / update Health Connect permissions") {
            requestHealthPermissions.launch(HealthConnectManager.PERMISSIONS)
        }
        root.addView(permissionButton)
        root.addView(button("Refresh from Health Connect") {
            lifecycleScope.launch { refreshFromHealthConnect() }
        })

        root.addView(section("Health Connect import"))
        stepsText = metric("Steps", "—")
        distanceText = metric("Distance", "—")
        activeCaloriesText = metric("Active calories", "—")
        totalCaloriesText = metric("Total calories", "—")
        exercisesText = metric("Exercise sessions", "—")
        nutritionText = metric("Nutrition records", "—")
        root.addView(stepsText)
        root.addView(distanceText)
        root.addView(activeCaloriesText)
        root.addView(totalCaloriesText)
        root.addView(exercisesText)
        root.addView(nutritionText)

        root.addView(section("FatSecret API import"))
        root.addView(body("One-time setup: paste your OAuth 1.0 Consumer Key and Secret, save them locally, authorize in browser, then enter the verifier code. After that, import the selected date's diary."))
        fatSecretStatusText = metric("FatSecret", "—")
        consumerKeyInput = input("Consumer Key", text = true)
        consumerSecretInput = input("Consumer Secret", text = true)
        verifierInput = input("Verifier code from FatSecret", text = true)
        root.addView(fatSecretStatusText)
        root.addView(label("Consumer Key")); root.addView(consumerKeyInput)
        root.addView(label("Consumer Secret")); root.addView(consumerSecretInput)
        root.addView(button("Save FatSecret credentials locally") { saveFatSecretCredentials() })
        root.addView(button("Start FatSecret authorization") { startFatSecretAuthorization() })
        root.addView(label("Verifier code")); root.addView(verifierInput)
        root.addView(button("Complete FatSecret authorization") { completeFatSecretAuthorization() })
        root.addView(button("Import FatSecret diary for selected date") { importFatSecretDiary() })

        root.addView(section("Manual food finalizer"))
        root.addView(body("Manual food/protein/sodium override imported FatSecret or Health Connect nutrition. Leave them blank to use imported values."))
        foodInput = input("Food calories, kcal")
        proteinInput = input("Protein, g")
        sodiumInput = input("Sodium, mg")
        manualBurnInput = input("Optional final burn override, kcal")
        notesInput = input("Notes", multiline = true, text = true)
        root.addView(label("Food calories")); root.addView(foodInput)
        root.addView(label("Protein")); root.addView(proteinInput)
        root.addView(label("Sodium")); root.addView(sodiumInput)
        root.addView(label("Final burn override, optional")); root.addView(manualBurnInput)
        root.addView(label("Notes")); root.addView(notesInput)
        root.addView(button("Save manual entries") { saveManualEntries() })

        root.addView(section("Final daily result"))
        finalBurnText = metric("Final burn", "—")
        finalFoodText = metric("Final food", "—")
        finalProteinText = metric("Final protein", "—")
        finalSodiumText = metric("Final sodium", "—")
        deficitText = metric("Estimated deficit", "—")
        root.addView(finalBurnText)
        root.addView(finalFoodText)
        root.addView(finalProteinText)
        root.addView(finalSodiumText)
        root.addView(deficitText)
        root.addView(body("Burn rule: manual override → Health Connect total calories → active calories only. Nutrition rule: manual entries → FatSecret import → Health Connect nutrition → missing."))

        root.addView(section("Export"))
        root.addView(button("Save PNG to Pictures/DailyCutReport") { exportImage(share = false) })
        root.addView(button("Save and share PNG") { exportImage(share = true) })
    }

    private fun loadDate(date: LocalDate) {
        selectedDate = date
        currentReport = store.load(date) ?: DailyReport(date = date)
        dateText.text = date.format(dateFmt)
        fillInputs(currentReport.manual)
        updateDisplay()
    }

    private suspend fun refreshFromHealthConnect() {
        if (!healthConnectManager.isAvailable()) {
            Toast.makeText(this, healthConnectManager.availabilityMessage(), Toast.LENGTH_LONG).show()
            currentReport = currentReport.copy(
                health = currentReport.health.copy(healthConnectStatus = healthConnectManager.availabilityMessage())
            )
            updateDisplay()
            return
        }

        val hasPermissions = healthConnectManager.hasAllPermissions()
        if (!hasPermissions) {
            Toast.makeText(this, "Health Connect permissions are not fully granted.", Toast.LENGTH_LONG).show()
            requestHealthPermissions.launch(HealthConnectManager.PERMISSIONS)
            return
        }

        val summary = runCatching { healthConnectManager.readDailySummary(selectedDate) }
            .getOrElse { HealthSummary(healthConnectStatus = "Read failed: ${it.message ?: it::class.java.simpleName}") }
        currentReport = store.mergeHealth(selectedDate, summary)
        updateDisplay()
        Toast.makeText(this, "Health Connect data refreshed.", Toast.LENGTH_SHORT).show()
    }

    private fun saveFatSecretCredentials() {
        val key = consumerKeyInput.text.toString().trim()
        val secret = consumerSecretInput.text.toString().trim()
        if (key.isBlank() || secret.isBlank()) {
            Toast.makeText(this, "Enter both Consumer Key and Consumer Secret.", Toast.LENGTH_LONG).show()
            return
        }
        fatSecretClient.saveConsumerCredentials(key, secret)
        consumerSecretInput.setText("")
        updateDisplay()
        Toast.makeText(this, "FatSecret credentials saved locally.", Toast.LENGTH_SHORT).show()
    }

    private fun startFatSecretAuthorization() {
        lifecycleScope.launch {
            val result = runCatching { fatSecretClient.startAuthorization() }
            result.onSuccess { url ->
                updateDisplay()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                Toast.makeText(this@MainActivity, "FatSecret auth failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun completeFatSecretAuthorization() {
        val verifier = verifierInput.text.toString().trim()
        if (verifier.isBlank()) {
            Toast.makeText(this, "Enter the verifier code shown by FatSecret.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val result = runCatching { fatSecretClient.completeAuthorization(verifier) }
            result.onSuccess {
                verifierInput.setText("")
                updateDisplay()
                Toast.makeText(this@MainActivity, "FatSecret authorized.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, "FatSecret verifier failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun importFatSecretDiary() {
        lifecycleScope.launch {
            val result = runCatching { fatSecretClient.importFoodDiary(selectedDate) }
            result.onSuccess { diary ->
                val existing = currentReport.manual
                val manual = existing.copy(
                    foodCalories = diary.calories,
                    proteinG = diary.proteinG,
                    sodiumMg = diary.sodiumMg,
                    notes = mergeNote(existing.notes, "FatSecret API import: ${diary.entries} entries")
                )
                currentReport = store.mergeManual(selectedDate, manual, currentReport.health)
                fillInputs(currentReport.manual)
                updateDisplay()
                Toast.makeText(this@MainActivity, "FatSecret imported ${diary.entries} entries.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, "FatSecret import failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveManualEntries() {
        val manual = ManualEntry(
            foodCalories = foodInput.toDoubleValue(),
            proteinG = proteinInput.toDoubleValue(),
            sodiumMg = sodiumInput.toDoubleValue(),
            manualBurnCalories = manualBurnInput.text.toString().trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull(),
            notes = notesInput.text.toString().trim()
        )
        currentReport = store.mergeManual(selectedDate, manual, currentReport.health)
        updateDisplay()
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
        foodInput.setText(if (manual.foodCalories == 0.0) "" else manual.foodCalories.roundToInt().toString())
        proteinInput.setText(if (manual.proteinG == 0.0) "" else manual.proteinG.roundToInt().toString())
        sodiumInput.setText(if (manual.sodiumMg == 0.0) "" else manual.sodiumMg.roundToInt().toString())
        manualBurnInput.setText(manual.manualBurnCalories?.roundToInt()?.toString() ?: "")
        notesInput.setText(manual.notes)
    }

    private fun updateDisplay() {
        val h = currentReport.health
        statusText.text = "${h.healthConnectStatus}. Network access enabled only for FatSecret API."
        fatSecretStatusText.text = "FatSecret: ${fatSecretClient.status()}"
        stepsText.text = "Steps: ${numberFmt.format(h.steps)}"
        distanceText.text = "Distance: ${oneFmt.format(h.distanceKm)} km"
        activeCaloriesText.text = "Active calories: ${numberFmt.format(h.activeCalories.roundToInt())} kcal"
        totalCaloriesText.text = "Total calories: ${numberFmt.format(h.totalCalories.roundToInt())} kcal"
        exercisesText.text = "Exercise sessions: ${h.exerciseSessions} (${h.exerciseMinutes} min)"
        nutritionText.text = "Nutrition records: ${h.nutritionRecords} | ${numberFmt.format(h.nutritionCalories.roundToInt())} kcal, ${numberFmt.format(h.nutritionProteinG.roundToInt())} g protein, ${numberFmt.format(h.nutritionSodiumMg.roundToInt())} mg sodium"
        finalBurnText.text = "Final burn: ${numberFmt.format(currentReport.finalBurnCalories.roundToInt())} kcal (${currentReport.burnSource})"
        finalFoodText.text = "Final food: ${numberFmt.format(currentReport.finalFoodCalories.roundToInt())} kcal (${currentReport.nutritionSource})"
        finalProteinText.text = "Final protein: ${numberFmt.format(currentReport.finalProteinG.roundToInt())} g"
        finalSodiumText.text = "Final sodium: ${numberFmt.format(currentReport.finalSodiumMg.roundToInt())} mg"

        val deficit = currentReport.deficitCalories
        val sign = if (deficit >= 0) "−" else "+"
        deficitText.text = "Estimated deficit: $sign${numberFmt.format(kotlin.math.abs(deficit).roundToInt())} kcal"
        deficitText.setTextColor(
            when {
                deficit >= 300 -> Color.rgb(20, 110, 55)
                deficit <= -200 -> Color.rgb(165, 45, 45)
                else -> Color.rgb(80, 80, 80)
            }
        )
    }

    private fun mergeNote(old: String, addition: String): String = when {
        old.isBlank() -> addition
        old.contains(addition) -> old
        else -> "$old\n$addition"
    }

    private fun title(text: String) = TextView(this).apply {
        this.text = text
        textSize = 28f
        setTextColor(Color.rgb(20, 20, 20))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun section(text: String) = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(Color.rgb(35, 95, 130))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(0, dp(24), 0, dp(8))
    }

    private fun sectionText(text: String, center: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.rgb(45, 45, 45))
        if (center) gravity = Gravity.CENTER
    }

    private fun body(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.rgb(80, 80, 80))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun metric(label: String, value: String) = TextView(this).apply {
        text = "$label: $value"
        textSize = 17f
        setTextColor(Color.rgb(35, 35, 35))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(Color.rgb(80, 80, 80))
        setPadding(0, dp(8), 0, dp(2))
    }

    private fun input(hint: String, multiline: Boolean = false, text: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setSingleLine(!multiline)
        minLines = if (multiline) 2 else 1
        inputType = when {
            multiline || text -> InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
            else -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setBackgroundColor(Color.WHITE)
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun LinearLayout.addView(view: View, weight: Float) {
        addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
            marginStart = dp(2)
            marginEnd = dp(2)
        })
    }

    private fun spacer(heightDp: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(heightDp))
    }

    private fun EditText.toDoubleValue(): Double = text.toString().trim().toDoubleOrNull() ?: 0.0
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}

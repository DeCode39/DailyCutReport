package com.littleone.dailycutreport

import android.graphics.Color
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
    private lateinit var finalBurnText: TextView
    private lateinit var deficitText: TextView

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
        root.addView(body("Offline Android MVP. Health data is read from Health Connect; food data is entered manually."))
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
        root.addView(stepsText)
        root.addView(distanceText)
        root.addView(activeCaloriesText)
        root.addView(totalCaloriesText)
        root.addView(exercisesText)

        root.addView(section("Manual food finalizer"))
        foodInput = input("Food calories, kcal")
        proteinInput = input("Protein, g")
        sodiumInput = input("Sodium, mg")
        manualBurnInput = input("Optional final burn override, kcal")
        notesInput = input("Notes", multiline = true)
        root.addView(label("Food calories")); root.addView(foodInput)
        root.addView(label("Protein")); root.addView(proteinInput)
        root.addView(label("Sodium")); root.addView(sodiumInput)
        root.addView(label("Final burn override, optional")); root.addView(manualBurnInput)
        root.addView(label("Notes")); root.addView(notesInput)
        root.addView(button("Save manual entries") { saveManualEntries() })

        root.addView(section("Final daily result"))
        finalBurnText = metric("Final burn", "—")
        deficitText = metric("Estimated deficit", "—")
        root.addView(finalBurnText)
        root.addView(deficitText)
        root.addView(body("Rule: final burn uses your manual override if entered; otherwise Health Connect total calories; otherwise active calories only."))

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
        statusText.text = "${h.healthConnectStatus}. Runtime network access: not requested."
        stepsText.text = "Steps: ${numberFmt.format(h.steps)}"
        distanceText.text = "Distance: ${oneFmt.format(h.distanceKm)} km"
        activeCaloriesText.text = "Active calories: ${numberFmt.format(h.activeCalories.roundToInt())} kcal"
        totalCaloriesText.text = "Total calories: ${numberFmt.format(h.totalCalories.roundToInt())} kcal"
        exercisesText.text = "Exercise sessions: ${h.exerciseSessions} (${h.exerciseMinutes} min)"
        finalBurnText.text = "Final burn: ${numberFmt.format(currentReport.finalBurnCalories.roundToInt())} kcal (${currentReport.burnSource})"

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

    private fun input(hint: String, multiline: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setSingleLine(!multiline)
        minLines = if (multiline) 2 else 1
        inputType = if (multiline) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
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

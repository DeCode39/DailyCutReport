package com.littleone.dailycutreport

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
    private enum class Tab { TODAY, SETTINGS }

    private lateinit var healthConnectManager: HealthConnectManager
    private lateinit var fatSecretClient: FatSecretClient
    private lateinit var store: LocalStore
    private lateinit var exporter: ReportImageExporter

    private var selectedDate: LocalDate = LocalDate.now()
    private var currentReport: DailyReport = DailyReport(selectedDate)
    private var currentTab: Tab = Tab.TODAY

    private lateinit var scroll: ScrollView
    private lateinit var root: LinearLayout

    private var foodInput: EditText? = null
    private var proteinInput: EditText? = null
    private var sodiumInput: EditText? = null
    private var manualBurnInput: EditText? = null
    private var notesInput: EditText? = null
    private var consumerKeyInput: EditText? = null
    private var consumerSecretInput: EditText? = null
    private var verifierInput: EditText? = null

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

    private fun render() {
        root.removeAllViews()
        clearScreenInputs()
        renderHeader()
        when (currentTab) {
            Tab.TODAY -> renderTodayTab()
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
            text = "Fitness + FatSecret daily image exporter"
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

    private fun renderSettingsTab() {
        root.addView(settingsStatusCard())
        root.addView(healthConnectSettingsCard())
        root.addView(fatSecretSettingsCard())
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
        addView(body("Health Connect: ${h.healthConnectStatus}"))
        addView(twoColumnMetrics(
            "Steps" to numberFmt.format(h.steps),
            "Distance" to "${oneFmt.format(h.distanceKm)} km",
            "Active" to "${numberFmt.format(h.activeCalories.roundToInt())} kcal",
            "Total" to "${numberFmt.format(h.totalCalories.roundToInt())} kcal"
        ))
        addView(body("Exercises: ${h.exerciseSessions} sessions, ${h.exerciseMinutes} min"))
        addView(body("Health nutrition records: ${h.nutritionRecords}"))
        addView(spacer(8))
        addView(primaryButton("Refresh Health Connect") { lifecycleScope.launch { refreshFromHealthConnect() } })
        addView(primaryButton("Import FatSecret diary") { importFatSecretDiary() })
    }

    private fun manualCard(): View = card {
        addView(sectionTitle("Manual overrides"))
        addView(body("Leave blank to keep imported FatSecret/Health Connect values."))
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
        addView(body("Creates a local PNG in Pictures/DailyCutReport."))
        addView(primaryButton("Save PNG") { exportImage(share = false) })
        addView(secondaryButton("Save and share PNG") { exportImage(share = true) })
    }

    private fun settingsStatusCard(): View = card {
        addView(sectionTitle("Settings status"))
        addView(body("Health Connect: ${currentReport.health.healthConnectStatus}"))
        addView(body("FatSecret: ${fatSecretClient.status()}"))
        addView(body("Network access is used for FatSecret API calls only."))
    }

    private fun healthConnectSettingsCard(): View = card {
        addView(sectionTitle("Health Connect"))
        addView(body("Grant read permissions for steps, distance, calories, exercises, and nutrition. Nutrition is optional because FatSecret API is the primary food source."))
        addView(primaryButton("Grant / update Health Connect permissions") {
            requestHealthPermissions.launch(HealthConnectManager.PERMISSIONS)
        })
        addView(secondaryButton("Refresh Health Connect") { lifecycleScope.launch { refreshFromHealthConnect() } })
    }

    private fun fatSecretSettingsCard(): View = card {
        addView(sectionTitle("FatSecret authorization"))
        addView(body("Your Consumer Secret and access token are stored only in this app's local private storage."))
        consumerKeyInput = input("Consumer Key", text = true).also { addView(label("Consumer Key")); addView(it) }
        consumerSecretInput = input("Consumer Secret", text = true).also { addView(label("Consumer Secret")); addView(it) }
        addView(primaryButton("Save credentials locally") { saveFatSecretCredentials() })
        addView(secondaryButton("Start browser authorization") { startFatSecretAuthorization() })
        verifierInput = input("Verifier code", text = true).also { addView(label("Verifier code")); addView(it) }
        addView(primaryButton("Complete authorization") { completeFatSecretAuthorization() })
        addView(body("After authorization, return to Today and tap Import FatSecret diary."))
    }

    private fun loadDate(date: LocalDate) {
        selectedDate = date
        currentReport = store.load(date) ?: DailyReport(date = date)
        render()
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

    private fun saveFatSecretCredentials() {
        val key = consumerKeyInput?.text?.toString()?.trim().orEmpty()
        val secret = consumerSecretInput?.text?.toString()?.trim().orEmpty()
        if (key.isBlank() || secret.isBlank()) {
            Toast.makeText(this, "Enter both Consumer Key and Consumer Secret.", Toast.LENGTH_LONG).show()
            return
        }
        fatSecretClient.saveConsumerCredentials(key, secret)
        consumerSecretInput?.setText("")
        render()
        Toast.makeText(this, "FatSecret credentials saved locally.", Toast.LENGTH_SHORT).show()
    }

    private fun startFatSecretAuthorization() {
        lifecycleScope.launch {
            val result = runCatching { fatSecretClient.startAuthorization() }
            result.onSuccess { url ->
                render()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }.onFailure {
                Toast.makeText(this@MainActivity, "FatSecret auth failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun completeFatSecretAuthorization() {
        val verifier = verifierInput?.text?.toString()?.trim().orEmpty()
        if (verifier.isBlank()) {
            Toast.makeText(this, "Enter the verifier code shown by FatSecret.", Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val result = runCatching { fatSecretClient.completeAuthorization(verifier) }
            result.onSuccess {
                verifierInput?.setText("")
                render()
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
                render()
                Toast.makeText(this@MainActivity, "FatSecret imported ${diary.entries} entries.", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(this@MainActivity, "FatSecret import failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
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
        consumerKeyInput = null
        consumerSecretInput = null
        verifierInput = null
    }

    private fun mergeNote(old: String, addition: String): String = when {
        old.isBlank() -> addition
        old.contains(addition) -> old
        else -> "$old\n$addition"
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
            multiline || text -> InputType.TYPE_CLASS_TEXT or if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0
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

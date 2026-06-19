package com.littleone.dailycutreport

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.DecimalFormat
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToInt

class ReportImageExporter(private val context: Context) {
    private val whole = DecimalFormat("#,##0")
    private val one = DecimalFormat("#,##0.0")
    private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy")

    fun saveReportToPictures(report: DailyReport): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName(report))
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DailyCutReport")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return if (writeReport(uri, report)) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun writeReport(uri: Uri, report: DailyReport): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
            check(drawReportBitmap(report).compress(Bitmap.CompressFormat.PNG, 100, output))
        } ?: error("Could not open report destination")
        true
    }.getOrDefault(false)

    fun createShareUri(report: DailyReport): Uri? = runCatching {
        val directory = File(context.cacheDir, "shared_reports").apply { mkdirs() }
        val file = File(directory, fileName(report))
        file.outputStream().use { output ->
            check(drawReportBitmap(report).compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

    private fun fileName(report: DailyReport) = "DailyCutReport_${report.date}.png"

    private fun drawReportBitmap(report: DailyReport): Bitmap {
        val notesLines = wrap(report.manual.notes, 58)
        val extraRows = report.nutrition.extras.size.coerceAtMost(12)
        val width = 1080
        val height = 1680 + extraRows * 42 + if (notesLines.isEmpty()) 0 else 100 + notesLines.size * 38
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 248, 246) }
        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30) }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(95, 95, 95) }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 95, 130) }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 220, 216); strokeWidth = 3f }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), background)

        fun text(value: String, x: Float, y: Float, size: Float, paint: Paint = dark, bold: Boolean = false) {
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            canvas.drawText(value, x, y, paint)
        }
        fun rule(y: Float) = canvas.drawLine(72f, y, width - 72f, y, line)
        fun metric(label: String, value: String, left: Float, top: Float) {
            text(label, left, top, 24f, muted)
            text(value, left, top + 43f, 38f, dark, true)
        }

        var y = 90f
        text("Daily Cut Report", 72f, y, 52f, dark, true)
        y += 54f
        text(report.date.format(dateFmt), 72f, y, 31f, muted)
        y += 50f; rule(y); y += 68f
        text(report.verdict.label, 72f, y, 43f, accent, true)
        y += 70f
        val sign = if (report.deficitCalories >= 0) "−" else "+"
        text("$sign${whole.format(abs(report.deficitCalories).roundToInt())} kcal", 72f, y, 64f, dark, true)
        y += 84f

        text("Activity", 72f, y, 31f, accent, true); y += 48f
        metric("Steps", whole.format(report.health.steps), 72f, y)
        metric("Distance", "${one.format(report.health.distanceKm)} km", 570f, y); y += 110f
        metric("Final burn", "${whole.format(report.finalBurnCalories)} kcal", 72f, y)
        metric("Active burn", "${whole.format(report.health.activeCalories)} kcal", 570f, y); y += 110f
        metric("Total burn", "${whole.format(report.health.totalCalories)} kcal", 72f, y)
        metric("Exercises", "${report.health.exerciseSessions} · ${report.health.exerciseMinutes} min", 570f, y)
        y += 96f; rule(y); y += 56f

        val nutrition = report.nutrition
        text("Nutrition", 72f, y, 31f, accent, true); y += 48f
        metric("Food", "${whole.format(report.finalFoodCalories)} kcal", 72f, y)
        metric("Entries", nutrition.entries.toString(), 570f, y); y += 110f
        metric("Protein", "${whole.format(report.finalProteinG)} g", 72f, y)
        metric("Sodium", "${whole.format(report.finalSodiumMg)} mg", 570f, y); y += 110f
        metric("Carbs", "${whole.format(nutrition.carbsG)} g", 72f, y)
        metric("Fat", "${whole.format(nutrition.fatG)} g", 570f, y); y += 110f
        metric("Sugar", "${whole.format(nutrition.sugarG)} g", 72f, y)
        metric("Fiber", "${whole.format(nutrition.fiberG)} g", 570f, y); y += 110f
        metric("Saturated fat", "${whole.format(nutrition.saturatedFatG)} g", 72f, y)
        metric("Source", report.nutritionSource, 570f, y); y += 96f

        if (nutrition.extras.isNotEmpty()) {
            rule(y); y += 54f
            text("Extra nutrients", 72f, y, 31f, accent, true); y += 42f
            nutrition.extras.entries.take(12).forEach { (name, amount) ->
                text("$name: ${one.format(amount.value)} ${amount.unit}", 72f, y, 26f)
                y += 42f
            }
        }

        rule(y); y += 54f
        text("Sources", 72f, y, 31f, accent, true); y += 42f
        text("Burn: ${report.burnSource}", 72f, y, 24f, muted); y += 34f
        text("Nutrition: ${report.nutritionSource}", 72f, y, 24f, muted); y += 34f
        wrap("Health: ${report.health.healthConnectStatus}", 72).forEach { lineText ->
            text(lineText, 72f, y, 23f, muted); y += 32f
        }

        if (notesLines.isNotEmpty()) {
            y += 20f; rule(y); y += 50f
            text("Notes", 72f, y, 30f, accent, true); y += 40f
            notesLines.forEach { lineText -> text(lineText, 72f, y, 25f); y += 38f }
        }
        text("Generated locally. No network permission.", 72f, height - 58f, 23f, muted)
        return bitmap
    }

    private fun wrap(value: String, maxCharacters: Int): List<String> {
        if (value.isBlank()) return emptyList()
        val lines = mutableListOf<String>()
        var current = ""
        value.trim().split(Regex("\\s+")).forEach { word ->
            if (current.isNotEmpty() && current.length + word.length + 1 > maxCharacters) {
                lines += current
                current = word
            } else current = listOf(current, word).filter { it.isNotEmpty() }.joinToString(" ")
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}

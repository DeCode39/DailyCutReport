package com.littleone.dailycutreport

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.text.DecimalFormat
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class ReportImageExporter(private val context: Context) {
    private val whole = DecimalFormat("#,##0")
    private val one = DecimalFormat("#,##0.0")
    private val dateFmt = DateTimeFormatter.ofPattern("dd MMM yyyy")

    fun saveReportToPictures(report: DailyReport): Uri? {
        val bitmap = drawReportBitmap(report)
        val fileName = "DailyCutReport_${report.date}.png"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DailyCutReport")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (_: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    fun shareImage(uri: Uri) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Share daily report"))
    }

    private fun drawReportBitmap(report: DailyReport): Bitmap {
        val width = 1080
        val height = 1900
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(248, 248, 246) }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)

        val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30) }
        val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(95, 95, 95) }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 220, 216); strokeWidth = 3f }
        val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 95, 130) }

        fun text(value: String, x: Float, y: Float, size: Float, paint: Paint = dark, bold: Boolean = false) {
            paint.textSize = size
            paint.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            canvas.drawText(value, x, y, paint)
        }

        fun metric(label: String, value: String, unit: String, left: Float, top: Float) {
            text(label, left, top, 25f, muted)
            text(value, left, top + 44f, 39f, dark, true)
            if (unit.isNotBlank()) text(unit, left + 230f, top + 44f, 25f, muted)
        }

        var y = 92f
        text("Daily Cut Report", 72f, y, 52f, dark, true)
        y += 54f
        text(report.date.format(dateFmt), 72f, y, 32f, muted)
        y += 50f
        canvas.drawLine(72f, y, width - 72f, y, line)
        y += 72f

        val verdict = when {
            report.deficitCalories >= 300 -> "Cut day"
            report.deficitCalories <= -200 -> "Surplus day"
            else -> "Maintenance-ish"
        }
        text(verdict, 72f, y, 44f, accent, true)
        y += 42f
        text("Deficit = burn − intake", 72f, y, 26f, muted)
        y += 70f
        val deficitSign = if (report.deficitCalories >= 0) "−" else "+"
        text("$deficitSign${whole.format(kotlin.math.abs(report.deficitCalories).roundToInt())} kcal", 72f, y, 66f, dark, true)
        y += 82f

        text("Activity", 72f, y, 31f, accent, true)
        y += 48f
        metric("Steps", whole.format(report.health.steps), "", 72f, y)
        metric("Distance", one.format(report.health.distanceKm), "km", 570f, y)
        y += 120f
        metric("Final burn", whole.format(report.finalBurnCalories.roundToInt()), "kcal", 72f, y)
        metric("Active burn", whole.format(report.health.activeCalories.roundToInt()), "kcal", 570f, y)
        y += 120f
        metric("Total burn", whole.format(report.health.totalCalories.roundToInt()), "kcal", 72f, y)
        metric("Exercises", "${report.health.exerciseSessions}", "${report.health.exerciseMinutes} min", 570f, y)
        y += 104f
        canvas.drawLine(72f, y, width - 72f, y, line)
        y += 58f

        val n = report.localNutrition
        text("Nutrition", 72f, y, 31f, accent, true)
        y += 48f
        metric("Food", whole.format(report.finalFoodCalories.roundToInt()), "kcal", 72f, y)
        metric("Entries", n.entries.toString(), "", 570f, y)
        y += 120f
        metric("Protein", whole.format(report.finalProteinG.roundToInt()), "g", 72f, y)
        metric("Sodium", whole.format(report.finalSodiumMg.roundToInt()), "mg", 570f, y)
        y += 120f
        metric("Carbs", whole.format(n.carbsG.roundToInt()), "g", 72f, y)
        metric("Fat", whole.format(n.fatG.roundToInt()), "g", 570f, y)
        y += 120f
        metric("Sugar", whole.format(n.sugarG.roundToInt()), "g", 72f, y)
        metric("Fiber", whole.format(n.fiberG.roundToInt()), "g", 570f, y)
        y += 120f
        metric("Saturated fat", whole.format(n.saturatedFatG.roundToInt()), "g", 72f, y)
        metric("Source", report.nutritionSource.take(18), "", 570f, y)
        y += 104f

        if (n.extras.isNotEmpty()) {
            canvas.drawLine(72f, y, width - 72f, y, line)
            y += 58f
            text("Extra nutrients", 72f, y, 31f, accent, true)
            y += 44f
            n.extras.entries.take(8).forEach { (name, value) ->
                text("$name: $value", 72f, y, 27f, dark)
                y += 38f
            }
            if (n.extras.size > 8) {
                text("+${n.extras.size - 8} more", 72f, y, 25f, muted)
                y += 38f
            }
            y += 20f
        }

        canvas.drawLine(72f, y, width - 72f, y, line)
        y += 58f
        text("Sources", 72f, y, 31f, accent, true)
        y += 44f
        text("Burn source: ${report.burnSource}", 72f, y, 25f, muted)
        y += 36f
        text("Nutrition source: ${report.nutritionSource}", 72f, y, 25f, muted)
        y += 36f
        text("Health: ${report.health.healthConnectStatus.take(70)}", 72f, y, 23f, muted)

        if (report.manual.notes.isNotBlank()) {
            y += 64f
            canvas.drawLine(72f, y, width - 72f, y, line)
            y += 52f
            text("Notes", 72f, y, 30f, accent, true)
            y += 42f
            text(report.manual.notes.take(130), 72f, y, 26f, dark)
        }

        text("Generated locally on device. Offline nutrition database.", 72f, height - 72f, 24f, muted)
        return bitmap
    }
}

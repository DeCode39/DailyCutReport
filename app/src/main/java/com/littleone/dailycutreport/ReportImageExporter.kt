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
        val height = 1350
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

        var y = 96f
        text("Daily Cut Report", 72f, y, 52f, dark, true)
        y += 54f
        text(report.date.format(dateFmt), 72f, y, 32f, muted)
        y += 50f
        canvas.drawLine(72f, y, width - 72f, y, line)
        y += 76f

        val verdict = when {
            report.deficitCalories >= 300 -> "Cut day"
            report.deficitCalories <= -200 -> "Surplus day"
            else -> "Maintenance-ish"
        }
        text(verdict, 72f, y, 44f, accent, true)
        y += 42f
        text("Deficit = burn − intake", 72f, y, 26f, muted)
        y += 76f

        fun metric(label: String, value: String, unit: String, left: Float, top: Float) {
            text(label, left, top, 27f, muted)
            text(value, left, top + 48f, 43f, dark, true)
            text(unit, left + 230f, top + 48f, 27f, muted)
        }

        metric("Steps", whole.format(report.health.steps), "", 72f, y)
        metric("Distance", one.format(report.health.distanceKm), "km", 570f, y)
        y += 138f
        metric("Final burn", whole.format(report.finalBurnCalories.roundToInt()), "kcal", 72f, y)
        metric("Food", whole.format(report.manual.foodCalories.roundToInt()), "kcal", 570f, y)
        y += 138f
        metric("Protein", whole.format(report.manual.proteinG.roundToInt()), "g", 72f, y)
        metric("Sodium", whole.format(report.manual.sodiumMg.roundToInt()), "mg", 570f, y)
        y += 138f
        metric("Active burn", whole.format(report.health.activeCalories.roundToInt()), "kcal", 72f, y)
        metric("Exercises", "${report.health.exerciseSessions}", "${report.health.exerciseMinutes} min", 570f, y)
        y += 126f

        canvas.drawLine(72f, y, width - 72f, y, line)
        y += 80f
        text("Estimated result", 72f, y, 30f, muted)
        y += 70f
        val deficitSign = if (report.deficitCalories >= 0) "−" else "+"
        text("$deficitSign${whole.format(kotlin.math.abs(report.deficitCalories).roundToInt())} kcal", 72f, y, 68f, dark, true)
        y += 54f
        text("Burn source: ${report.burnSource}", 72f, y, 26f, muted)
        y += 54f
        text("Health: ${report.health.healthConnectStatus}", 72f, y, 24f, muted)

        if (report.manual.notes.isNotBlank()) {
            y += 72f
            canvas.drawLine(72f, y, width - 72f, y, line)
            y += 58f
            text("Notes", 72f, y, 30f, muted)
            y += 44f
            val clipped = report.manual.notes.take(120)
            text(clipped, 72f, y, 27f, dark)
        }

        text("Generated locally on device. No internet permission.", 72f, height - 72f, 24f, muted)
        return bitmap
    }
}

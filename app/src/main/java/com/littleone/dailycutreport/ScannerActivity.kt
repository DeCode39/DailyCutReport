package com.littleone.dailycutreport

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScannerActivity : ComponentActivity() {
    private enum class Mode { BARCODE, OCR }

    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var mode: Mode = Mode.BARCODE
    private var isProcessing = false
    private var pendingOcrCapture = false

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mode = if (intent.getStringExtra(EXTRA_MODE) == MODE_OCR) Mode.OCR else Mode.BARCODE
        cameraExecutor = Executors.newSingleThreadExecutor()
        buildUi()
        ensureCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(18, 24, 28))
        }
        statusText = TextView(this).apply {
            text = if (mode == Mode.BARCODE) "Point camera at barcode" else "Point camera at nutrition label"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(16, 24, 16, 24)
        }
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        root.addView(statusText)
        root.addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        if (mode == Mode.OCR) {
            root.addView(Button(this).apply {
                text = "Scan nutrition label"
                isAllCaps = false
                textSize = 16f
                setOnClickListener {
                    pendingOcrCapture = true
                    statusText.text = "Capturing OCR frame… hold steady"
                }
            })
        }
        root.addView(Button(this).apply {
            text = "Cancel"
            isAllCaps = false
            setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
        })
        setContentView(root)
    }

    private fun ensureCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy -> analyzeFrame(imageProxy) }
                }
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        if (isProcessing) {
            imageProxy.close()
            return
        }
        if (mode == Mode.OCR && !pendingOcrCapture) {
            imageProxy.close()
            return
        }

        isProcessing = true
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        when (mode) {
            Mode.BARCODE -> processBarcode(image, imageProxy)
            Mode.OCR -> {
                pendingOcrCapture = false
                processOcr(image, imageProxy)
            }
        }
    }

    private fun processBarcode(image: InputImage, imageProxy: ImageProxy) {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_QR_CODE
            )
            .build()
        val scanner = BarcodeScanning.getClient(options)
        scanner.process(image)
            .addOnSuccessListener { barcodes ->
                val value = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (!value.isNullOrBlank()) {
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_BARCODE, value))
                    finish()
                }
            }
            .addOnFailureListener {
                runOnUiThread { statusText.text = "Barcode scan failed. Try again." }
            }
            .addOnCompleteListener {
                scanner.close()
                imageProxy.close()
                isProcessing = false
            }
    }

    private fun processOcr(image: InputImage, imageProxy: ImageProxy) {
        val recognizers = listOf(
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS),
            TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build()),
            TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        )
        val pieces = mutableListOf<String>()

        fun runAt(index: Int) {
            if (index >= recognizers.size) {
                recognizers.forEach { it.close() }
                imageProxy.close()
                isProcessing = false
                val raw = pieces.joinToString("\n")
                val parsed = parseNutritionText(raw)
                setResult(Activity.RESULT_OK, Intent().apply {
                    putExtra(RESULT_OCR_RAW, raw.take(2000))
                    parsed.forEach { (key, value) -> putExtra(key, value) }
                })
                finish()
                return
            }
            recognizers[index].process(image)
                .addOnSuccessListener { result ->
                    if (result.text.isNotBlank()) pieces.add(result.text)
                }
                .addOnCompleteListener { runAt(index + 1) }
        }
        runAt(0)
    }

    private fun parseNutritionText(raw: String): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        raw.lines().forEach { line ->
            val lower = line.lowercase()
            val value = firstNumber(line) ?: return@forEach
            when {
                containsAny(lower, "energy", "calorie", "calories", "kcal", "熱量", "能量", "エネルギー", "열량") -> result[RESULT_CALORIES] = value
                containsAny(lower, "saturated", "saturates", "飽和", "饱和", "포화") -> result[RESULT_SAT_FAT] = value
                containsAny(lower, "protein", "蛋白", "たんぱく", "タンパク", "단백") -> result[RESULT_PROTEIN] = value
                containsAny(lower, "sodium", "salt equivalent", "鈉", "钠", "ナトリウム", "食塩相当量", "나트륨") -> {
                    result[RESULT_SODIUM] = if (containsAny(lower, "食塩相当量", "salt equivalent") && containsAny(lower, "g")) value * 393.4 else value
                }
                containsAny(lower, "carbohydrate", "carbs", "碳水", "炭水化物", "탄수") -> result[RESULT_CARBS] = value
                containsAny(lower, "sugar", "sugars", "糖", "糖質", "당류") -> result[RESULT_SUGAR] = value
                containsAny(lower, "fiber", "fibre", "膳食纖維", "膳食纤维", "食物繊維", "섬유") -> result[RESULT_FIBER] = value
                containsAny(lower, "fat", "脂質", "脂肪", "지방") -> result[RESULT_FAT] = value
            }
        }
        return result
    }

    private fun firstNumber(text: String): Double? {
        val match = Regex("([0-9]+(?:[.,][0-9]+)?)").find(text) ?: return null
        return match.value.replace(',', '.').toDoubleOrNull()
    }

    private fun containsAny(text: String, vararg needles: String): Boolean = needles.any { text.contains(it.lowercase()) }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_BARCODE = "barcode"
        const val MODE_OCR = "ocr"
        const val RESULT_BARCODE = "barcode"
        const val RESULT_OCR_RAW = "ocr_raw"
        const val RESULT_CALORIES = "calories"
        const val RESULT_PROTEIN = "protein"
        const val RESULT_SODIUM = "sodium"
        const val RESULT_CARBS = "carbs"
        const val RESULT_FAT = "fat"
        const val RESULT_SUGAR = "sugar"
        const val RESULT_FIBER = "fiber"
        const val RESULT_SAT_FAT = "sat_fat"
    }
}

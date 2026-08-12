package com.littleone.dailycutreport

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface BarcodeDecoder : AutoCloseable {
    fun process(image: InputImage, onSuccess: (List<Barcode>) -> Unit, onFailure: (Throwable) -> Unit, onComplete: () -> Unit)
}

class MlKitBarcodeDecoder : BarcodeDecoder {
    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(
            Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E, Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93, Barcode.FORMAT_CODABAR, Barcode.FORMAT_QR_CODE
        ).build()
    )

    override fun process(image: InputImage, onSuccess: (List<Barcode>) -> Unit, onFailure: (Throwable) -> Unit, onComplete: () -> Unit) {
        scanner.process(image).addOnSuccessListener(onSuccess).addOnFailureListener(onFailure).addOnCompleteListener { onComplete() }
    }

    override fun close() = scanner.close()
}

class BarcodeAnalyzer(
    private val decoder: BarcodeDecoder,
    private val callbackExecutor: Executor,
    private val continuous: Boolean = false,
    private val onFound: (String) -> Unit,
    private val onFailure: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {
    private val processing = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)
    @Volatile private var lastContinuousValue: String? = null
    @Volatile private var noBarcodeSinceMs: Long = 0L

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null || (!continuous && delivered.get()) || !processing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        try {
            val input = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            decoder.process(
                input,
                onSuccess = { results ->
                    val value = results.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                    if (continuous) {
                        val now = System.currentTimeMillis()
                        if (value == null) {
                            if (noBarcodeSinceMs == 0L) noBarcodeSinceMs = now
                            if (now - noBarcodeSinceMs >= BARCODE_LEAVE_INTERVAL_MS) lastContinuousValue = null
                        } else {
                            noBarcodeSinceMs = 0L
                            if (value != lastContinuousValue) {
                                lastContinuousValue = value
                                callbackExecutor.execute { onFound(value) }
                            }
                        }
                    } else if (value != null && delivered.compareAndSet(false, true)) {
                        callbackExecutor.execute { onFound(value) }
                    }
                },
                onFailure = { error -> callbackExecutor.execute { onFailure(error) } },
                onComplete = {
                    imageProxy.close()
                    processing.set(false)
                }
            )
        } catch (error: Throwable) {
            try {
                imageProxy.close()
            } finally {
                processing.set(false)
            }
            callbackExecutor.execute { onFailure(error) }
        }
    }
}

@Composable
fun BarcodeScannerScreen(
    multiAllowed: Boolean = true,
    multiEnabled: Boolean,
    queueCount: Int,
    sessionStatus: String,
    onMultiChange: (Boolean) -> Unit,
    onFound: (String) -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val decoder = remember { MlKitBarcodeDecoder() }
    var permissionGranted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var status by remember { mutableStateOf("Point the camera at a barcode") }
    var retryToken by remember { mutableStateOf(0) }
    val lastFailureLogMs = remember { AtomicLong(0L) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (!granted) {
            status = "Camera permission denied. You can return and enter the barcode manually."
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (permissionGranted) {
            val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            DisposableEffect(lifecycleOwner, retryToken, multiEnabled) {
                var provider: ProcessCameraProvider? = null
                val future = ProcessCameraProvider.getInstance(context)
                future.addListener({
                    try {
                        provider = future.get()
                        if (provider?.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) != true) {
                            status = "No rear camera is available."
                            return@addListener
                        }
                        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                        val analyzer = BarcodeAnalyzer(
                            decoder,
                            mainExecutor,
                            continuous = multiEnabled,
                            onFound = onFound,
                            onFailure = {
                                val now = System.currentTimeMillis()
                                val previous = lastFailureLogMs.get()
                                if (now - previous >= FAILURE_LOG_INTERVAL_MS && lastFailureLogMs.compareAndSet(previous, now)) {
                                    Log.e("DailyCutScanner", "Barcode decoder failed", it)
                                }
                                status = "Could not read this frame. Hold the barcode steady."
                            }
                        )
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build().also { it.setAnalyzer(cameraExecutor, analyzer) }
                        provider?.unbindAll()
                        provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    } catch (error: Throwable) {
                        Log.e("DailyCutScanner", "Camera startup failed", error)
                        status = "Camera could not start."
                    }
                }, mainExecutor)
                onDispose { provider?.unbindAll() }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color(0xCC111111)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (multiEnabled) sessionStatus else status, color = Color.White, style = MaterialTheme.typography.titleMedium)
            if (multiAllowed) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Multi-scan", color = Color.White)
                Switch(checked = multiEnabled, onCheckedChange = onMultiChange)
                if (multiEnabled) Text("$queueCount queued", color = Color.White)
            }
            if (!permissionGranted) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            } else {
                OutlinedButton(onClick = { retryToken++ }) { Text("Retry") }
            }
            if (multiEnabled) Button(onClick = onDone) { Text(if (queueCount == 0) "Done" else "Review $queueCount") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            decoder.close()
            cameraExecutor.shutdown()
        }
    }
}

private const val FAILURE_LOG_INTERVAL_MS = 5_000L
private const val BARCODE_LEAVE_INTERVAL_MS = 700L

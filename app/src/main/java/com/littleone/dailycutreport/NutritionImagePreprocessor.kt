package com.littleone.dailycutreport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

interface NutritionImagePreprocessor {
    suspend fun preview(source: Uri, rotationDegrees: Int): Bitmap
    suspend fun prepare(source: Uri, crop: CropRegion, rotationDegrees: Int): PreparedOcrImage
    suspend fun variants(prepared: Uri): List<OcrVariantImage>
    fun delete(uri: Uri)
    fun cleanup()
}

class AndroidNutritionImagePreprocessor(private val context: Context) : NutritionImagePreprocessor {
    private val directory = File(context.cacheDir, "ocr_prepared")

    override suspend fun preview(source: Uri, rotationDegrees: Int): Bitmap = withContext(Dispatchers.IO) {
        rotate(decode(source, PREVIEW_MAX_DIMENSION), rotationDegrees)
    }

    override suspend fun prepare(
        source: Uri,
        crop: CropRegion,
        rotationDegrees: Int
    ): PreparedOcrImage = withContext(Dispatchers.IO) {
        val normalizedCrop = crop.normalized()
        val rotated = rotate(decode(source, PREPARE_MAX_DIMENSION), rotationDegrees)
        val fullFrame = resize(rotated, OCR_MAX_DIMENSION)
        val fullFrameUri = write(fullFrame, "full")
        val left = (rotated.width * normalizedCrop.left).roundToInt().coerceIn(0, rotated.width - 1)
        val top = (rotated.height * normalizedCrop.top).roundToInt().coerceIn(0, rotated.height - 1)
        val right = (rotated.width * normalizedCrop.right).roundToInt().coerceIn(left + 1, rotated.width)
        val bottom = (rotated.height * normalizedCrop.bottom).roundToInt().coerceIn(top + 1, rotated.height)
        val cropped = Bitmap.createBitmap(rotated, left, top, right - left, bottom - top)
        val prepared = resize(cropped, OCR_MAX_DIMENSION)
        val quality = measureQuality(prepared)
        try {
            val uri = write(prepared, "prepared")
            PreparedOcrImage(
                source,
                uri,
                normalizeRotation(rotationDegrees),
                normalizedCrop,
                quality,
                fullFrameUri
            )
        } catch (error: Throwable) {
            delete(fullFrameUri)
            throw error
        }
    }

    override suspend fun variants(prepared: Uri): List<OcrVariantImage> = withContext(Dispatchers.IO) {
        val original = decode(prepared, OCR_MAX_DIMENSION)
        val contrast = grayscaleContrast(original)
        val sharpened = sharpen(contrast)
        listOf(
            OcrVariantImage(prepared, OcrImageVariant.ORIGINAL, temporary = false),
            OcrVariantImage(write(contrast, "contrast"), OcrImageVariant.CONTRAST, temporary = true),
            OcrVariantImage(write(sharpened, "sharpened"), OcrImageVariant.SHARPENED, temporary = true)
        )
    }

    override fun delete(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: return
        File(directory, name).takeIf { it.canonicalFile.parentFile == directory.canonicalFile }?.delete()
    }

    override fun cleanup() {
        directory.listFiles()?.forEach(File::delete)
    }

    private fun decode(uri: Uri, maximumDimension: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val largest = max(info.size.width, info.size.height)
            if (largest > maximumDimension) {
                val ratio = maximumDimension.toDouble() / largest
                decoder.setTargetSize(
                    (info.size.width * ratio).roundToInt().coerceAtLeast(1),
                    (info.size.height * ratio).roundToInt().coerceAtLeast(1)
                )
            }
        }
    }

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = normalizeRotation(degrees)
        if (normalized == 0) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply {
            postRotate(normalized.toFloat())
        }, true)
    }

    private fun resize(bitmap: Bitmap, maximumDimension: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maximumDimension) return bitmap
        val ratio = maximumDimension.toDouble() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
            (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
            true
        )
    }

    private fun measureQuality(bitmap: Bitmap): ImageQuality {
        val step = sqrt((bitmap.width.toLong() * bitmap.height / QUALITY_SAMPLE_PIXELS.toDouble()).coerceAtLeast(1.0))
            .roundToInt().coerceAtLeast(1)
        var count = 0
        var sum = 0.0
        var squareSum = 0.0
        var glare = 0
        var edgeSum = 0.0
        for (y in step until bitmap.height - step step step) {
            for (x in step until bitmap.width - step step step) {
                val center = luminance(bitmap.getPixel(x, y))
                sum += center
                squareSum += center * center
                if (center >= 245) glare++
                val laplacian = 4 * center - luminance(bitmap.getPixel(x - step, y)) -
                    luminance(bitmap.getPixel(x + step, y)) - luminance(bitmap.getPixel(x, y - step)) -
                    luminance(bitmap.getPixel(x, y + step))
                edgeSum += abs(laplacian)
                count++
            }
        }
        if (count == 0) return ImageQuality(bitmap.width, bitmap.height, 0.0, 0.0, 0.0)
        val mean = sum / count
        val contrast = sqrt((squareSum / count - mean * mean).coerceAtLeast(0.0))
        return ImageQuality(bitmap.width, bitmap.height, contrast, edgeSum / count, glare.toDouble() / count)
    }

    private fun grayscaleContrast(bitmap: Bitmap): Bitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val histogram = IntArray(256)
        var shadows = 0
        var highlights = 0
        pixels.forEach { color ->
            val value = luminance(color)
            histogram[value]++
            if (value <= 55) shadows++
            if (value >= 210) highlights++
        }
        val threshold = otsuThreshold(histogram, pixels.size)
        val invert = highlights > pixels.size / 100 && highlights > shadows * 1.25
        pixels.indices.forEach { index ->
            var value = if (luminance(pixels[index]) > threshold) 255 else 0
            if (invert) value = 255 - value
            pixels[index] = Color.rgb(value, value, value)
        }
        return Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var weightedTotal = 0.0
        histogram.indices.forEach { weightedTotal += it * histogram[it].toDouble() }
        var backgroundWeight = 0
        var backgroundSum = 0.0
        var bestVariance = -1.0
        var threshold = 127
        histogram.indices.forEach { level ->
            backgroundWeight += histogram[level]
            if (backgroundWeight == 0) return@forEach
            val foregroundWeight = total - backgroundWeight
            if (foregroundWeight == 0) return threshold
            backgroundSum += level * histogram[level].toDouble()
            val backgroundMean = backgroundSum / backgroundWeight
            val foregroundMean = (weightedTotal - backgroundSum) / foregroundWeight
            val variance = backgroundWeight.toDouble() * foregroundWeight *
                (backgroundMean - foregroundMean) * (backgroundMean - foregroundMean)
            if (variance > bestVariance) {
                bestVariance = variance
                threshold = level
            }
        }
        return threshold
    }

    private fun sharpen(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val input = IntArray(width * height)
        val output = IntArray(width * height)
        bitmap.getPixels(input, 0, width, 0, 0, width, height)
        input.copyInto(output)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                val value = (5 * Color.red(input[index]) - Color.red(input[index - 1]) -
                    Color.red(input[index + 1]) - Color.red(input[index - width]) -
                    Color.red(input[index + width])).coerceIn(0, 255)
                output[index] = Color.rgb(value, value, value)
            }
        }
        return Bitmap.createBitmap(output, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun write(bitmap: Bitmap, prefix: String): Uri {
        directory.mkdirs()
        val file = File(directory, "${prefix}_${UUID.randomUUID()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114).roundToInt()

    private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    companion object {
        private const val PREVIEW_MAX_DIMENSION = 900
        private const val PREPARE_MAX_DIMENSION = 3200
        private const val OCR_MAX_DIMENSION = 2000
        private const val QUALITY_SAMPLE_PIXELS = 65_536
    }
}

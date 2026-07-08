package com.littleone.dailycutreport

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

interface AppBackupManager {
    suspend fun export(uri: Uri, password: CharArray)
    suspend fun restore(uri: Uri, password: CharArray)
}

class EncryptedAppBackupManager(
    private val context: Context,
    private val dao: NutritionDao
) : AppBackupManager {
    override suspend fun export(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(password.size >= MIN_PASSWORD_LENGTH) { "Password must be at least $MIN_PASSWORD_LENGTH characters." }
        val payload = BackupJson.encode(
            BackupPayload(
                products = dao.allProducts(),
                productExtras = dao.allProductExtras(),
                reports = dao.allDailyReports(),
                foodLogs = dao.allFoodLogs(),
                dailyExtras = dao.allDailyExtras()
            )
        ).toByteArray(Charsets.UTF_8)
        val encrypted = BackupCrypto.encrypt(payload, password)
        context.contentResolver.openOutputStream(uri, "w")?.use { it.write(encrypted) }
            ?: error("The selected backup file could not be opened.")
    }

    override suspend fun restore(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "Enter the backup password." }
        val encrypted = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BACKUP_BYTES) { "Backup is larger than the supported limit." }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: error("The selected backup file could not be opened.")
        val plain = try {
            BackupCrypto.decrypt(encrypted, password)
        } catch (_: AEADBadTagException) {
            throw IllegalArgumentException("Wrong password or damaged backup.")
        }
        val payload = BackupJson.decode(plain.toString(Charsets.UTF_8))
        dao.replaceUserData(payload.products, payload.productExtras, payload.reports, payload.foodLogs, payload.dailyExtras)
    }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
        private const val MAX_BACKUP_BYTES = 50 * 1024 * 1024
    }
}

data class BackupPayload(
    val products: List<ProductEntity>,
    val productExtras: List<ProductExtraNutrientEntity>,
    val reports: List<DailyReportEntity>,
    val foodLogs: List<DailyFoodLogEntity>,
    val dailyExtras: List<DailyExtraNutrientLogEntity>
)

object BackupCrypto {
    private val MAGIC = "DCRBKP01".toByteArray(Charsets.US_ASCII)
    private const val FORMAT_VERSION = 1
    private const val ITERATIONS = 600_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12

    fun encrypt(plain: ByteArray, password: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(salt, nonce, ITERATIONS)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, ITERATIONS), GCMParameterSpec(128, nonce))
        cipher.updateAAD(header)
        val encrypted = cipher.doFinal(plain)
        return header + encrypted
    }

    fun decrypt(value: ByteArray, password: CharArray): ByteArray {
        val input = DataInputStream(ByteArrayInputStream(value))
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        require(magic.contentEquals(MAGIC)) { "Not a DailyCutReport backup." }
        val version = input.readInt()
        require(version == FORMAT_VERSION) { "Unsupported backup format $version." }
        val iterations = input.readInt()
        require(iterations in 100_000..1_500_000) { "Invalid backup key settings." }
        val saltLength = input.readInt()
        require(saltLength in 16..64) { "Invalid backup salt." }
        val salt = ByteArray(saltLength).also(input::readFully)
        val nonceLength = input.readInt()
        require(nonceLength == NONCE_BYTES) { "Invalid backup nonce." }
        val nonce = ByteArray(nonceLength).also(input::readFully)
        val headerLength = value.size - input.available()
        val encrypted = ByteArray(input.available()).also(input::readFully)
        require(encrypted.size >= 16) { "Backup payload is incomplete." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(128, nonce))
        cipher.updateAAD(value.copyOfRange(0, headerLength))
        return cipher.doFinal(encrypted)
    }

    private fun header(salt: ByteArray, nonce: ByteArray, iterations: Int): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(iterations)
                output.writeInt(salt.size)
                output.write(salt)
                output.writeInt(nonce.size)
                output.write(nonce)
            }
        }.toByteArray()

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}

object BackupJson {
    private const val SCHEMA_VERSION = 1

    fun encode(payload: BackupPayload): String = JSONObject().apply {
        put("schemaVersion", SCHEMA_VERSION)
        put("createdAt", System.currentTimeMillis())
        put("settings", JSONObject())
        put("products", JSONArray().apply { payload.products.forEach { put(it.toJson()) } })
        put("productExtras", JSONArray().apply { payload.productExtras.forEach { put(it.toJson()) } })
        put("dailyReports", JSONArray().apply { payload.reports.forEach { put(it.toJson()) } })
        put("foodLogs", JSONArray().apply { payload.foodLogs.forEach { put(it.toJson()) } })
        put("dailyExtras", JSONArray().apply { payload.dailyExtras.forEach { put(it.toJson()) } })
    }.toString()

    fun decode(json: String): BackupPayload {
        val root = JSONObject(json)
        require(root.getInt("schemaVersion") == SCHEMA_VERSION) { "Unsupported backup schema." }
        val products = root.getJSONArray("products").objects(::productFromJson)
        val productExtras = root.getJSONArray("productExtras").objects(::productExtraFromJson)
        val reports = root.getJSONArray("dailyReports").objects(::reportFromJson)
        val logs = root.getJSONArray("foodLogs").objects(::foodLogFromJson)
        val dailyExtras = root.getJSONArray("dailyExtras").objects(::dailyExtraFromJson)
        validate(products, productExtras, reports, logs, dailyExtras)
        return BackupPayload(products, productExtras, reports, logs, dailyExtras)
    }

    private fun validate(
        products: List<ProductEntity>, extras: List<ProductExtraNutrientEntity>, reports: List<DailyReportEntity>,
        logs: List<DailyFoodLogEntity>, dailyExtras: List<DailyExtraNutrientLogEntity>
    ) {
        require(products.map { it.productId }.toSet().size == products.size) { "Duplicate product IDs in backup." }
        require(products.mapNotNull { it.barcode }.toSet().size == products.count { it.barcode != null }) { "Duplicate barcodes in backup." }
        val productIds = products.map { it.productId }.toSet()
        require(extras.all { it.productId in productIds }) { "Backup contains an orphan product nutrient." }
        require(reports.map { it.date }.toSet().size == reports.size) { "Duplicate report dates in backup." }
        reports.forEach { LocalDate.parse(it.date) }
        val logIds = logs.map { it.id }.toSet()
        require(logIds.size == logs.size && logs.all { it.id > 0 && it.quantity > 0 }) { "Invalid food logs in backup." }
        logs.forEach { LocalDate.parse(it.date) }
        require(dailyExtras.all { it.logId in logIds }) { "Backup contains an orphan daily nutrient." }
    }

    private fun ProductEntity.toJson() = JSONObject().apply {
        put("productId", productId); putNullable("barcode", barcode); put("name", name); put("brand", brand)
        put("servingLabel", servingLabel); put("calories", calories); put("proteinG", proteinG); put("sodiumMg", sodiumMg)
        put("carbsG", carbsG); put("fatG", fatG); put("sugarG", sugarG); put("fiberG", fiberG)
        put("saturatedFatG", saturatedFatG); put("notes", notes); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    private fun ProductExtraNutrientEntity.toJson() = JSONObject().apply {
        put("productId", productId); put("name", name); put("value", value); put("unit", unit)
    }

    private fun DailyReportEntity.toJson() = JSONObject().apply {
        put("date", date); put("steps", steps); put("distanceKm", distanceKm); put("activeCalories", activeCalories)
        put("totalCalories", totalCalories); put("exerciseSessions", exerciseSessions); put("exerciseMinutes", exerciseMinutes)
        put("nutritionCalories", nutritionCalories); put("nutritionProteinG", nutritionProteinG); put("nutritionSodiumMg", nutritionSodiumMg)
        put("nutritionRecords", nutritionRecords); put("healthConnectStatus", healthConnectStatus)
        putNullable("manualFoodCalories", manualFoodCalories); putNullable("manualProteinG", manualProteinG)
        putNullable("manualSodiumMg", manualSodiumMg); putNullable("manualBurnCalories", manualBurnCalories)
        put("notes", notes); put("savedAtEpochMs", savedAtEpochMs)
    }

    private fun DailyFoodLogEntity.toJson() = JSONObject().apply {
        put("id", id); put("date", date); putNullable("productId", productId); putNullable("barcode", barcode)
        put("productName", productName); put("brand", brand); put("servingLabel", servingLabel); put("quantity", quantity)
        put("caloriesPerServing", caloriesPerServing); put("proteinGPerServing", proteinGPerServing); put("sodiumMgPerServing", sodiumMgPerServing)
        put("carbsGPerServing", carbsGPerServing); put("fatGPerServing", fatGPerServing); put("sugarGPerServing", sugarGPerServing)
        put("fiberGPerServing", fiberGPerServing); put("saturatedFatGPerServing", saturatedFatGPerServing); put("loggedAt", loggedAt)
    }

    private fun DailyExtraNutrientLogEntity.toJson() = JSONObject().apply {
        put("id", id); put("logId", logId); put("name", name); put("valuePerServing", valuePerServing); put("unit", unit)
    }

    private fun productFromJson(o: JSONObject) = ProductEntity(
        productId = o.requiredText("productId"), barcode = o.nullableText("barcode"), name = o.requiredText("name"),
        brand = o.getString("brand"), servingLabel = o.requiredText("servingLabel"), calories = o.nonNegative("calories"),
        proteinG = o.nonNegative("proteinG"), sodiumMg = o.nonNegative("sodiumMg"), carbsG = o.nonNegative("carbsG"),
        fatG = o.nonNegative("fatG"), sugarG = o.nonNegative("sugarG"), fiberG = o.nonNegative("fiberG"),
        saturatedFatG = o.nonNegative("saturatedFatG"), notes = o.getString("notes"), createdAt = o.getLong("createdAt"), updatedAt = o.getLong("updatedAt")
    )

    private fun productExtraFromJson(o: JSONObject) = ProductExtraNutrientEntity(
        o.requiredText("productId"), o.requiredText("name"), o.nonNegative("value"), o.getString("unit")
    )

    private fun reportFromJson(o: JSONObject) = DailyReportEntity(
        date = o.requiredText("date"), steps = o.getLong("steps"), distanceKm = o.nonNegative("distanceKm"),
        activeCalories = o.nonNegative("activeCalories"), totalCalories = o.nonNegative("totalCalories"),
        exerciseSessions = o.getInt("exerciseSessions"), exerciseMinutes = o.getLong("exerciseMinutes"),
        nutritionCalories = o.nonNegative("nutritionCalories"), nutritionProteinG = o.nonNegative("nutritionProteinG"),
        nutritionSodiumMg = o.nonNegative("nutritionSodiumMg"), nutritionRecords = o.getInt("nutritionRecords"),
        healthConnectStatus = o.getString("healthConnectStatus"), manualFoodCalories = o.nullableDouble("manualFoodCalories"),
        manualProteinG = o.nullableDouble("manualProteinG"), manualSodiumMg = o.nullableDouble("manualSodiumMg"),
        manualBurnCalories = o.nullableDouble("manualBurnCalories"), notes = o.getString("notes"), savedAtEpochMs = o.getLong("savedAtEpochMs")
    )

    private fun foodLogFromJson(o: JSONObject) = DailyFoodLogEntity(
        id = o.getLong("id"), date = o.requiredText("date"), productId = o.nullableText("productId"), barcode = o.nullableText("barcode"),
        productName = o.requiredText("productName"), brand = o.getString("brand"), servingLabel = o.requiredText("servingLabel"),
        quantity = o.positive("quantity"), caloriesPerServing = o.nonNegative("caloriesPerServing"), proteinGPerServing = o.nonNegative("proteinGPerServing"),
        sodiumMgPerServing = o.nonNegative("sodiumMgPerServing"), carbsGPerServing = o.nonNegative("carbsGPerServing"),
        fatGPerServing = o.nonNegative("fatGPerServing"), sugarGPerServing = o.nonNegative("sugarGPerServing"),
        fiberGPerServing = o.nonNegative("fiberGPerServing"), saturatedFatGPerServing = o.nonNegative("saturatedFatGPerServing"), loggedAt = o.getLong("loggedAt")
    )

    private fun dailyExtraFromJson(o: JSONObject) = DailyExtraNutrientLogEntity(
        id = o.getLong("id"), logId = o.getLong("logId"), name = o.requiredText("name"),
        valuePerServing = o.nonNegative("valuePerServing"), unit = o.getString("unit")
    )

    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.nullableText(key: String): String? = if (isNull(key)) null else getString(key).trim().ifBlank { null }
    private fun JSONObject.nullableDouble(key: String): Double? = if (isNull(key)) null else getDouble(key).also { require(it.isFinite() && it >= 0) }
    private fun JSONObject.requiredText(key: String): String = getString(key).trim().also { require(it.isNotBlank()) { "$key is required." } }
    private fun JSONObject.nonNegative(key: String): Double = getDouble(key).also { require(it.isFinite() && it >= 0) { "$key is invalid." } }
    private fun JSONObject.positive(key: String): Double = getDouble(key).also { require(it.isFinite() && it > 0) { "$key is invalid." } }
    private fun <T> JSONArray.objects(transform: (JSONObject) -> T): List<T> = (0 until length()).map { transform(getJSONObject(it)) }
}

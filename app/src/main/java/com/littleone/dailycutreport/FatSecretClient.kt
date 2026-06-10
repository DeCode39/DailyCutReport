package com.littleone.dailycutreport

import android.content.Context
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class FatSecretClient(context: Context) {
    private val prefs = context.getSharedPreferences("fatsecret_oauth", Context.MODE_PRIVATE)

    data class DiaryImport(
        val calories: Double,
        val proteinG: Double,
        val sodiumMg: Double,
        val entries: Int
    )

    fun saveConsumerCredentials(key: String, secret: String) {
        prefs.edit()
            .putString("consumer_key", key.trim())
            .putString("consumer_secret", secret.trim())
            .apply()
    }

    fun hasConsumerCredentials(): Boolean =
        !prefs.getString("consumer_key", "").isNullOrBlank() &&
            !prefs.getString("consumer_secret", "").isNullOrBlank()

    fun hasAccessToken(): Boolean =
        !prefs.getString("access_token", "").isNullOrBlank() &&
            !prefs.getString("access_token_secret", "").isNullOrBlank()

    fun status(): String = when {
        !hasConsumerCredentials() -> "Credentials not saved"
        !hasAccessToken() -> "Credentials saved; authorization needed"
        else -> "Authorized"
    }

    suspend fun startAuthorization(): String = withContext(Dispatchers.IO) {
        val key = consumerKey()
        val secret = consumerSecret()
        val baseUrl = "https://authentication.fatsecret.com/oauth/request_token"
        val oauth = oauthParams(key).toMutableMap().apply {
            put("oauth_callback", "oob")
        }
        val signed = signParams("POST", baseUrl, oauth, secret, tokenSecret = "")
        val response = httpRequest("POST", baseUrl, signed)
        val parsed = parseForm(response)
        val requestToken = parsed["oauth_token"] ?: error("FatSecret did not return oauth_token: $response")
        val requestSecret = parsed["oauth_token_secret"] ?: error("FatSecret did not return oauth_token_secret: $response")
        prefs.edit()
            .putString("request_token", requestToken)
            .putString("request_token_secret", requestSecret)
            .apply()
        "https://authentication.fatsecret.com/oauth/authorize?oauth_token=${percent(requestToken)}"
    }

    suspend fun completeAuthorization(verifier: String) = withContext(Dispatchers.IO) {
        val key = consumerKey()
        val secret = consumerSecret()
        val requestToken = prefs.getString("request_token", null) ?: error("No pending request token. Start authorization first.")
        val requestSecret = prefs.getString("request_token_secret", null) ?: error("No pending request token secret. Start authorization first.")
        val baseUrl = "https://authentication.fatsecret.com/oauth/access_token"
        val params = oauthParams(key, requestToken).toMutableMap().apply {
            put("oauth_verifier", verifier.trim())
        }
        val signed = signParams("GET", baseUrl, params, secret, requestSecret)
        val response = httpRequest("GET", baseUrl, signed)
        val parsed = parseForm(response)
        val accessToken = parsed["oauth_token"] ?: error("FatSecret did not return access token: $response")
        val accessSecret = parsed["oauth_token_secret"] ?: error("FatSecret did not return access token secret: $response")
        prefs.edit()
            .putString("access_token", accessToken)
            .putString("access_token_secret", accessSecret)
            .remove("request_token")
            .remove("request_token_secret")
            .apply()
    }

    suspend fun importFoodDiary(date: LocalDate): DiaryImport = withContext(Dispatchers.IO) {
        val key = consumerKey()
        val secret = consumerSecret()
        val token = prefs.getString("access_token", null) ?: error("FatSecret is not authorized yet.")
        val tokenSecret = prefs.getString("access_token_secret", null) ?: error("FatSecret access token secret is missing.")
        val baseUrl = "https://platform.fatsecret.com/rest/server.api"
        val params = oauthParams(key, token).toMutableMap().apply {
            put("method", "food_entries.get.v2")
            put("date", date.toEpochDay().toString())
            put("format", "json")
        }
        val signed = signParams("GET", baseUrl, params, secret, tokenSecret)
        val response = httpRequest("GET", baseUrl, signed)
        parseDiary(response)
    }

    private fun consumerKey(): String = prefs.getString("consumer_key", null)?.takeIf { it.isNotBlank() }
        ?: error("FatSecret Consumer Key is missing.")

    private fun consumerSecret(): String = prefs.getString("consumer_secret", null)?.takeIf { it.isNotBlank() }
        ?: error("FatSecret Consumer Secret is missing.")

    private fun oauthParams(key: String, token: String? = null): Map<String, String> {
        val params = linkedMapOf(
            "oauth_consumer_key" to key,
            "oauth_nonce" to UUID.randomUUID().toString().replace("-", ""),
            "oauth_signature_method" to "HMAC-SHA1",
            "oauth_timestamp" to (System.currentTimeMillis() / 1000L).toString(),
            "oauth_version" to "1.0"
        )
        if (!token.isNullOrBlank()) params["oauth_token"] = token
        return params
    }

    private fun signParams(
        method: String,
        baseUrl: String,
        params: Map<String, String>,
        consumerSecret: String,
        tokenSecret: String
    ): Map<String, String> {
        val normalized = params.entries
            .sortedWith(compareBy({ percent(it.key) }, { percent(it.value) }))
            .joinToString("&") { "${percent(it.key)}=${percent(it.value)}" }
        val base = listOf(method.uppercase(), percent(baseUrl), percent(normalized)).joinToString("&")
        val signingKey = "${percent(consumerSecret)}&${percent(tokenSecret)}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(signingKey.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val signature = Base64.encodeToString(mac.doFinal(base.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return params + ("oauth_signature" to signature)
    }

    private fun httpRequest(method: String, baseUrl: String, params: Map<String, String>): String {
        val query = params.entries
            .sortedWith(compareBy({ it.key }, { it.value }))
            .joinToString("&") { "${percent(it.key)}=${percent(it.value)}" }
        val url = URL("$baseUrl?$query")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method.uppercase()
            connectTimeout = 20000
            readTimeout = 20000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            if (requestMethod == "POST") doOutput = true
        }
        try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}: $body")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private fun parseForm(body: String): Map<String, String> = body.split("&")
        .mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else {
                val k = Uri.decode(part.substring(0, idx))
                val v = Uri.decode(part.substring(idx + 1))
                k to v
            }
        }.toMap()

    private fun parseDiary(body: String): DiaryImport {
        val root = JSONObject(body)
        if (root.has("error")) error(root.getJSONObject("error").toString())
        val foodEntries = root.optJSONObject("food_entries") ?: return DiaryImport(0.0, 0.0, 0.0, 0)
        val rawEntry = foodEntries.opt("food_entry") ?: return DiaryImport(0.0, 0.0, 0.0, 0)
        val entries: JSONArray = when (rawEntry) {
            is JSONArray -> rawEntry
            is JSONObject -> JSONArray().put(rawEntry)
            else -> JSONArray()
        }
        var calories = 0.0
        var protein = 0.0
        var sodium = 0.0
        for (i in 0 until entries.length()) {
            val entry = entries.optJSONObject(i) ?: continue
            calories += entry.optString("calories", "0").toDoubleOrNull() ?: 0.0
            protein += entry.optString("protein", "0").toDoubleOrNull() ?: 0.0
            sodium += entry.optString("sodium", "0").toDoubleOrNull() ?: 0.0
        }
        return DiaryImport(calories, protein, sodium, entries.length())
    }

    private fun percent(value: String): String = URLEncoder.encode(value, "UTF-8")
        .replace("+", "%20")
        .replace("*", "%2A")
        .replace("%7E", "~")
}
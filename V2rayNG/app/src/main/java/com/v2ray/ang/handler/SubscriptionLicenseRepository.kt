package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.RemoteConfig
import com.v2ray.ang.util.LogUtil
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Owns the PARSCLOUD entitlement key and its server validation.
 *
 * The record is kept as one MMKV value so key and expiry are written atomically.
 * The key is never written to logs. A successful cached expiry is used only to stop
 * an existing connection after its known expiry when the validation server is offline;
 * a new connection always requires a successful online validation.
 */
object SubscriptionLicenseRepository {
    private const val STORAGE_KEY = "PARSCLOUD_SUBSCRIPTION_LICENSE"
    private val keyPattern = Regex("^pc_[a-f0-9]{64}$")
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    data class StoredLicense(val key: String, val expiresAtMillis: Long)

    internal fun isValidKeyFormat(key: String): Boolean = keyPattern.matches(key)

    sealed class ValidationResult {
        data class Active(val expiresAtMillis: Long) : ValidationResult()
        data class Rejected(val status: String) : ValidationResult()
        data object NetworkError : ValidationResult()
    }

    fun getStoredLicense(): StoredLicense? {
        val raw = MmkvManager.decodeSettingsString(STORAGE_KEY).orEmpty()
        if (raw.isBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val key = json.optString("key").takeIf(::isValidKeyFormat) ?: return null
        val expiry = json.optLong("expiresAtMillis", 0L)
        if (expiry <= 0L) return null
        return StoredLicense(key, expiry)
    }

    fun saveActiveLicense(key: String, expiresAtMillis: Long): Boolean {
        if (!isValidKeyFormat(key) || expiresAtMillis <= System.currentTimeMillis()) return false
        val value = JSONObject()
            .put("key", key)
            .put("expiresAtMillis", expiresAtMillis)
            .toString()
        return MmkvManager.encodeSettings(STORAGE_KEY, value)
    }

    fun isExpired(license: StoredLicense, nowMillis: Long = System.currentTimeMillis()): Boolean =
        license.expiresAtMillis <= nowMillis

    fun validate(key: String): ValidationResult {
        if (!isValidKeyFormat(key)) return ValidationResult.Rejected("invalid")

        val requestBody = JSONObject()
            .put("subscriptionKey", key)
            .toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(RemoteConfig.SUBSCRIPTION_VALIDATE_URL)
            .post(requestBody)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                parseValidationResponse(response.code, body, System.currentTimeMillis())
            }
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "SubscriptionLicenseRepository: validation network request failed", e)
            ValidationResult.NetworkError
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "SubscriptionLicenseRepository: validation response failed", e)
            ValidationResult.NetworkError
        }
    }

    internal fun parseValidationResponse(
        httpCode: Int,
        responseBody: String,
        nowMillis: Long,
    ): ValidationResult {
        if (httpCode !in 200..299) {
            if (httpCode !in 400..499) return ValidationResult.NetworkError
            val status = runCatching { JSONObject(responseBody).optString("status", "invalid") }
                .getOrDefault("invalid")
            return ValidationResult.Rejected(status)
        }

        val json = runCatching { JSONObject(responseBody) }.getOrNull()
            ?: return ValidationResult.NetworkError
        val status = json.optString("status", "invalid")
        if (!json.optBoolean("valid", false) || status != "active") {
            return ValidationResult.Rejected(status)
        }

        val expiry = runCatching {
            Instant.parse(json.getString("expiresAt")).toEpochMilli()
        }.getOrNull() ?: return ValidationResult.NetworkError

        if (expiry <= nowMillis) return ValidationResult.Rejected("expired")
        return ValidationResult.Active(expiry)
    }
}

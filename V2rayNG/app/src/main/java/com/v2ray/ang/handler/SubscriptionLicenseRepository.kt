package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.RemoteConfig
import com.v2ray.ang.util.LogUtil
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Owns the PARSCLOUD entitlement key and its server validation.
 *
 * The entitlement record is encrypted with an AES-256-GCM key held by Android
 * Keystore. MMKV stores only the authenticated ciphertext and expiry metadata.
 * A legacy plaintext record is migrated on first successful read.
 */
object SubscriptionLicenseRepository {
    private const val STORAGE_KEY = "PARSCLOUD_SUBSCRIPTION_LICENSE"
    private const val KEY_ALIAS = "parscloud.subscription.aes.v1"
    private const val ENCRYPTED_PREFIX = "enc:v1:"
    private const val GCM_TAG_BITS = 128
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

    private fun getOrCreateEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptRecord(plainText: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateEncryptionKey())
        val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$ENCRYPTED_PREFIX$iv:$payload"
    }

    private fun decryptRecord(value: String): String {
        require(value.startsWith(ENCRYPTED_PREFIX)) { "Unsupported encrypted license format" }
        val parts = value.removePrefix(ENCRYPTED_PREFIX).split(':', limit = 2)
        require(parts.size == 2) { "Malformed encrypted license record" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val payload = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateEncryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return String(cipher.doFinal(payload), StandardCharsets.UTF_8)
    }

    private fun parseStoredRecord(raw: String): StoredLicense? {
        val json = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull() ?: return null
        val key = runCatching {
            json.get("key")?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()?.takeIf(::isValidKeyFormat) ?: return null
        val expiry = runCatching {
            json.get("expiresAtMillis")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        }.getOrDefault(0L)
        if (expiry <= 0L) return null
        return StoredLicense(key, expiry)
    }

    fun getStoredLicense(): StoredLicense? {
        val stored = MmkvManager.decodeSettingsString(STORAGE_KEY).orEmpty()
        if (stored.isBlank()) return null
        val plainText = if (stored.startsWith(ENCRYPTED_PREFIX)) {
            runCatching { decryptRecord(stored) }.getOrElse {
                LogUtil.e(AppConfig.TAG, "SubscriptionLicenseRepository: encrypted license could not be opened", it)
                return null
            }
        } else {
            // Migrate records created by earlier app versions. Do not keep using or
            // retaining plaintext if the encrypted replacement cannot be persisted.
            stored
        }
        val license = parseStoredRecord(plainText) ?: return null
        if (!stored.startsWith(ENCRYPTED_PREFIX) && !saveActiveLicense(license.key, license.expiresAtMillis)) {
            MmkvManager.encodeSettings(STORAGE_KEY, null)
            LogUtil.e(AppConfig.TAG, "SubscriptionLicenseRepository: legacy license migration failed")
            return null
        }
        return license
    }

    fun saveActiveLicense(key: String, expiresAtMillis: Long): Boolean {
        if (!isValidKeyFormat(key) || expiresAtMillis <= System.currentTimeMillis()) return false
        val value = JsonObject().apply {
            addProperty("key", key)
            addProperty("expiresAtMillis", expiresAtMillis)
        }.toString()
        return runCatching {
            MmkvManager.encodeSettings(STORAGE_KEY, encryptRecord(value))
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "SubscriptionLicenseRepository: encrypted license could not be saved", it)
        }.getOrDefault(false)
    }

    fun isExpired(license: StoredLicense, nowMillis: Long = System.currentTimeMillis()): Boolean =
        license.expiresAtMillis <= nowMillis

    fun validate(key: String): ValidationResult {
        if (!isValidKeyFormat(key)) return ValidationResult.Rejected("invalid")

        val requestBody = JsonObject().apply {
            addProperty("subscriptionKey", key)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
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
        val rejectedStatuses = setOf("invalid", "expired", "revoked")
        if (httpCode !in 200..299) {
            if (httpCode !in 400..499) return ValidationResult.NetworkError
            val status = runCatching {
                JsonParser.parseString(responseBody).asJsonObject
                    .get("status")?.takeUnless { it.isJsonNull }?.asString
            }.getOrNull()
            return if (status != null && status in rejectedStatuses) {
                ValidationResult.Rejected(status)
            } else {
                ValidationResult.NetworkError
            }
        }

        val json = runCatching { JsonParser.parseString(responseBody).asJsonObject }.getOrNull()
            ?: return ValidationResult.NetworkError
        val status = json.get("status")?.takeUnless { it.isJsonNull }?.asString
            ?: return ValidationResult.NetworkError
        val valid = json.get("valid")?.takeUnless { it.isJsonNull }?.asBoolean
            ?: return ValidationResult.NetworkError
        if (!valid && status in rejectedStatuses) {
            return ValidationResult.Rejected(status)
        }
        if (!valid || status != "active") {
            return ValidationResult.NetworkError
        }

        val expiry = runCatching {
            Instant.parse(json.get("expiresAt").asString).toEpochMilli()
        }.getOrNull() ?: return ValidationResult.NetworkError

        if (expiry <= nowMillis) return ValidationResult.Rejected("expired")
        return ValidationResult.Active(expiry)
    }
}

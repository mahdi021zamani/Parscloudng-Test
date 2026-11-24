package com.v2ray.ang.handler

import com.v2ray.ang.AppConfig
import com.v2ray.ang.RemoteConfig
import com.v2ray.ang.util.LogUtil
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.os.SystemClock
import java.io.File
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
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
    private const val DEVICE_KEY_ALIAS = "parscloud.subscription.device.ec.v1"
    private const val ENCRYPTED_PREFIX = "enc:v1:"
    private const val GCM_TAG_BITS = 128
    private val keyPattern = Regex("^pc_[a-f0-9]{64}$")
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
        .build()

    data class StoredLicense(
        val key: String,
        val expiresAtMillis: Long,
        val serverTimeMillis: Long,
        val validatedAtElapsedRealtime: Long,
        val bootId: String?,
    )

    internal fun isValidKeyFormat(key: String): Boolean = keyPattern.matches(key)

    sealed class ValidationResult {
        data class Active(val expiresAtMillis: Long, val serverTimeMillis: Long) : ValidationResult()
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
        val serverTime = runCatching {
            json.get("serverTimeMillis")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        }.getOrDefault(0L)
        val elapsed = runCatching {
            json.get("validatedAtElapsedRealtime")?.takeIf { !it.isJsonNull }?.asLong ?: 0L
        }.getOrDefault(0L)
        val boot = runCatching {
            json.get("bootId")?.takeIf { !it.isJsonNull }?.asString
        }.getOrNull()
        return StoredLicense(key, expiry, serverTime, elapsed, boot)
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
        if (!stored.startsWith(ENCRYPTED_PREFIX)) {
            // Encrypt legacy records without inventing trusted server-time metadata.
            // Such records remain readable, but cannot grant offline access until
            // the next successful online validation refreshes their metadata.
            val migrated = runCatching {
                MmkvManager.encodeSettings(STORAGE_KEY, encryptRecord(plainText))
            }.getOrDefault(false)
            if (!migrated) {
                MmkvManager.encodeSettings(STORAGE_KEY, null)
                LogUtil.e(AppConfig.TAG, "SubscriptionLicenseRepository: legacy license migration failed")
                return null
            }
        }
        return license
    }

    fun saveActiveLicense(key: String, expiresAtMillis: Long, serverTimeMillis: Long): Boolean {
        if (!isValidKeyFormat(key) || serverTimeMillis <= 0L || expiresAtMillis <= serverTimeMillis) return false
        val value = JsonObject().apply {
            addProperty("key", key)
            addProperty("expiresAtMillis", expiresAtMillis)
            addProperty("serverTimeMillis", serverTimeMillis)
            addProperty("validatedAtElapsedRealtime", SystemClock.elapsedRealtime())
            currentBootId()?.let { addProperty("bootId", it) }
        }.toString()
        return runCatching {
            MmkvManager.encodeSettings(STORAGE_KEY, encryptRecord(value))
        }.onFailure {
            LogUtil.e(AppConfig.TAG, "SubscriptionLicenseRepository: encrypted license could not be saved", it)
        }.getOrDefault(false)
    }

    /** True only while a locally stored entitlement has a provable offline grant. */
    fun hasActiveLocalLicense(): Boolean {
        val license = getStoredLicense() ?: return false
        return hasOfflineAccess(license)
    }

    internal fun currentBootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * Offline access is granted only while the device can prove it is still in
     * the same boot session as the last successful server validation.
     * Wall-clock time is deliberately not consulted.
     */
    internal fun hasOfflineAccess(
        license: StoredLicense,
        nowElapsedRealtime: Long = SystemClock.elapsedRealtime(),
        currentBootId: String? = currentBootId(),
    ): Boolean {
        if (license.serverTimeMillis <= 0L ||
            license.validatedAtElapsedRealtime <= 0L ||
            license.bootId.isNullOrBlank() ||
            currentBootId.isNullOrBlank() ||
            license.bootId != currentBootId
        ) return false

        val elapsedSinceValidation = nowElapsedRealtime - license.validatedAtElapsedRealtime
        if (elapsedSinceValidation < 0L || elapsedSinceValidation >= OFFLINE_GRACE_MILLIS) return false
        val estimatedServerTime = license.serverTimeMillis + elapsedSinceValidation
        return estimatedServerTime < license.expiresAtMillis
    }

    const val OFFLINE_GRACE_MILLIS = 24L * 60L * 60L * 1000L

    private fun getOrCreateDeviceKeyPair(): Pair<PrivateKey, PublicKey> {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getEntry(DEVICE_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return existing.privateKey to existing.certificate.publicKey

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(DEVICE_KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        val pair = generator.generateKeyPair()
        return pair.private to pair.public
    }

    private fun signDeviceChallenge(challenge: String): Pair<String, String> {
        val (privateKey, publicKey) = getOrCreateDeviceKeyPair()
        val publicKeyEncoded = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(challenge.toByteArray(StandardCharsets.UTF_8))
        val signature = Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
        return publicKeyEncoded to signature
    }

    private fun postJson(url: String, body: JsonObject): Pair<Int, String> {
        val requestBody = body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).post(requestBody).build()
        return client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
    }

    fun validate(key: String): ValidationResult {
        if (!isValidKeyFormat(key)) return ValidationResult.Rejected("invalid")

        return try {
            val (publicKey, _) = signDeviceChallenge("keypair-check")
            val challengeBody = JsonObject().apply {
                addProperty("subscriptionKey", key)
                addProperty("devicePublicKey", publicKey)
            }
            val (challengeCode, challengeResponse) = postJson(
                RemoteConfig.SUBSCRIPTION_DEVICE_CHALLENGE_URL,
                challengeBody,
            )
            if (challengeCode !in 200..299) {
                return parseValidationResponse(challengeCode, challengeResponse, System.currentTimeMillis())
            }

            val challengeJson = runCatching { JsonParser.parseString(challengeResponse).asJsonObject }.getOrNull()
                ?: return ValidationResult.NetworkError
            val challengeId = challengeJson.get("challengeId")?.takeUnless { it.isJsonNull }?.asString
                ?: return ValidationResult.NetworkError
            val challenge = challengeJson.get("challenge")?.takeUnless { it.isJsonNull }?.asString
                ?: return ValidationResult.NetworkError
            val (_, signature) = signDeviceChallenge(challenge)

            val validationBody = JsonObject().apply {
                addProperty("subscriptionKey", key)
                addProperty("devicePublicKey", publicKey)
                addProperty("challengeId", challengeId)
                addProperty("signature", signature)
            }
            val (validationCode, validationResponse) = postJson(
                RemoteConfig.SUBSCRIPTION_VALIDATE_URL,
                validationBody,
            )
            parseValidationResponse(validationCode, validationResponse, System.currentTimeMillis())
        } catch (e: IOException) {
            LogUtil.e(AppConfig.TAG, "SubscriptionLicenseRepository: device validation network request failed", e)
            ValidationResult.NetworkError
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "SubscriptionLicenseRepository: device validation failed", e)
            ValidationResult.NetworkError
        }
    }

    internal fun parseValidationResponse(
        httpCode: Int,
        responseBody: String,
        nowMillis: Long,
    ): ValidationResult {
        val rejectedStatuses = setOf("invalid", "expired", "revoked", "device_limit", "device_revoked", "device_required")
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
        val serverTime = runCatching {
            Instant.parse(json.get("serverTime").asString).toEpochMilli()
        }.getOrNull() ?: return ValidationResult.NetworkError

        if (expiry <= serverTime) return ValidationResult.Rejected("expired")
        return ValidationResult.Active(expiry, serverTime)
    }
}

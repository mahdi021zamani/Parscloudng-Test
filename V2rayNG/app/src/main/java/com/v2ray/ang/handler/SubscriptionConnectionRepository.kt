package com.v2ray.ang.handler

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.v2ray.ang.AppConfig
import com.v2ray.ang.RemoteConfig
import com.v2ray.ang.util.LogUtil
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CancellationException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the server-side lease for this app installation.
 *
 * The installation ID is stable across process/service restarts, while the
 * subscription key is kept only in memory here and in the existing app-private license store. Never log either value.
 */
object SubscriptionConnectionRepository {
    private const val SESSION_ID_STORAGE_KEY = "PARSCLOUD_CONNECTION_SESSION_ID"
    private val sessionIdPattern = Regex("^[a-f0-9-]{36}$")
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val sessionMutex = Mutex()

    @Volatile
    private var activeSubscriptionKey: String? = null

    @Volatile
    private var lastSuccessfulLeaseAtMillis: Long = 0L

    enum class SessionResult {
        ACTIVE,
        LIMIT_REACHED,
        REJECTED,
        NETWORK_ERROR,
    }

    fun lastSuccessfulLeaseAtMillis(): Long = lastSuccessfulLeaseAtMillis

    /** Claims this installation's slot before the local proxy/VPN core starts. */
    suspend fun acquire(): SessionResult = sessionMutex.withLock {
        val license = SubscriptionLicenseRepository.getStoredLicense()
            ?: return@withLock SessionResult.REJECTED
        val result = requestSessionAction("acquire", license.key)
        if (result == SessionResult.ACTIVE) {
            activeSubscriptionKey = license.key
            lastSuccessfulLeaseAtMillis = System.currentTimeMillis()
        }
        result
    }

    suspend fun heartbeat(): SessionResult = sessionMutex.withLock {
        val key = activeSubscriptionKey
            ?: SubscriptionLicenseRepository.getStoredLicense()?.key
            ?: return@withLock SessionResult.REJECTED
        val result = requestSessionAction("heartbeat", key)
        if (result == SessionResult.ACTIVE) {
            activeSubscriptionKey = key
            lastSuccessfulLeaseAtMillis = System.currentTimeMillis()
        }
        result
    }

    /**
     * Releases the lease on a normal stop. If the process is killed, the server's
     * 90-second lease expiry is the fallback cleanup mechanism.
     */
    suspend fun release(): Boolean = sessionMutex.withLock {
        val key = activeSubscriptionKey ?: return@withLock true
        try {
            val sessionId = getOrCreateSessionId() ?: return@withLock false
            val body = JsonObject().apply {
                addProperty("sessionId", sessionId)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${RemoteConfig.SUBSCRIPTION_API_BASE_URL}/v1/session/release")
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            execute(request).use { response ->
                val responseBody = response.body?.string().orEmpty()
                val released = if (response.isSuccessful) {
                    runCatching {
                        JsonParser.parseString(responseBody).asJsonObject
                            .get("released")?.asBoolean == true
                    }.getOrDefault(false)
                } else false
                if (!released) {
                    LogUtil.w(AppConfig.TAG, "Subscription session: server did not confirm release; lease expiry will clean up")
                }
                released
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LogUtil.w(AppConfig.TAG, "Subscription session: release request failed; lease expiry will clean up", e)
            false
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Subscription session: release response failed", e)
            false
        } finally {
            activeSubscriptionKey = null
            lastSuccessfulLeaseAtMillis = 0L
        }
    }

    private suspend fun requestSessionAction(action: String, key: String): SessionResult {
        return try {
            val sessionId = getOrCreateSessionId() ?: return SessionResult.NETWORK_ERROR
            val body = JsonObject().apply {
                addProperty("sessionId", sessionId)
            }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("${RemoteConfig.SUBSCRIPTION_API_BASE_URL}/v1/session/$action")
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            execute(request).use { response ->
                parseSessionResponse(response.code, response.body?.string().orEmpty())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            LogUtil.w(AppConfig.TAG, "Subscription session: $action request failed", e)
            SessionResult.NETWORK_ERROR
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Subscription session: $action response failed", e)
            SessionResult.NETWORK_ERROR
        }
    }

    /** Cancellable OkHttp bridge so a stopped service can cancel pending requests. */
    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        })
    }

    internal fun parseSessionResponse(httpCode: Int, responseBody: String): SessionResult {
        if (httpCode == 429) return SessionResult.LIMIT_REACHED
        if (httpCode == 401 || httpCode == 403) return SessionResult.REJECTED
        if (httpCode !in 200..299) return SessionResult.NETWORK_ERROR

        val acquired = runCatching {
            JsonParser.parseString(responseBody).asJsonObject
                .get("acquired")?.asBoolean
        }.getOrNull()

        return when (acquired) {
            true -> SessionResult.ACTIVE
            false -> SessionResult.LIMIT_REACHED
            null -> SessionResult.NETWORK_ERROR
        }
    }

    private fun getOrCreateSessionId(): String? {
        val existing = MmkvManager.decodeSettingsString(SESSION_ID_STORAGE_KEY).orEmpty()
        if (sessionIdPattern.matches(existing)) return existing

        val created = UUID.randomUUID().toString()
        if (!MmkvManager.encodeSettings(SESSION_ID_STORAGE_KEY, created)) {
            LogUtil.w(AppConfig.TAG, "Subscription session: unable to persist installation session ID")
            return null
        }
        return created
    }
}

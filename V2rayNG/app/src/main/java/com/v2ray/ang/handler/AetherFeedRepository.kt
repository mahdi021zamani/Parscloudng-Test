package com.v2ray.ang.handler

import com.v2ray.ang.RemoteConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Downloads the private Aether feed through the PARSCLOUD Worker.
 * The entitlement key is sent only as an Authorization header and is never logged.
 */
object AetherFeedRepository {
    sealed class Result {
        data class Success(val content: String) : Result()
        data object LicenseMissing : Result()
        data object LicenseExpired : Result()
        data object Unauthorized : Result()
        data class HttpError(val code: Int) : Result()
        data object NetworkError : Result()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): Result = withContext(Dispatchers.IO) {
        val license = SubscriptionLicenseRepository.getStoredLicense()
            ?: return@withContext Result.LicenseMissing
        if (SubscriptionLicenseRepository.isExpired(license)) {
            return@withContext Result.LicenseExpired
        }

        val request = Request.Builder()
            .url(RemoteConfig.AETHER_SERVER_LIST_URL)
            .header("Authorization", "Bearer ${license.key}")
            .header("Accept", "text/plain")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 401 || response.code == 403 -> Result.Unauthorized
                    !response.isSuccessful -> Result.HttpError(response.code)
                    else -> Result.Success(response.body?.string().orEmpty())
                }
            }
        } catch (_: IOException) {
            Result.NetworkError
        } catch (_: Exception) {
            Result.NetworkError
        }
    }
}

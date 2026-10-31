package com.v2ray.ang.handler

import com.v2ray.ang.RemoteConfig
import com.v2ray.ang.AppConfig
import com.v2ray.ang.util.LogUtil
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
            ?: return@withContext Result.LicenseMissing.also {
                LogUtil.w(AppConfig.TAG, "Aether sync skipped: no stored subscription license")
            }
        if (SubscriptionLicenseRepository.isExpired(license)) {
            LogUtil.w(AppConfig.TAG, "Aether sync skipped: stored subscription license is expired")
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
                    response.code == 401 || response.code == 403 -> {
                        LogUtil.w(AppConfig.TAG, "Aether feed request rejected by server: HTTP ${response.code}")
                        Result.Unauthorized
                    }
                    !response.isSuccessful -> {
                        LogUtil.w(AppConfig.TAG, "Aether feed request failed: HTTP ${response.code}")
                        Result.HttpError(response.code)
                    }
                    else -> {
                        val content = response.body?.string().orEmpty()
                        LogUtil.w(AppConfig.TAG, "Aether feed downloaded: HTTP ${response.code}, ${content.length} characters, ${content.lineSequence().count { it.isNotBlank() }} non-empty lines")
                        Result.Success(content)
                    }
                }
            }
        } catch (error: IOException) {
            LogUtil.w(AppConfig.TAG, "Aether feed network error: ${error.javaClass.simpleName}")
            Result.NetworkError
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "Aether feed request error: ${error.javaClass.simpleName}")
            Result.NetworkError
        }
    }
}

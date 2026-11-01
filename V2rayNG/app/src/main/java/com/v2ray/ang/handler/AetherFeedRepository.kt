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
 * Downloads the public Aether feed directly from GitHub.
 *
 * Customer subscription licensing remains independently validated by
 * SubscriptionLicenseRepository; no customer key or GitHub credential is sent
 * with this public feed request.
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
        val request = Request.Builder()
            .url(RemoteConfig.AETHER_SERVER_LIST_URL)
            .header("Accept", "text/plain")
            .header("Cache-Control", "no-cache")
            .get()
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when {
                    !response.isSuccessful -> {
                        LogUtil.w(AppConfig.TAG, "Aether public feed request failed: HTTP ${response.code}")
                        Result.HttpError(response.code)
                    }
                    else -> {
                        val content = response.body?.string().orEmpty()
                        LogUtil.i(
                            AppConfig.TAG,
                            "Aether public feed downloaded: HTTP ${response.code}, ${content.length} characters, ${content.lineSequence().count { it.isNotBlank() }} non-empty lines"
                        )
                        Result.Success(content)
                    }
                }
            }
        } catch (error: IOException) {
            LogUtil.w(AppConfig.TAG, "Aether public feed network error: ${error.javaClass.simpleName}")
            Result.NetworkError
        } catch (error: Exception) {
            LogUtil.w(AppConfig.TAG, "Aether public feed request error: ${error.javaClass.simpleName}")
            Result.NetworkError
        }
    }
}

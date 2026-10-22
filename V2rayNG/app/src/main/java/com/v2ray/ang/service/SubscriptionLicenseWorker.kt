package com.v2ray.ang.service

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.handler.SubscriptionLicenseRepository
import java.util.concurrent.TimeUnit

/**
 * Periodically revalidates the saved PARSCLOUD key so a revoked subscription can
 * stop an already-running VPN even when the launcher screen is not visible.
 *
 * WorkManager's periodic interval is best-effort (minimum 15 minutes), not an exact
 * timer. The app also validates online before each new connection.
 */
class SubscriptionLicenseWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        val license = SubscriptionLicenseRepository.getStoredLicense() ?: return Result.success()

        return when (val result = SubscriptionLicenseRepository.validate(license.key)) {
            is SubscriptionLicenseRepository.ValidationResult.Active -> {
                if (SubscriptionLicenseRepository.saveActiveLicense(license.key, result.expiresAtMillis)) {
                    Result.success()
                } else {
                    Result.retry()
                }
            }

            is SubscriptionLicenseRepository.ValidationResult.Rejected -> {
                LauncherManager.stopService(applicationContext)
                Result.success()
            }

            SubscriptionLicenseRepository.ValidationResult.NetworkError -> {
                if (SubscriptionLicenseRepository.isExpired(license)) {
                    LauncherManager.stopService(applicationContext)
                }
                Result.retry()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "parscloud-subscription-license-monitor"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SubscriptionLicenseWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}

package com.v2ray.ang.service

import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.receiver.SubscriptionRevocationReceiver
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.handler.SubscriptionLicenseRepository
import java.util.concurrent.TimeUnit

/**
 * Revalidates the saved PARSCLOUD key. The periodic check is a fallback; network
 * availability callbacks also enqueue an immediate check while the VPN service runs.
 */
class SubscriptionLicenseWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        LogUtil.w(AppConfig.TAG, "Subscription monitor: worker started; runAttemptCount=$runAttemptCount")
        val license = SubscriptionLicenseRepository.getStoredLicense()
        if (license == null) {
            LogUtil.w(AppConfig.TAG, "Subscription monitor: worker has no stored license; nothing to validate")
            return Result.success()
        }
        LogUtil.w(AppConfig.TAG, "Subscription monitor: stored license found; validating with server")

        return when (val result = SubscriptionLicenseRepository.validate(license.key)) {
            is SubscriptionLicenseRepository.ValidationResult.Active -> {
                LogUtil.w(AppConfig.TAG, "Subscription monitor: server validation ACTIVE")
                if (SubscriptionLicenseRepository.saveActiveLicense(license.key, result.expiresAtMillis)) {
                    LogUtil.w(AppConfig.TAG, "Subscription monitor: active license expiry saved")
                    Result.success()
                } else {
                    LogUtil.w(AppConfig.TAG, "Subscription monitor: failed to save active license; retrying")
                    Result.retry()
                }
            }

            is SubscriptionLicenseRepository.ValidationResult.Rejected -> {
                LogUtil.w(AppConfig.TAG, "Subscription monitor: server rejected saved license; requesting stop through service message channel")
                // Use the same MessageHelper/service-control path as the app's Disconnect
                // button. A manifest receiver in the daemon process can be started in a
                // fresh process where CoreServiceManager.serviceControl is still null.
                LauncherManager.stopService(applicationContext)
                LogUtil.w(AppConfig.TAG, "Subscription monitor: normal service stop request sent")

                // Keep the daemon-side receiver as a second, independent stop attempt.
                val stopIntent = Intent(SubscriptionRevocationReceiver.ACTION_STOP_REVOKED_SUBSCRIPTION)
                    .setPackage(AppConfig.ANG_PACKAGE)
                applicationContext.sendBroadcast(stopIntent)
                LogUtil.w(AppConfig.TAG, "Subscription monitor: fallback revocation broadcast sent")
                Result.success()
            }

            SubscriptionLicenseRepository.ValidationResult.NetworkError -> {
                LogUtil.w(AppConfig.TAG, "Subscription monitor: validation ended with NETWORK_ERROR")
                if (SubscriptionLicenseRepository.isExpired(license)) {
                    LogUtil.w(AppConfig.TAG, "Subscription monitor: saved license is expired during network error; requesting service stop")
                    LauncherManager.stopService(applicationContext)
                }
                Result.retry()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "parscloud-subscription-license-monitor"
        private const val RECONNECT_WORK_NAME = "parscloud-subscription-license-reconnect-check"

        fun schedule(context: Context) {
            LogUtil.w(AppConfig.TAG, "Subscription monitor: scheduling periodic validation")
            val request = PeriodicWorkRequestBuilder<SubscriptionLicenseWorker>(
                15, TimeUnit.MINUTES
            ).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * Queue a prompt revalidation after Android reports network availability.
         * WorkManager waits for a usable network and retries temporary failures.
         */
        fun checkAfterNetworkAvailable(context: Context) {
            LogUtil.w(AppConfig.TAG, "Subscription monitor: network callback requested one-time validation")
            val request = OneTimeWorkRequestBuilder<SubscriptionLicenseWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    10,
                    TimeUnit.SECONDS,
                )
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                RECONNECT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}

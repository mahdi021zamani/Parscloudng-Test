package com.v2ray.ang.ui.settings

import android.app.Application
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.root.RootManager
import com.v2ray.ang.handler.SubscriptionLicenseRepository
import com.v2ray.ang.ui.base.BaseViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SettingsViewModel(application: Application) : BaseViewModel(application) {

    /** Validates and securely stores a customer subscription key. */
    fun activateSubscription(rawKey: String) {
        val key = rawKey.trim()
        if (key.isEmpty()) {
            toastError(R.string.simple_license_key_required)
            return
        }
        launchLoading {
            when (val result = withContext(Dispatchers.IO) {
                SubscriptionLicenseRepository.validate(key)
            }) {
                is SubscriptionLicenseRepository.ValidationResult.Active -> {
                    val saved = withContext(Dispatchers.IO) {
                        SubscriptionLicenseRepository.saveActiveLicense(key, result.expiresAtMillis)
                    }
                    if (saved) toastSuccess(R.string.simple_license_active)
                    else toastError(R.string.simple_license_network_error)
                }
                is SubscriptionLicenseRepository.ValidationResult.Rejected ->
                    toastError(R.string.simple_license_invalid)
                SubscriptionLicenseRepository.ValidationResult.NetworkError ->
                    toastError(R.string.simple_license_network_error)
            }
        }
    }

    /**
     * Checks for root access and requests it if necessary.
     * Updates [isLoading] during the process.
     */
    fun checkAndRequestRoot(onSuccess: () -> Unit) {
        launchLoading {
            val hasRoot = withContext(Dispatchers.IO) {
                RootManager.refresh()
            }
            if (hasRoot) {
                onSuccess()
            } else {
                toastError(R.string.toast_root_required)
            }
        }
    }

    /**
     * Validates if the given string is a valid observatory duration.
     * Shows error toast if invalid.
     * @return The trimmed value if valid, null otherwise.
     */
    fun validateObservatoryDuration(value: String): String? {
        val duration = value.trim()
        return if (AppConfig.OBSERVATORY_DURATION_PATTERN.matches(duration)) {
            duration
        } else {
            toastError(R.string.toast_invalid_observatory_duration)
            null
        }
    }

    /**
     * Validates if the given string is a valid observatory sampling value.
     * Shows error toast if invalid.
     * @return The value if valid, null otherwise.
     */
    fun validateObservatorySampling(value: String): String? {
        val sampling = value.trim().toIntOrNull()?.takeIf { it > 0 }
        return if (sampling != null) {
            sampling.toString()
        } else {
            toastError(R.string.toast_invalid_observatory_sampling)
            null
        }
    }
}
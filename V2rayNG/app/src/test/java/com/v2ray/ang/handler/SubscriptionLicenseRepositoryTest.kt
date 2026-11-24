package com.v2ray.ang.handler

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionLicenseRepositoryTest {
    private val serverNow = 1_800_000_000_000L

    @Test
    fun activeResponseWithFutureExpiryAndServerTimeIsAccepted() {
        val expiry = Instant.ofEpochMilli(serverNow + 86_400_000L).toString()
        val serverTime = Instant.ofEpochMilli(serverNow).toString()
        val body = """{"valid":true,"status":"active","expiresAt":"$expiry","serverTime":"$serverTime"}"""

        val result = SubscriptionLicenseRepository.parseValidationResponse(200, body, 1L)

        assertEquals(
            SubscriptionLicenseRepository.ValidationResult.Active(serverNow + 86_400_000L, serverNow),
            result,
        )
    }

    @Test
    fun rejectedResponseIsNotAccepted() {
        val result = SubscriptionLicenseRepository.parseValidationResponse(
            200,
            """{"valid":false,"status":"revoked"}""",
            serverNow,
        )

        assertEquals(SubscriptionLicenseRepository.ValidationResult.Rejected("revoked"), result)
    }

    @Test
    fun expiryIsComparedWithServerTimeNotPhoneClock() {
        val expiry = Instant.ofEpochMilli(serverNow - 1L).toString()
        val serverTime = Instant.ofEpochMilli(serverNow).toString()
        val body = """{"valid":true,"status":"active","expiresAt":"$expiry","serverTime":"$serverTime"}"""

        val result = SubscriptionLicenseRepository.parseValidationResponse(200, body, 0L)

        assertEquals(SubscriptionLicenseRepository.ValidationResult.Rejected("expired"), result)
    }

    @Test
    fun missingServerTimeIsNotTrusted() {
        val expiry = Instant.ofEpochMilli(serverNow + 86_400_000L).toString()
        val body = """{"valid":true,"status":"active","expiresAt":"$expiry"}"""

        val result = SubscriptionLicenseRepository.parseValidationResponse(200, body, serverNow)

        assertEquals(SubscriptionLicenseRepository.ValidationResult.NetworkError, result)
    }

    @Test
    fun offlineGrantIsValidOnlyWithinConfiguredGraceOnSameBoot() {
        val license = offlineLicense()
        val grace = SubscriptionLicenseRepository.OFFLINE_GRACE_MILLIS
        assertEquals(
            true,
            SubscriptionLicenseRepository.hasOfflineAccess(
                license,
                nowElapsedRealtime = 10_000L + grace - 1L,
                currentBootId = "boot-a",
            ),
        )
        assertEquals(
            false,
            SubscriptionLicenseRepository.hasOfflineAccess(
                license,
                nowElapsedRealtime = 10_000L + grace,
                currentBootId = "boot-a",
            ),
        )
    }

    @Test
    fun offlineGrantCannotBeReusedAfterRebootOrMonotonicClockReset() {
        val license = offlineLicense()
        assertEquals(
            false,
            SubscriptionLicenseRepository.hasOfflineAccess(
                license,
                nowElapsedRealtime = 20_000L,
                currentBootId = "boot-b",
            ),
        )
        assertEquals(
            false,
            SubscriptionLicenseRepository.hasOfflineAccess(
                license,
                nowElapsedRealtime = 5_000L,
                currentBootId = "boot-a",
            ),
        )
    }

    @Test
    fun offlineGrantCannotOutliveSubscriptionExpiry() {
        val license = offlineLicense(expiresAt = serverNow + 30L * 60L * 1000L)
        assertEquals(
            false,
            SubscriptionLicenseRepository.hasOfflineAccess(
                license,
                nowElapsedRealtime = 10_000L + 31L * 60L * 1000L,
                currentBootId = "boot-a",
            ),
        )
    }

    @Test
    fun deviceLimitResponseIsRejected() {
        val result = SubscriptionLicenseRepository.parseValidationResponse(
            403,
            """{"valid":false,"status":"device_limit","deviceCount":2,"maxDevices":2}""",
            serverNow,
        )
        assertEquals(SubscriptionLicenseRepository.ValidationResult.Rejected("device_limit"), result)
    }

    @Test
    fun removedDeviceResponseIsRejected() {
        val result = SubscriptionLicenseRepository.parseValidationResponse(
            403,
            """{"valid":false,"status":"device_revoked"}""",
            serverNow,
        )
        assertEquals(SubscriptionLicenseRepository.ValidationResult.Rejected("device_revoked"), result)
    }

    @Test
    fun serverFailureIsTreatedAsNetworkError() {
        val result = SubscriptionLicenseRepository.parseValidationResponse(
            503,
            """{"error":"temporarily unavailable"}""",
            serverNow,
        )

        assertEquals(SubscriptionLicenseRepository.ValidationResult.NetworkError, result)
    }

    @Test
    fun emptyOrMalformedKeysAreRejectedByFormatCheck() {
        assertEquals(false, SubscriptionLicenseRepository.isValidKeyFormat(""))
        assertEquals(false, SubscriptionLicenseRepository.isValidKeyFormat("pc_not-a-real-key"))
        assertEquals(false, SubscriptionLicenseRepository.isValidKeyFormat("  "))
    }

    @Test
    fun malformedSuccessBodyIsTreatedAsNetworkError() {
        val result = SubscriptionLicenseRepository.parseValidationResponse(200, "not-json", serverNow)

        assertEquals(SubscriptionLicenseRepository.ValidationResult.NetworkError, result)
    }

    private fun offlineLicense(expiresAt: Long = serverNow + 48L * 60L * 60L * 1000L) =
        SubscriptionLicenseRepository.StoredLicense(
            key = "pc_" + "a".repeat(64),
            expiresAtMillis = expiresAt,
            serverTimeMillis = serverNow,
            validatedAtElapsedRealtime = 10_000L,
            bootId = "boot-a",
        )
}

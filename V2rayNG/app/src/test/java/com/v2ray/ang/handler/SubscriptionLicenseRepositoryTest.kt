package com.v2ray.ang.handler

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionLicenseRepositoryTest {
    private val now = 1_800_000_000_000L

    @Test
    fun activeResponseWithFutureExpiryIsAccepted() {
        val expiry = Instant.ofEpochMilli(now + 86_400_000L).toString()
        val body = """{"valid":true,"status":"active","expiresAt":"$expiry"}"""

        val result = SubscriptionLicenseRepository.parseValidationResponse(200, body, now)

        assertEquals(
            SubscriptionLicenseRepository.ValidationResult.Active(now + 86_400_000L),
            result,
        )
    }

    @Test
    fun rejectedResponseIsNotAccepted() {
        val result = SubscriptionLicenseRepository.parseValidationResponse(
            200,
            """{"valid":false,"status":"revoked"}""",
            now,
        )

        assertEquals(SubscriptionLicenseRepository.ValidationResult.Rejected("revoked"), result)
    }

    @Test
    fun activeResponseWithPastExpiryIsRejected() {
        val expiry = Instant.ofEpochMilli(now - 1L).toString()
        val body = """{"valid":true,"status":"active","expiresAt":"$expiry"}"""

        val result = SubscriptionLicenseRepository.parseValidationResponse(200, body, now)

        assertEquals(SubscriptionLicenseRepository.ValidationResult.Rejected("expired"), result)
    }

    @Test
    fun serverFailureIsTreatedAsNetworkError() {
        val result = SubscriptionLicenseRepository.parseValidationResponse(
            503,
            """{"error":"temporarily unavailable"}""",
            now,
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
        val result = SubscriptionLicenseRepository.parseValidationResponse(200, "not-json", now)

        assertEquals(SubscriptionLicenseRepository.ValidationResult.NetworkError, result)
    }

}

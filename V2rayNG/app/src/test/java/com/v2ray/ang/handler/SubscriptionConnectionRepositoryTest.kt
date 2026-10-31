package com.v2ray.ang.handler

import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionConnectionRepositoryTest {

    @Test
    fun successfulAcquireResponseIsAccepted() {
        assertEquals(
            SubscriptionConnectionRepository.SessionResult.ACTIVE,
            SubscriptionConnectionRepository.parseSessionResponse(
                200,
                """{"acquired":true,"limit":2,"activeConnections":1,"leaseSeconds":90}""",
            ),
        )
    }

    @Test
    fun fullConnectionLimitIsRejected() {
        assertEquals(
            SubscriptionConnectionRepository.SessionResult.LIMIT_REACHED,
            SubscriptionConnectionRepository.parseSessionResponse(
                429,
                """{"acquired":false,"limit":2,"activeConnections":2,"message":"Connection limit reached"}""",
            ),
        )
    }

    @Test
    fun unsuccessfulAcquireBodyIsRejectedEvenWithSuccessStatus() {
        assertEquals(
            SubscriptionConnectionRepository.SessionResult.LIMIT_REACHED,
            SubscriptionConnectionRepository.parseSessionResponse(
                200,
                """{"acquired":false,"limit":2,"activeConnections":2}""",
            ),
        )
    }

    @Test
    fun invalidKeyIsRejected() {
        assertEquals(
            SubscriptionConnectionRepository.SessionResult.REJECTED,
            SubscriptionConnectionRepository.parseSessionResponse(401, """{"error":"unauthorized"}"""),
        )
    }

    @Test
    fun malformedSuccessBodyIsNetworkError() {
        assertEquals(
            SubscriptionConnectionRepository.SessionResult.NETWORK_ERROR,
            SubscriptionConnectionRepository.parseSessionResponse(200, "not-json"),
        )
    }

    @Test
    fun serverErrorIsNetworkError() {
        assertEquals(
            SubscriptionConnectionRepository.SessionResult.NETWORK_ERROR,
            SubscriptionConnectionRepository.parseSessionResponse(503, """{"error":"unavailable"}"""),
        )
    }
}

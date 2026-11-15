package com.v2ray.ang.ui.simple

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimpleVpnSessionEventTest {

    @Test
    fun acceptsExpectedSessionAfterDisconnectClearsActiveOwner() {
        assertTrue(shouldAcceptSimpleVpnSessionEvent(5L, 0L, 5L))
    }

    @Test
    fun acceptsCurrentOwnerWhileNewUserStartIsQueued() {
        assertTrue(shouldAcceptSimpleVpnSessionEvent(7L, 5L, 5L))
        assertTrue(shouldAcceptSimpleVpnSessionEvent(7L, 7L, 7L))
    }

    @Test
    fun rejectsLateStopFromOldOwnerAfterNewOwnerStarts() {
        assertFalse(shouldAcceptSimpleVpnSessionEvent(7L, 7L, 5L))
    }

    @Test
    fun rejectsUnscopedStatusWhileAnySessionIsKnown() {
        assertFalse(shouldAcceptSimpleVpnSessionEvent(5L, 0L, 0L))
        assertFalse(shouldAcceptSimpleVpnSessionEvent(null, 7L, 0L))
    }

    @Test
    fun acceptsInitialUnscopedNotRunningWhenNoSessionExists() {
        assertTrue(shouldAcceptSimpleVpnSessionEvent(null, 0L, 0L))
    }
}

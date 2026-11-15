package com.v2ray.ang.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServiceSessionGateTest {

    @Test
    fun stopOldSessionCancelsPendingSystemRecovery() {
        val gate = VpnServiceSessionGate()
        assertEquals(
            VpnServiceSessionGate.StartDecision.START_NOW,
            gate.requestStart(user(5), alwaysOnEnabled = true),
        )
        gate.beginTeardown(VpnServiceSessionGate.TeardownCause.INTERNAL)

        assertEquals(
            VpnServiceSessionGate.StartDecision.QUEUED,
            gate.requestStart(system(6), alwaysOnEnabled = true),
        )
        assertEquals(5L, gate.pending?.recoversSession)

        assertEquals(VpnServiceSessionGate.StopDecision.CANCEL_PENDING, gate.requestStop(5))
        assertNull(gate.pending)
        assertEquals(VpnServiceSessionGate.TeardownCause.USER_STOP, gate.teardownCause)
        assertNull(gate.finishTeardown())
        assertNull(gate.owner)
    }

    @Test
    fun stopOldSessionStillCancelsSystemRecoveryAfterPromotion() {
        val gate = VpnServiceSessionGate()
        gate.requestStart(user(5), alwaysOnEnabled = true)
        gate.beginTeardown(VpnServiceSessionGate.TeardownCause.INTERNAL)
        gate.requestStart(system(6), alwaysOnEnabled = true)

        val promoted = gate.finishTeardown()
        assertEquals(6L, promoted?.sessionId)
        assertEquals(5L, promoted?.recoversSession)

        assertEquals(VpnServiceSessionGate.StopDecision.STOP_OWNER, gate.requestStop(5))
        gate.beginTeardown(VpnServiceSessionGate.TeardownCause.USER_STOP)
        assertNull(gate.finishTeardown())
        assertNull(gate.owner)
    }

    @Test
    fun lateStopDoesNotAffectDifferentUserOwner() {
        val gate = VpnServiceSessionGate()
        gate.requestStart(user(7), alwaysOnEnabled = true)

        assertEquals(VpnServiceSessionGate.StopDecision.IGNORED, gate.requestStop(5))
        assertEquals(7L, gate.owner?.sessionId)
    }

    @Test
    fun lateStopDoesNotCancelDifferentPendingUserStart() {
        val gate = VpnServiceSessionGate()
        gate.requestStart(user(5), alwaysOnEnabled = true)
        gate.beginTeardown(VpnServiceSessionGate.TeardownCause.INTERNAL)
        gate.requestStart(system(6), alwaysOnEnabled = true)
        gate.requestStart(user(7), alwaysOnEnabled = true)

        assertEquals(7L, gate.pending?.sessionId)
        assertEquals(VpnServiceSessionGate.StopDecision.STOP_OWNER, gate.requestStop(5))
        assertEquals(7L, gate.pending?.sessionId)
        assertEquals(7L, gate.finishTeardown()?.sessionId)
    }

    @Test
    fun nullStickyRecoveryDoesNotReplaceNewerPendingUserStart() {
        val gate = VpnServiceSessionGate()
        gate.requestStart(user(5), alwaysOnEnabled = true)
        gate.beginTeardown(VpnServiceSessionGate.TeardownCause.INTERNAL)
        gate.requestStart(user(7), alwaysOnEnabled = true)

        val sticky = VpnServiceSessionGate.Start(
            sessionId = 5L,
            startId = 8,
            origin = VpnServiceSessionGate.Origin.USER,
            stickyRecovery = true,
        )
        assertEquals(
            VpnServiceSessionGate.StartDecision.IGNORED,
            gate.requestStart(sticky, alwaysOnEnabled = true),
        )
        assertEquals(7L, gate.pending?.sessionId)
        assertEquals(7L, gate.finishTeardown()?.sessionId)
    }

    @Test
    fun explicitStopCancelsQueuedUserStartWithoutStoppingOldOwnerDirectly() {
        val gate = VpnServiceSessionGate()
        gate.requestStart(user(5), alwaysOnEnabled = true)
        gate.beginTeardown(VpnServiceSessionGate.TeardownCause.INTERNAL)
        gate.requestStart(user(7), alwaysOnEnabled = true)

        assertEquals(VpnServiceSessionGate.StopDecision.CANCEL_PENDING, gate.requestStop(7))
        assertNull(gate.pending)
        assertEquals(VpnServiceSessionGate.TeardownCause.USER_STOP, gate.teardownCause)
        assertNull(gate.finishTeardown())
    }

    @Test
    fun alwaysOnCannotQueueDuringUserStopTeardown() {
        val gate = VpnServiceSessionGate()
        gate.requestStart(user(5), alwaysOnEnabled = true)
        gate.beginTeardown(VpnServiceSessionGate.TeardownCause.USER_STOP)

        assertEquals(
            VpnServiceSessionGate.StartDecision.IGNORED,
            gate.requestStart(system(6), alwaysOnEnabled = true),
        )
        assertNull(gate.pending)
    }

    @Test
    fun systemRecoveryRequiresAlwaysOnAndKeepsExactRecoveryLink() {
        val gate = VpnServiceSessionGate()
        gate.requestStart(user(5), alwaysOnEnabled = true)
        gate.beginTeardown(VpnServiceSessionGate.TeardownCause.INTERNAL)

        assertEquals(
            VpnServiceSessionGate.StartDecision.IGNORED,
            gate.requestStart(system(6), alwaysOnEnabled = false),
        )
        assertEquals(
            VpnServiceSessionGate.StartDecision.QUEUED,
            gate.requestStart(system(7), alwaysOnEnabled = true),
        )
        assertTrue(gate.pending?.recoversSession == 5L)
    }

    private fun user(id: Long) = VpnServiceSessionGate.Start(
        sessionId = id,
        startId = id.toInt(),
        origin = VpnServiceSessionGate.Origin.USER,
    )

    private fun system(id: Long) = VpnServiceSessionGate.Start(
        sessionId = id,
        startId = id.toInt(),
        origin = VpnServiceSessionGate.Origin.SYSTEM,
    )
}

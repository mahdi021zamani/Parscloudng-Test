package com.v2ray.ang.service

/**
 * Small, deterministic state machine for serializing service starts across teardown.
 *
 * System starts are special: they may only recover the exact session whose teardown
 * created them. That relationship survives promotion from pending to owner.
 */
internal class VpnServiceSessionGate {

    internal enum class Origin { USER, SYSTEM }
    internal enum class TeardownCause { INTERNAL, USER_STOP }
    internal enum class StartDecision { START_NOW, QUEUED, IGNORED }
    internal enum class StopDecision { STOP_OWNER, CANCEL_PENDING, IGNORED }

    internal data class Start(
        val sessionId: Long,
        val startId: Int,
        val origin: Origin,
        val recoversSession: Long? = null,
    )

    var owner: Start? = null
        private set
    var pending: Start? = null
        private set
    var teardownSession: Long? = null
        private set
    var teardownCause: TeardownCause? = null
        private set

    fun requestStart(request: Start, alwaysOnEnabled: Boolean): StartDecision {
        if (request.sessionId <= 0L) return StartDecision.IGNORED

        if (teardownSession != null) {
            if (request.origin == Origin.SYSTEM) {
                if (!alwaysOnEnabled || teardownCause == TeardownCause.USER_STOP) {
                    return StartDecision.IGNORED
                }
                val recovering = teardownSession ?: return StartDecision.IGNORED
                pending = request.copy(recoversSession = recovering)
                return StartDecision.QUEUED
            }

            // A deliberate user start supersedes a system recovery, but not the
            // teardown itself. It is started only after teardown has completed.
            pending = request.copy(recoversSession = null)
            return StartDecision.QUEUED
        }

        if (owner != null) return StartDecision.IGNORED
        owner = request.copy(recoversSession = null)
        return StartDecision.START_NOW
    }

    fun beginTeardown(cause: TeardownCause) {
        if (teardownSession == null) {
            teardownSession = owner?.let {
                if (it.origin == Origin.SYSTEM && it.recoversSession != null) it.recoversSession
                else it.sessionId
            }
        }
        teardownCause = if (cause == TeardownCause.USER_STOP) {
            TeardownCause.USER_STOP
        } else {
            teardownCause ?: TeardownCause.INTERNAL
        }
    }

    fun requestStop(sessionId: Long): StopDecision {
        if (sessionId <= 0L) return StopDecision.IGNORED

        val queued = pending
        if (queued?.origin == Origin.SYSTEM && queued.recoversSession == sessionId) {
            pending = null
            teardownCause = TeardownCause.USER_STOP
            return StopDecision.CANCEL_PENDING
        }

        val current = owner
        if (current != null &&
            (current.sessionId == sessionId ||
                (current.origin == Origin.SYSTEM && current.recoversSession == sessionId))
        ) {
            if (teardownSession == null) {
                teardownSession = if (current.origin == Origin.SYSTEM && current.recoversSession != null) {
                    current.recoversSession
                } else {
                    current.sessionId
                }
            }
            owner = null
            teardownCause = TeardownCause.USER_STOP
            return StopDecision.STOP_OWNER
        }

        return StopDecision.IGNORED
    }

    /**
     * Completes teardown and promotes only a still-valid pending request.
     * The promoted system owner retains recoversSession for a late STOP.
     */
    fun finishTeardown(): Start? {
        val previousTeardownSession = teardownSession
        val previousCause = teardownCause
        val queued = pending
        pending = null
        teardownSession = null
        teardownCause = null

        val next = when {
            queued == null -> null
            queued.origin == Origin.USER -> queued.copy(recoversSession = null)
            previousCause != TeardownCause.USER_STOP &&
                queued.recoversSession != null &&
                queued.recoversSession == previousTeardownSession -> queued
            else -> null
        }
        owner = next
        return next
    }

    fun clearOwner() {
        owner = null
    }
}

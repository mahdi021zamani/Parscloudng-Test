package com.v2ray.ang.core

/**
 * Private intent contract for starts initiated by PARSCLOUD itself.
 *
 * Android's VpnService.SERVICE_INTERFACE start has no application extras. Never
 * classify a null START_STICKY restart as an Always-on request.
 */
internal object ServiceSessionContract {
    const val EXTRA_INTERNAL_START = "com.parscloud.vpn.extra.INTERNAL_SERVICE_START"
    const val EXTRA_SESSION_ID = "com.parscloud.vpn.extra.SERVICE_SESSION_ID"
    const val EXTRA_STOP_SESSION_ID = "com.parscloud.vpn.extra.STOP_SESSION_ID"
}

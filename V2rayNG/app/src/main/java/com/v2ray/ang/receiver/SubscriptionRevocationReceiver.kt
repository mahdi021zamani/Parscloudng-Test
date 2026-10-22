package com.v2ray.ang.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.v2ray.ang.AppConfig
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.util.LogUtil

/**
 * Runs in the VPN daemon process and delivers subscription revocations directly
 * to the active service controller.
 */
class SubscriptionRevocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_STOP_REVOKED_SUBSCRIPTION) return
        LogUtil.w(AppConfig.TAG, "Subscription monitor: revocation stop broadcast received in daemon process")
        val stopped = CoreServiceManager.stopActiveServiceForRevokedLicense()
        LogUtil.w(AppConfig.TAG, "Subscription monitor: stop request handled=$stopped")
    }

    companion object {
        const val ACTION_STOP_REVOKED_SUBSCRIPTION =
            "${AppConfig.ANG_PACKAGE}.action.STOP_REVOKED_SUBSCRIPTION"
    }
}

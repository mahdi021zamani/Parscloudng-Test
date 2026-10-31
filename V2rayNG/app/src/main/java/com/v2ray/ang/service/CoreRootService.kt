package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.SoftReference

/**
 * Foreground service for the root (system-wide) run modes. Unlike [CoreVpnService] it
 * does not use Android VpnService — traffic is routed by iptables instead
 * (see [RootProxyManager]).
 *
 * The in-process core is started first (so its listener is up and the foreground
 * notification is posted promptly), then the root routing rules are installed off the
 * main thread. On teardown the rules are removed before the core stops.
 */
class CoreRootService : Service(), ServiceControl {

    private var setupJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: command received")

        if (CoreServiceManager.isRunning() || setupJob?.isActive == true) {
            LogUtil.i(AppConfig.TAG, "StartCore-Root: Core is already running or starting")
            return START_STICKY
        }

        // Acquire the subscription lease and start the core off the Android main thread.
        setupJob = CoroutineScope(Dispatchers.IO).launch {
            if (!CoreServiceManager.startCoreLoop(null)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: core failed to start")
                withContext(Dispatchers.Main) { stopService() }
                return@launch
            }
            if (!RootProxyManager.start(this@CoreRootService)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode, stopping")
                withContext(Dispatchers.Main) { stopService() }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel setup immediately, then perform ordered teardown off the main thread.
        // This avoids blocking Android's main thread and avoids joining this same setup
        // job when it calls stopService() after a failed start.
        val pendingSetup = setupJob
        setupJob = null
        pendingSetup?.cancel()

        CoroutineScope(Dispatchers.IO).launch {
            pendingSetup?.join()
            // Remove routing rules BEFORE stopping the core so traffic is never redirected
            // to a dead listener.
            RootProxyManager.stop(this@CoreRootService)
            CoreServiceManager.stopCoreLoopAndWait()
        }
    }

    override fun getService(): Service = this

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        stopSelf()
    }

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }
}

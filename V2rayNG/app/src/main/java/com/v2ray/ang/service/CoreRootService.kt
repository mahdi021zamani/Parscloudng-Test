package com.v2ray.ang.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.v2ray.ang.AppConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.ServiceSessionContract
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.root.RootProxyManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    @Volatile private var activeSessionId: Long = 0L

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Root: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
        CoreServiceManager.registerServiceReceiver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val internalStart = intent?.getBooleanExtra(ServiceSessionContract.EXTRA_INTERNAL_START, false) == true
        val stickyRecovery = intent == null
        val sessionId = when {
            internalStart -> intent?.getLongExtra(ServiceSessionContract.EXTRA_SESSION_ID, 0L) ?: 0L
            stickyRecovery -> MmkvManager.getActiveServiceSession()
            else -> 0L
        }
        if ((!internalStart && !stickyRecovery) || sessionId <= 0L || MmkvManager.isServiceSessionRetired(sessionId)) {
            LogUtil.w(AppConfig.TAG, "StartCore-Root: rejecting unmarked or retired start")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        NotificationManager.ensureForeground()
        if (CoreServiceManager.isRunning()) {
            if (activeSessionId > 0L && activeSessionId != sessionId) {
                MmkvManager.retireServiceSession(sessionId)
                MmkvManager.setActiveServiceSession(activeSessionId)
            } else if (activeSessionId == 0L) {
                if (!MmkvManager.setActiveServiceSession(sessionId)) {
                    LogUtil.w(AppConfig.TAG, "StartCore: session retired before owner recovery")
                    return START_NOT_STICKY
                }
                activeSessionId = sessionId
            }
            CoreServiceManager.reportCurrentStateToUi()
            LogUtil.i(AppConfig.TAG, "StartCore-Root: Core is already running")
            return START_STICKY
        }
        if (!MmkvManager.setActiveServiceSession(sessionId)) {
            LogUtil.w(AppConfig.TAG, "StartCore: session retired before service start")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        activeSessionId = sessionId
        LogUtil.i(AppConfig.TAG, "StartCore-Root: command received")

        // Start the in-process core first (this also posts the foreground notification),
        // then install the root routing off the main thread.
        if (!CoreServiceManager.startCoreLoop(null)) {
            LogUtil.e(AppConfig.TAG, "StartCore-Root: core failed to start")
            stopService()
            return START_NOT_STICKY
        }

        setupJob = CoroutineScope(Dispatchers.IO).launch {
            if (!RootProxyManager.start(this@CoreRootService)) {
                LogUtil.e(AppConfig.TAG, "StartCore-Root: failed to start root mode, stopping")
                stopService()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        CoreServiceManager.unregisterServiceReceiver(this)
        super.onDestroy()
        // Wait for any in-flight async setup to finish before tearing down. The rules are
        // installed off the main thread and can take seconds (the setup script waits for the
        // tun to appear); if a stop arrives during that window, teardown would run first and
        // the setup would then re-install the rules + tun pointing at a now-dead core,
        // blackholing all traffic until the next start/stop cycle clears it.
        runBlocking { setupJob?.cancelAndJoin() }
        // Remove routing rules BEFORE stopping the core so traffic is never redirected
        // to a dead listener. Synchronous on purpose — leaving rules behind breaks the net.
        RootProxyManager.stop(this)
        CoreServiceManager.stopCoreLoop()
    }

    override fun getService(): Service = this

    override fun startService() {
        // do nothing
    }

    override fun stopService() {
        if (activeSessionId > 0L) MmkvManager.retireServiceSession(activeSessionId)
        stopSelf()
    }

    override fun stopService(sessionId: Long?) {
        if (sessionId == null || sessionId != activeSessionId) {
            LogUtil.i(AppConfig.TAG, "StartCore-Root: ignoring stale STOP session=$sessionId")
            return
        }
        MmkvManager.retireServiceSession(sessionId)
        stopSelf()
    }

    override val serviceSessionId: Long
        get() = activeSessionId

    override fun vpnProtect(socket: Int): Boolean = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }
}

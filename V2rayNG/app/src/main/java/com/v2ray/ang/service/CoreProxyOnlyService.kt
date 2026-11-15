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
import com.v2ray.ang.util.LogUtil
import java.lang.ref.SoftReference

class CoreProxyOnlyService : Service(), ServiceControl {

    @Volatile private var activeSessionId: Long = 0L
    /**
     * Initializes the service.
     */
    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service created")
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    /**
     * Handles the start command for the service.
     * @param intent The intent.
     * @param flags The flags.
     * @param startId The start ID.
     * @return The start mode.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val internalStart = intent?.getBooleanExtra(ServiceSessionContract.EXTRA_INTERNAL_START, false) == true
        val sessionId = intent?.getLongExtra(ServiceSessionContract.EXTRA_SESSION_ID, 0L) ?: 0L
        if (!internalStart || sessionId <= 0L || MmkvManager.isServiceSessionRetired(sessionId)) {
            LogUtil.w(AppConfig.TAG, "StartCore-Proxy: rejecting unmarked or retired start")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        NotificationManager.ensureForeground()
        if (CoreServiceManager.isRunning()) {
            if (activeSessionId > 0L && activeSessionId != sessionId) {
                MmkvManager.retireServiceSession(sessionId)
                MmkvManager.setActiveServiceSession(activeSessionId)
            } else if (activeSessionId == 0L) {
                activeSessionId = sessionId
                MmkvManager.setActiveServiceSession(sessionId)
            }
            LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Core is already running")
            return START_STICKY
        }
        activeSessionId = sessionId
        MmkvManager.setActiveServiceSession(sessionId)
        LogUtil.i(AppConfig.TAG, "StartCore-Proxy: Service command received")

        if (!CoreServiceManager.startCoreLoop(null)) {
            LogUtil.e(AppConfig.TAG, "StartCore-Proxy: Failed to start core loop")
            MmkvManager.retireServiceSession(sessionId)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    /**
     * Destroys the service.
     */
    override fun onDestroy() {
        super.onDestroy()
        CoreServiceManager.stopCoreLoop()
    }

    /**
     * Gets the service instance.
     * @return The service instance.
     */
    override fun getService(): Service {
        return this
    }

    /**
     * Starts the service.
     */
    override fun startService() {
        // do nothing
    }

    /**
     * Stops the service.
     */
    override fun stopService() {
        if (activeSessionId > 0L) MmkvManager.retireServiceSession(activeSessionId)
        stopSelf()
    }

    override fun stopService(sessionId: Long?) {
        if (sessionId == null || sessionId != activeSessionId) {
            LogUtil.i(AppConfig.TAG, "StartCore-Proxy: ignoring stale STOP session=$sessionId")
            return
        }
        MmkvManager.retireServiceSession(sessionId)
        stopSelf()
    }

    override val serviceSessionId: Long
        get() = activeSessionId

    /**
     * Protects the VPN socket.
     * @param socket The socket to protect.
     * @return True if the socket is protected, false otherwise.
     */
    override fun vpnProtect(socket: Int): Boolean {
        return true
    }

    /**
     * Binds the service.
     * @param intent The intent.
     * @return The binder.
     */
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    /**
     * Attaches the base context to the service.
     * @param newBase The new base context.
     */
    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }
}

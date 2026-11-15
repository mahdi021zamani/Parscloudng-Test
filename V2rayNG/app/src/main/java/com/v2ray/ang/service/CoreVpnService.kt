package com.v2ray.ang.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import com.v2ray.ang.AppConfig
import com.v2ray.ang.AppConfig.LOOPBACK
import com.v2ray.ang.BuildConfig
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.contracts.Tun2SocksControl
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.ServiceSessionContract
import com.v2ray.ang.handler.AppLocaleManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.root.RootLanSharing
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.lang.ref.SoftReference
import java.util.concurrent.atomic.AtomicBoolean

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    @Volatile private var isRunning = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)
    private val isStoppingLock = AtomicBoolean(false)
    private val isInterfaceClosed = AtomicBoolean(false)
    private val sessionGate = VpnServiceSessionGate()
    @Volatile private var latestStartId = 0
    @Volatile private var activeSessionId = 0L
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
        registerSubscriptionNetworkCallback()
    }

    /**
     * Keep monitoring network changes while the VPN foreground service is alive.
     * This allows a revoked subscription to be detected after connectivity returns,
     * without requiring the user to reopen the launcher activity.
     */
    private fun registerSubscriptionNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogUtil.i(AppConfig.TAG, "Subscription monitor: network available; queueing validation")
                SubscriptionLicenseWorker.checkAfterNetworkAvailable(applicationContext)
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            manager.registerNetworkCallback(request, callback)
            connectivityManager = manager
            networkCallback = callback
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "Subscription monitor: unable to register network callback", e)
        }
    }

    private fun unregisterSubscriptionNetworkCallback() {
        val manager = connectivityManager
        val callback = networkCallback
        if (manager != null && callback != null) {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
        connectivityManager = null
        networkCallback = null
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        stopService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onDestroy() {
        unregisterSubscriptionNetworkCallback()
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // A normal stop also sets isRunning=false before native shutdown finishes.
        // The close helper is idempotent, so onDestroy can safely cover both normal and
        // unexpected service destruction without racing the stop coroutine.
        closeVpnInterface()

        unlockStart()
        MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_STOP_SUCCESS, "", activeSessionId)
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        latestStartId = maxOf(latestStartId, startId)

        val internalStart = intent?.getBooleanExtra(ServiceSessionContract.EXTRA_INTERNAL_START, false) == true
        val systemVpnStart = !internalStart && intent?.action == SERVICE_INTERFACE
        val alwaysOnEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isAlwaysOn

        val request = when {
            internalStart -> {
                val sessionId = intent?.getLongExtra(ServiceSessionContract.EXTRA_SESSION_ID, 0L) ?: 0L
                if (sessionId <= 0L || MmkvManager.isServiceSessionRetired(sessionId)) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: rejecting invalid or retired app session=$sessionId")
                    return START_NOT_STICKY
                }
                VpnServiceSessionGate.Start(sessionId, startId, VpnServiceSessionGate.Origin.USER)
            }

            systemVpnStart -> {
                if (!alwaysOnEnabled) {
                    LogUtil.w(AppConfig.TAG, "StartCore-VPN: ignoring SERVICE_INTERFACE while Always-on is disabled")
                    return START_NOT_STICKY
                }
                val sessionId = MmkvManager.beginServiceSession()
                VpnServiceSessionGate.Start(sessionId, startId, VpnServiceSessionGate.Origin.SYSTEM)
            }

            intent == null -> {
                // START_STICKY may redeliver a null Intent. It resumes only the persisted
                // session; null is never interpreted as an Android Always-on start.
                val sessionId = MmkvManager.getActiveServiceSession()
                if (sessionId <= 0L || MmkvManager.isServiceSessionRetired(sessionId)) {
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: no live session to recover from null sticky Intent")
                    return START_NOT_STICKY
                }
                VpnServiceSessionGate.Start(sessionId, startId, VpnServiceSessionGate.Origin.USER)
            }

            else -> {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: rejecting unmarked non-system start")
                return START_NOT_STICKY
            }
        }

        if (systemVpnStart) unlockStart()
        val decision = sessionGate.requestStart(request, alwaysOnEnabled)
        LogUtil.i(
            AppConfig.TAG,
            "StartCore-VPN: start session=${request.sessionId}, origin=${request.origin}, decision=$decision, alwaysOn=$alwaysOnEnabled"
        )
        when (decision) {
            VpnServiceSessionGate.StartDecision.START_NOW -> startSession(request)
            VpnServiceSessionGate.StartDecision.QUEUED -> Unit
            VpnServiceSessionGate.StartDecision.IGNORED -> {
                if (sessionGate.teardownCause == VpnServiceSessionGate.TeardownCause.USER_STOP) {
                    MmkvManager.retireServiceSession(request.sessionId)
                } else {
                    val currentOwner = sessionGate.owner
                    if (currentOwner?.sessionId != request.sessionId) {
                        MmkvManager.retireServiceSession(request.sessionId)
                    }
                    currentOwner?.sessionId?.let(MmkvManager::setActiveServiceSession)
                }
            }
        }
        return if (decision == VpnServiceSessionGate.StartDecision.IGNORED) START_NOT_STICKY else START_STICKY
    }

    private fun startSession(request: VpnServiceSessionGate.Start) {
        if (MmkvManager.isServiceSessionRetired(request.sessionId)) {
            sessionGate.clearOwner()
            stopSelf(latestStartId)
            return
        }
        activeSessionId = request.sessionId
        MmkvManager.setActiveServiceSession(request.sessionId)
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: start lock unavailable for session=${request.sessionId}")
            sessionGate.beginTeardown(VpnServiceSessionGate.TeardownCause.INTERNAL)
            sessionGate.finishTeardown()
            stopSelf(latestStartId)
            return
        }
        if (!setupVpnService()) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: VPN setup failed for session=${request.sessionId}")
            stopAllService(true, VpnServiceSessionGate.TeardownCause.INTERNAL)
            return
        }
        startService()
    }

    override val serviceSessionId: Long
        get() = activeSessionId

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        if (!::mInterface.isInitialized) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Interface not initialized")
            stopAllService(true, VpnServiceSessionGate.TeardownCause.INTERNAL)
            return
        }
        if (!CoreServiceManager.startCoreLoop(mInterface)) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
            stopAllService(true, VpnServiceSessionGate.TeardownCause.INTERNAL)
            return
        }

        // Start LAN sharing if enabled in settings
        RootLanSharing.startClientSharing(this)
    }

    override fun stopService() {
        val owner = sessionGate.owner
        if (owner != null) {
            stopService(owner.sessionId)
        } else {
            stopAllService(true, VpnServiceSessionGate.TeardownCause.USER_STOP)
        }
    }

    override fun stopService(sessionId: Long?) {
        if (sessionId == null || sessionId <= 0L) return
        val ownerBeforeStop = sessionGate.owner
        val pendingBeforeStop = sessionGate.pending
        when (sessionGate.requestStop(sessionId)) {
            VpnServiceSessionGate.StopDecision.CANCEL_PENDING -> {
                pendingBeforeStop?.let { MmkvManager.retireServiceSession(it.sessionId) }
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: cancelled pending recovery for teardown session=$sessionId")
            }

            VpnServiceSessionGate.StopDecision.STOP_OWNER -> {
                ownerBeforeStop?.let { MmkvManager.retireServiceSession(it.sessionId) }
                stopAllService(true, VpnServiceSessionGate.TeardownCause.USER_STOP)
            }

            VpnServiceSessionGate.StopDecision.IGNORED -> {
                LogUtil.i(AppConfig.TAG, "StartCore-VPN: ignored stale STOP for session=$sessionId")
            }
        }
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            return false
        }

        runTun2socks()
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed.
        closeVpnInterface()

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish()!!
            isInterfaceClosed.set(false)
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            stopAllService()
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        val bypassLan = SettingsManager.routingRulesetsBypassLan()

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // Configure IPv6 if enabled
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_IPV6_ENABLED) == true) {
            builder.addAddress(vpnConfig.ipv6Client, 126)
            if (bypassLan) {
                builder.addRoute("2000::", 3) // Currently only 1/8 of total IPv6 is in use
                builder.addRoute("fc00::", 18) // Xray-core default FakeIPv6 Pool
            } else {
                builder.addRoute("::", 0)
            }
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Configures per-app proxy rules for the VPN builder.
     *
     * - If per-app proxy is not enabled, disallow the VPN service's own package.
     * - If no apps are selected, disallow the VPN service's own package.
     * - If bypass mode is enabled, disallow all selected apps (including self).
     * - If proxy mode is enabled, only allow the selected apps (excluding self).
     *
     * @param builder The VPN Builder to configure.
     */
    private fun configurePerAppProxy(builder: Builder) {
        val selfPackageName = BuildConfig.APPLICATION_ID

        // If per-app proxy is not enabled, disallow the VPN service's own package and return
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_PER_APP_PROXY) == false) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        // If no apps are selected, disallow the VPN service's own package and return
        val apps = MmkvManager.decodeSettingsStringSet(AppConfig.PREF_PER_APP_PROXY_SET)
        if (apps.isNullOrEmpty()) {
            builder.addDisallowedApplication(selfPackageName)
            return
        }

        val bypassApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_BYPASS_APPS)
        // Handle the VPN service's own package according to the mode
        if (bypassApps) apps.add(selfPackageName) else apps.remove(selfPackageName)

        apps.forEach {
            try {
                if (bypassApps) {
                    // In bypass mode, disallow the selected apps
                    builder.addDisallowedApplication(it)
                } else {
                    // In proxy mode, only allow the selected apps
                    builder.addAllowedApplication(it)
                }
            } catch (e: PackageManager.NameNotFoundException) {
                LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to configure app", e)
            }
        }
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks() {
        if (SettingsManager.isUsingHevTun()) {
            tun2SocksService = TProxyService(
                context = applicationContext,
                vpnInterface = mInterface,
                isRunningProvider = { isRunning },
                restartCallback = { runTun2socks() }
            )
        } else {
            tun2SocksService = null
        }

        tun2SocksService?.startTun2Socks()
    }

    private fun stopAllService(
        isForced: Boolean = true,
        cause: VpnServiceSessionGate.TeardownCause = VpnServiceSessionGate.TeardownCause.INTERNAL,
    ) {
        if (!isStoppingLock.compareAndSet(false, true)) {
            if (cause == VpnServiceSessionGate.TeardownCause.USER_STOP) {
                sessionGate.beginTeardown(VpnServiceSessionGate.TeardownCause.USER_STOP)
            }
            return
        }

        sessionGate.beginTeardown(cause)
        unlockStart()
        isRunning = false

        val tunnel = tun2SocksService
        tun2SocksService = null

        // HEV's JNI stop joins its native worker thread. Run it off the service
        // main thread, then await Aether and Xray before releasing the TUN descriptor.
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { tunnel?.stopTun2Socks() }
                .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-VPN: HEV shutdown failed", it) }
            runCatching { RootLanSharing.stopClientSharing(this@CoreVpnService) }
                .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-VPN: LAN sharing shutdown failed", it) }
            runCatching { CoreServiceManager.stopCoreLoopAndAwait(notifyUi = false) }
                .onSuccess { stopped ->
                    if (!stopped) LogUtil.w(AppConfig.TAG, "StartCore-VPN: one or more native components did not confirm shutdown")
                }
                .onFailure { LogUtil.e(AppConfig.TAG, "StartCore-VPN: core shutdown failed", it) }

            if (isForced) {
                closeVpnInterface()
                val next = sessionGate.finishTeardown()
                isStoppingLock.set(false)
                if (next != null && !MmkvManager.isServiceSessionRetired(next.sessionId)) {
                    LogUtil.i(AppConfig.TAG, "StartCore-VPN: promoting pending session=${next.sessionId}, origin=${next.origin}, recovers=${next.recoversSession}")
                    startSession(next)
                } else {
                    sessionGate.clearOwner()
                    stopSelf(latestStartId)
                }
            } else {
                isStoppingLock.set(false)
            }
        }
    }

    @Synchronized
    private fun closeVpnInterface() {
        if (!::mInterface.isInitialized || isInterfaceClosed.get()) return
        try {
            mInterface.close()
            isInterfaceClosed.set(true)
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed")
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to close interface", e)
        }
    }

    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        if (isStoppingLock.get()) return false
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}

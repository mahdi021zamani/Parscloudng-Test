package com.v2ray.ang.ui.simple

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SimpleVpnRepository
import com.v2ray.ang.handler.SimpleVpnRepository.SimpleServerUiModel
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionLicenseRepository
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.RealPingWorkerService
import com.v2ray.ang.service.SubscriptionLicenseWorker
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

enum class SimpleConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class SimpleVpnUiState(
    val servers: List<SimpleServerUiModel> = emptyList(),
    val testingGuids: Set<String> = emptySet(),
    val selectedGuid: String? = null,
    val selectedSourceId: String = SimpleVpnRepository.SIMPLE_SUB_ID,
    val status: SimpleConnectionStatus = SimpleConnectionStatus.DISCONNECTED,
    val isRefreshing: Boolean = false,
    val canConnect: Boolean = false,
    val message: String? = null,
    val licenseDialogVisible: Boolean = false,
    val licenseKeyInput: String = "",
    val isValidatingLicense: Boolean = false,
    val licenseValid: Boolean = false,
)

internal fun selectSimpleVpnSourceTab(
    current: SimpleVpnUiState,
    sourceId: String,
    servers: List<SimpleServerUiModel>,
    selectedGuid: String?,
    canConnect: Boolean,
): SimpleVpnUiState? {
    if (sourceId != SimpleVpnRepository.SIMPLE_SUB_ID &&
        sourceId != SimpleVpnRepository.AETHER_SUB_ID
    ) return null

    val validSelection = selectedGuid?.takeIf { guid -> servers.any { it.guid == guid } }
    return current.copy(
        selectedSourceId = sourceId,
        servers = servers,
        selectedGuid = validSelection,
        canConnect = canConnect && validSelection != null,
        testingGuids = emptySet(),
        isRefreshing = false,
        message = null,
    )
}

class SimpleVpnViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SimpleVpnUiState())
    val uiState: StateFlow<SimpleVpnUiState> = _uiState.asStateFlow()

    private var pingWorker: RealPingWorkerService? = null
    private var refreshJob: Job? = null
    private var connectValidationJob: Job? = null

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            when (safeIntent.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING, AppConfig.MSG_STATE_START_SUCCESS ->
                    _uiState.update { it.copy(status = SimpleConnectionStatus.CONNECTED) }

                AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS ->
                    _uiState.update { it.copy(status = SimpleConnectionStatus.DISCONNECTED) }

                AppConfig.MSG_STATE_START_FAILURE ->
                    _uiState.update {
                        it.copy(
                            status = SimpleConnectionStatus.DISCONNECTED,
                            message = getApplication<Application>().getString(R.string.simple_connect_failed),
                        )
                    }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            application,
            serviceReceiver,
            IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY),
            Utils.receiverFlags()
        )
        MessageHelper.sendMsg2Service(application, AppConfig.MSG_REGISTER_CLIENT, "")

        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                SettingsManager.initAssets(application, application.assets)
            }
            loadLocalState()

            val storedLicense = withContext(Dispatchers.IO) {
                SubscriptionLicenseRepository.getStoredLicense()
            }
            if (storedLicense == null) {
                _uiState.update { it.copy(licenseDialogVisible = true) }
            } else {
                SubscriptionLicenseWorker.schedule(application)
                validateStoredLicense(storedLicense.key, showDialogOnFailure = false)
            }
            onRefresh()
        }

        // WorkManager's periodic interval is at least 15 minutes and may be deferred
        // by Android. While this screen/process is alive and VPN is connected, check
        // the entitlement promptly so a revoked subscription does not stay connected.
        viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                val state = _uiState.value
                if (state.status == SimpleConnectionStatus.CONNECTED && !state.isValidatingLicense) {
                    val stored = withContext(Dispatchers.IO) {
                        SubscriptionLicenseRepository.getStoredLicense()
                    }
                    if (stored == null) {
                        LauncherManager.stopService(getApplication())
                        _uiState.update {
                            it.copy(
                                status = SimpleConnectionStatus.DISCONNECTED,
                                licenseValid = false,
                                canConnect = false,
                                licenseDialogVisible = true,
                            )
                        }
                    } else {
                        LogUtil.w(AppConfig.TAG, "Subscription monitor: foreground 30-second validation started")
                        validateStoredLicense(stored.key, showDialogOnFailure = true)
                    }
                }
            }
        }
    }

    /**
     * Re-check the saved entitlement whenever the app returns to the foreground.
     * This makes revocations take effect on resume instead of waiting for a user
     * action or the best-effort periodic WorkManager check.
     */
    fun onAppResumed() {
        if (_uiState.value.isValidatingLicense) return

        viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                SubscriptionLicenseRepository.getStoredLicense()
            }
            if (stored == null) {
                _uiState.update {
                    it.copy(
                        licenseValid = false,
                        canConnect = false,
                        licenseDialogVisible = true,
                    )
                }
                return@launch
            }

            validateStoredLicense(stored.key, showDialogOnFailure = true)
            // Feed refresh remains user-driven. License validation on resume must
            // not unexpectedly refresh whichever protocol tab happens to be selected.
        }
    }

    private fun loadLocalState() {
        SimpleVpnRepository.ensureSubscription()
        val sourceId = _uiState.value.selectedSourceId
        refreshJob?.cancel()
        refreshJob = null
        cancelPingWorker()
        val servers = SimpleVpnRepository.getServersForSource(sourceId)
        val selected = MmkvManager.getSelectServer()
            ?.takeIf { guid -> servers.any { it.guid == guid } }
            ?: servers.firstOrNull()?.guid
        selected?.let { MmkvManager.setSelectServer(it) }
        _uiState.update {
            it.copy(
                servers = servers,
                selectedGuid = selected,
                canConnect = SimpleVpnRepository.canConnect(sourceId) && _uiState.value.licenseValid,
            )
        }
    }

    fun onSelectSource(sourceId: String) {
        if (sourceId != SimpleVpnRepository.SIMPLE_SUB_ID &&
            sourceId != SimpleVpnRepository.AETHER_SUB_ID
        ) return
        refreshJob?.cancel()
        refreshJob = null
        cancelPingWorker()
        val servers = SimpleVpnRepository.getServersForSource(sourceId)
        val selected = MmkvManager.getSelectServer()
            ?.takeIf { guid -> servers.any { it.guid == guid } }
            ?: servers.firstOrNull()?.guid
        selected?.let(MmkvManager::setSelectServer)
        val current = _uiState.value
        val next = selectSimpleVpnSourceTab(
            current = current,
            sourceId = sourceId,
            servers = servers,
            selectedGuid = selected,
            canConnect = SimpleVpnRepository.canConnect(sourceId) && current.licenseValid,
        ) ?: return
        _uiState.value = next
    }

    fun onSelectServer(guid: String) {
        if (_uiState.value.servers.none { it.guid == guid }) return
        MmkvManager.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun onRefresh() {
        if (_uiState.value.isRefreshing) return
        val requestedSourceId = _uiState.value.selectedSourceId
        _uiState.update { it.copy(isRefreshing = true, message = null) }

        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            when (val outcome = SimpleVpnRepository.refresh(app, requestedSourceId)) {
                is SimpleVpnRepository.RefreshOutcome.Updated -> {
                    val sourceId = requestedSourceId
                    val servers = SimpleVpnRepository.getServersForSource(sourceId)
                    val sourceGuids = servers.map { it.guid }
                    val selected = MmkvManager.getSelectServer()
                        ?.takeIf { guid -> servers.any { it.guid == guid } }
                        ?: servers.firstOrNull()?.guid
                    val aetherFailure = outcome.aetherFailure.takeIf {
                        sourceId == SimpleVpnRepository.AETHER_SUB_ID && servers.isEmpty()
                    }
                    val aetherMessageId = when (aetherFailure) {
                        SimpleVpnRepository.AetherFailure.LICENSE_MISSING -> R.string.simple_aether_license_required
                        SimpleVpnRepository.AetherFailure.LICENSE_EXPIRED -> R.string.simple_aether_license_expired
                        SimpleVpnRepository.AetherFailure.UNAUTHORIZED -> R.string.simple_aether_license_rejected
                        SimpleVpnRepository.AetherFailure.HTTP_ERROR,
                        SimpleVpnRepository.AetherFailure.NETWORK_ERROR,
                        SimpleVpnRepository.AetherFailure.INVALID_CONTENT -> R.string.simple_network_error_no_cache
                        null -> null
                    }
                    val needsLicenseDialog = aetherFailure == SimpleVpnRepository.AetherFailure.LICENSE_MISSING ||
                        aetherFailure == SimpleVpnRepository.AetherFailure.LICENSE_EXPIRED ||
                        aetherFailure == SimpleVpnRepository.AetherFailure.UNAUTHORIZED
                    withContext(Dispatchers.Main) {
                        _uiState.update { state ->
                            if (state.selectedSourceId != sourceId) {
                                state.copy(
                                    isRefreshing = false,
                                    canConnect = SimpleVpnRepository.canConnect(state.selectedSourceId) && state.licenseValid,
                                )
                            } else {
                                selected?.let(MmkvManager::setSelectServer)
                                state.copy(
                                    servers = servers,
                                    selectedGuid = selected,
                                    testingGuids = sourceGuids.toSet(),
                                    canConnect = SimpleVpnRepository.canConnect(sourceId) && state.licenseValid,
                                    message = aetherMessageId?.let(app::getString),
                                    licenseDialogVisible = needsLicenseDialog,
                                )
                            }
                        }
                    }
                    // Ping is informational; test only the source that is still active.
                    if (_uiState.value.selectedSourceId == sourceId) runPingTest(sourceGuids)
                }

                SimpleVpnRepository.RefreshOutcome.EmptiedByServer -> withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        if (state.selectedSourceId != requestedSourceId) {
                            state.copy(
                                isRefreshing = false,
                                canConnect = SimpleVpnRepository.canConnect(state.selectedSourceId) && state.licenseValid,
                            )
                        } else {
                            state.copy(
                                servers = emptyList(),
                                selectedGuid = null,
                                testingGuids = emptySet(),
                                canConnect = false,
                                isRefreshing = false,
                                status = SimpleConnectionStatus.DISCONNECTED,
                                message = app.getString(R.string.simple_no_servers_available),
                            )
                        }
                    }
                }

                SimpleVpnRepository.RefreshOutcome.KeepingCacheAfterNetworkError -> withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        state.copy(
                            isRefreshing = false,
                            canConnect = SimpleVpnRepository.canConnect(state.selectedSourceId) && state.licenseValid,
                            message = if (state.selectedSourceId == requestedSourceId)
                                app.getString(R.string.simple_network_error_using_cache) else null,
                        )
                    }
                }

                is SimpleVpnRepository.RefreshOutcome.CacheUnavailableAfterNetworkError -> withContext(Dispatchers.Main) {
                    val messageId = when (outcome.aetherFailure) {
                        SimpleVpnRepository.AetherFailure.LICENSE_MISSING -> R.string.simple_aether_license_required
                        SimpleVpnRepository.AetherFailure.LICENSE_EXPIRED -> R.string.simple_aether_license_expired
                        SimpleVpnRepository.AetherFailure.UNAUTHORIZED -> R.string.simple_aether_license_rejected
                        SimpleVpnRepository.AetherFailure.HTTP_ERROR,
                        SimpleVpnRepository.AetherFailure.NETWORK_ERROR,
                        SimpleVpnRepository.AetherFailure.INVALID_CONTENT,
                        null -> R.string.simple_network_error_no_cache
                    }
                    _uiState.update { state ->
                        if (state.selectedSourceId != requestedSourceId) {
                            state.copy(
                                isRefreshing = false,
                                canConnect = SimpleVpnRepository.canConnect(state.selectedSourceId) && state.licenseValid,
                            )
                        } else {
                            state.copy(
                                isRefreshing = false,
                                canConnect = false,
                                status = SimpleConnectionStatus.DISCONNECTED,
                                message = app.getString(messageId),
                                licenseDialogVisible = outcome.aetherFailure == SimpleVpnRepository.AetherFailure.LICENSE_MISSING ||
                                    outcome.aetherFailure == SimpleVpnRepository.AetherFailure.LICENSE_EXPIRED ||
                                    outcome.aetherFailure == SimpleVpnRepository.AetherFailure.UNAUTHORIZED,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun cancelPingWorker() {
        val previous = pingWorker
        pingWorker = null
        previous?.cancel()
    }

    private fun runPingTest(guids: List<String>) {
        cancelPingWorker()
        if (guids.isEmpty()) {
            _uiState.update { it.copy(isRefreshing = false, canConnect = SimpleVpnRepository.canConnect(it.selectedSourceId) && it.licenseValid) }
            return
        }

        val app = getApplication<Application>()
        lateinit var worker: RealPingWorkerService
        worker = RealPingWorkerService(
            context = app,
            guids = guids,
            onEvent = { event ->
                // A cancelled batch may finish after a newer batch has started.
                // Ignore all late events from workers that no longer own the UI.
                if (pingWorker === worker) handlePingEvent(event)
            }
        )
        pingWorker = worker
        worker.start()
    }

    private fun handlePingEvent(event: RealPingEvent) {
        when (event) {
            is RealPingEvent.Result -> {
                MmkvManager.encodeServerTestDelayMillis(event.guid, event.delayMillis)
                _uiState.update { state ->
                    state.copy(
                        testingGuids = state.testingGuids - event.guid,
                        servers = state.servers.map { s ->
                            if (s.guid == event.guid) s.copy(delayMillis = event.delayMillis) else s
                        }
                    )
                }
            }

            is RealPingEvent.Finish -> {
                pingWorker = null
                SimpleVpnRepository.sortByPing()
                _uiState.update {
                    it.copy(
                        servers = SimpleVpnRepository.getServersForSource(it.selectedSourceId),
                        testingGuids = emptySet(),
                        isRefreshing = false,
                        canConnect = SimpleVpnRepository.canConnect(it.selectedSourceId) && it.licenseValid,
                    )
                }
            }

            is RealPingEvent.Progress -> Unit
        }
    }

    /**
     * Called by the Activity when the user taps Connect/Disconnect.
     * [requestVpnPermission] should run the platform VpnService.prepare() dance and
     * call [confirmConnect] once permission is granted (or immediately, in proxy-only mode).
     */
    fun onToggleConnect(requestVpnPermission: () -> Unit) {
        val state = _uiState.value
        val app = getApplication<Application>()
        if (state.status == SimpleConnectionStatus.CONNECTING) {
            // Cancel pending entitlement validation as well as the service start.
            // Otherwise a late validation response could start the previously selected
            // (possibly unreachable) profile after the user has cancelled.
            connectValidationJob?.cancel()
            connectValidationJob = null
            LauncherManager.stopService(app)
            _uiState.update {
                it.copy(
                    status = SimpleConnectionStatus.DISCONNECTED,
                    isValidatingLicense = false,
                    canConnect = it.licenseValid && SimpleVpnRepository.canConnect(it.selectedSourceId),
                )
            }
            return
        }
        if (state.status == SimpleConnectionStatus.CONNECTED) {
            LauncherManager.stopService(app)
            return
        }
        if (!state.licenseValid) {
            _uiState.update { it.copy(licenseDialogVisible = true, message = null) }
            return
        }
        if (!state.canConnect || state.selectedGuid.isNullOrEmpty()) {
            _uiState.update { it.copy(message = app.getString(R.string.simple_no_servers_available)) }
            return
        }
        // Aether probes use the same local listener as a live session. Stop the
        // current batch before launching the daemon so its native process releases
        // the port and identity files first.
        refreshJob?.cancel()
        refreshJob = null
        cancelPingWorker()
        // Validate after the permission prompt, immediately before starting the VPN.
        _uiState.update {
            it.copy(
                status = SimpleConnectionStatus.CONNECTING,
                isRefreshing = false,
                testingGuids = emptySet(),
            )
        }
        requestVpnPermission()
    }

    fun onLicenseKeyInputChanged(value: String) {
        _uiState.update { it.copy(licenseKeyInput = value) }
    }

    fun showLicenseDialog() {
        _uiState.update { it.copy(licenseDialogVisible = true, message = null) }
    }

    fun dismissLicenseDialog() {
        _uiState.update { it.copy(licenseDialogVisible = false) }
    }

    fun activateLicense() {
        val app = getApplication<Application>()
        val key = _uiState.value.licenseKeyInput.trim()
        if (key.isBlank()) {
            _uiState.update { it.copy(message = app.getString(R.string.simple_license_key_required)) }
            return
        }
        if (_uiState.value.isValidatingLicense) return

        _uiState.update { it.copy(isValidatingLicense = true, message = null) }
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) {
                SubscriptionLicenseRepository.validate(key)
            }) {
                is SubscriptionLicenseRepository.ValidationResult.Active -> {
                    val previous = withContext(Dispatchers.IO) {
                        SubscriptionLicenseRepository.getStoredLicense()
                    }
                    val saved = withContext(Dispatchers.IO) {
                        SubscriptionLicenseRepository.saveActiveLicense(key, result.expiresAtMillis)
                    }
                    if (!saved) {
                        _uiState.update {
                            it.copy(
                                isValidatingLicense = false,
                                message = app.getString(R.string.simple_license_network_error),
                            )
                        }
                        return@launch
                    }
                    if (previous != null && previous.key != key) {
                        LauncherManager.stopService(app)
                    }
                    SubscriptionLicenseWorker.schedule(app)
                    val canConnect = SimpleVpnRepository.canConnect(_uiState.value.selectedSourceId)
                    _uiState.update {
                        it.copy(
                            licenseDialogVisible = false,
                            licenseKeyInput = "",
                            isValidatingLicense = false,
                            licenseValid = true,
                            canConnect = canConnect,
                            message = app.getString(R.string.simple_license_active),
                        )
                    }
                    onRefresh()
                }

                is SubscriptionLicenseRepository.ValidationResult.Rejected -> {
                    _uiState.update {
                        it.copy(
                            isValidatingLicense = false,
                            message = app.getString(R.string.simple_license_invalid),
                        )
                    }
                }

                SubscriptionLicenseRepository.ValidationResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isValidatingLicense = false,
                            message = app.getString(R.string.simple_license_network_error),
                        )
                    }
                }
            }
        }
    }

    private suspend fun validateStoredLicense(key: String, showDialogOnFailure: Boolean) {
        _uiState.update { it.copy(isValidatingLicense = true) }
        val stored = withContext(Dispatchers.IO) {
            SubscriptionLicenseRepository.getStoredLicense()
        }
        when (val result = withContext(Dispatchers.IO) {
            SubscriptionLicenseRepository.validate(key)
        }) {
            is SubscriptionLicenseRepository.ValidationResult.Active -> {
                val saved = withContext(Dispatchers.IO) {
                    SubscriptionLicenseRepository.saveActiveLicense(key, result.expiresAtMillis)
                }
                val canConnect = saved && SimpleVpnRepository.canConnect(_uiState.value.selectedSourceId)
                _uiState.update {
                    it.copy(
                        isValidatingLicense = false,
                        licenseValid = saved,
                        canConnect = canConnect,
                        message = if (saved) null else getApplication<Application>().getString(R.string.simple_license_network_error),
                    )
                }
            }

            is SubscriptionLicenseRepository.ValidationResult.Rejected -> {
                LauncherManager.stopService(getApplication())
                _uiState.update {
                    it.copy(
                        isValidatingLicense = false,
                        licenseValid = false,
                        canConnect = false,
                        licenseDialogVisible = showDialogOnFailure,
                        message = getApplication<Application>().getString(R.string.simple_license_invalid),
                    )
                }
            }

            SubscriptionLicenseRepository.ValidationResult.NetworkError -> {
                val locallyActive = stored != null && !SubscriptionLicenseRepository.isExpired(stored)
                if (!locallyActive) {
                    LauncherManager.stopService(getApplication())
                }
                _uiState.update {
                    it.copy(
                        isValidatingLicense = false,
                        licenseValid = locallyActive,
                        canConnect = locallyActive && SimpleVpnRepository.canConnect(it.selectedSourceId),
                        // A temporary validation network failure is not a rejected subscription.
                        licenseDialogVisible = showDialogOnFailure && !locallyActive,
                        message = if (locallyActive) null else getApplication<Application>().getString(R.string.simple_license_network_error),
                    )
                }
            }
        }
    }

    /** Called when the user cancels/denies the VPN permission prompt. */
    fun cancelConnectAttempt() {
        _uiState.update {
            if (it.status == SimpleConnectionStatus.CONNECTING) {
                it.copy(status = SimpleConnectionStatus.DISCONNECTED)
            } else {
                it
            }
        }
    }

    fun confirmConnect() {
        val app = getApplication<Application>()
        val guid = _uiState.value.selectedGuid
        if (guid.isNullOrEmpty()) {
            _uiState.update {
                it.copy(
                    status = SimpleConnectionStatus.DISCONNECTED,
                    message = app.getString(R.string.simple_no_servers_available),
                )
            }
            return
        }

        connectValidationJob?.cancel()
        _uiState.update { it.copy(isValidatingLicense = true) }
        connectValidationJob = viewModelScope.launch {
            val stored = withContext(Dispatchers.IO) {
                SubscriptionLicenseRepository.getStoredLicense()
            }
            if (stored == null) {
                _uiState.update {
                    it.copy(
                        status = SimpleConnectionStatus.DISCONNECTED,
                        isValidatingLicense = false,
                        licenseValid = false,
                        canConnect = false,
                        licenseDialogVisible = true,
                    )
                }
                return@launch
            }

            when (val result = withContext(Dispatchers.IO) {
                SubscriptionLicenseRepository.validate(stored.key)
            }) {
                is SubscriptionLicenseRepository.ValidationResult.Active -> {
                    val saved = withContext(Dispatchers.IO) {
                        SubscriptionLicenseRepository.saveActiveLicense(stored.key, result.expiresAtMillis)
                    }
                    val canConnect = saved && SimpleVpnRepository.canConnect(_uiState.value.selectedSourceId)
                    if (!canConnect) {
                        _uiState.update {
                            it.copy(
                                status = SimpleConnectionStatus.DISCONNECTED,
                                isValidatingLicense = false,
                                licenseValid = saved,
                                canConnect = false,
                                message = app.getString(
                                    if (saved) R.string.simple_no_servers_available
                                    else R.string.simple_license_network_error
                                ),
                            )
                        }
                        return@launch
                    }

                    SubscriptionLicenseWorker.schedule(app)
                    _uiState.update {
                        it.copy(
                            isValidatingLicense = false,
                            licenseValid = true,
                            canConnect = true,
                        )
                    }
                    try {
                        LauncherManager.startService(app, guid)
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "SimpleVpnViewModel: failed to start service", e)
                        _uiState.update {
                            it.copy(
                                status = SimpleConnectionStatus.DISCONNECTED,
                                isValidatingLicense = false,
                                licenseValid = true,
                                canConnect = true,
                            )
                        }
                    }
                }

                is SubscriptionLicenseRepository.ValidationResult.Rejected -> {
                    LauncherManager.stopService(app)
                    _uiState.update {
                        it.copy(
                            status = SimpleConnectionStatus.DISCONNECTED,
                            isValidatingLicense = false,
                            licenseValid = false,
                            canConnect = false,
                            licenseDialogVisible = true,
                            message = app.getString(R.string.simple_license_invalid),
                        )
                    }
                }

                SubscriptionLicenseRepository.ValidationResult.NetworkError -> {
                    val locallyActive = !SubscriptionLicenseRepository.isExpired(stored)
                    if (!locallyActive) {
                        LauncherManager.stopService(app)
                    }
                    _uiState.update {
                        it.copy(
                            status = SimpleConnectionStatus.DISCONNECTED,
                            isValidatingLicense = false,
                            licenseValid = locallyActive,
                            canConnect = locallyActive && SimpleVpnRepository.canConnect(it.selectedSourceId),
                            // A transient validation outage must not disable either protocol tab.
                            message = if (locallyActive) null else app.getString(R.string.simple_license_network_error),
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        refreshJob = null
        cancelPingWorker()
        val app = getApplication<Application>
        runCatching { MessageHelper.sendMsg2Service(app, AppConfig.MSG_UNREGISTER_CLIENT, "") }
        runCatching { app.unregisterReceiver(serviceReceiver) }
    }
}

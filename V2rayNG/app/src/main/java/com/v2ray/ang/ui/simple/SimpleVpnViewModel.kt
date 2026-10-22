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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SimpleConnectionStatus { DISCONNECTED, CONNECTING, CONNECTED }

data class SimpleVpnUiState(
    val servers: List<SimpleServerUiModel> = emptyList(),
    val testingGuids: Set<String> = emptySet(),
    val selectedGuid: String? = null,
    val status: SimpleConnectionStatus = SimpleConnectionStatus.DISCONNECTED,
    val isRefreshing: Boolean = false,
    val canConnect: Boolean = false,
    val message: String? = null,
    val licenseDialogVisible: Boolean = false,
    val licenseKeyInput: String = "",
    val isValidatingLicense: Boolean = false,
    val licenseValid: Boolean = false,
)

class SimpleVpnViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SimpleVpnUiState())
    val uiState: StateFlow<SimpleVpnUiState> = _uiState.asStateFlow()

    private var pingWorker: RealPingWorkerService? = null

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
    }

    private fun loadLocalState() {
        SimpleVpnRepository.ensureSubscription()
        val servers = SimpleVpnRepository.getServers()
        val selected = MmkvManager.getSelectServer()
            ?.takeIf { guid -> servers.any { it.guid == guid } }
            ?: servers.firstOrNull()?.guid
        selected?.let { MmkvManager.setSelectServer(it) }
        _uiState.update {
            it.copy(
                servers = servers,
                selectedGuid = selected,
                canConnect = SimpleVpnRepository.canConnect() && _uiState.value.licenseValid,
            )
        }
    }

    fun onSelectServer(guid: String) {
        MmkvManager.setSelectServer(guid)
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun onRefresh() {
        if (_uiState.value.isRefreshing) return
        _uiState.update { it.copy(isRefreshing = true, message = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            when (val outcome = SimpleVpnRepository.refresh(app)) {
                is SimpleVpnRepository.RefreshOutcome.Updated -> {
                    val guids = SimpleVpnRepository.getServerGuids()
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                servers = guids.mapNotNull(SimpleVpnRepository::getServerUiModel),
                                testingGuids = guids.toSet(),
                            )
                        }
                    }
                    runPingTest(guids)
                }

                SimpleVpnRepository.RefreshOutcome.EmptiedByServer -> withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
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

                SimpleVpnRepository.RefreshOutcome.KeepingCacheAfterNetworkError -> withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            canConnect = SimpleVpnRepository.canConnect() && _uiState.value.licenseValid,
                            message = app.getString(R.string.simple_network_error_using_cache),
                        )
                    }
                }

                SimpleVpnRepository.RefreshOutcome.CacheUnavailableAfterNetworkError -> withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            canConnect = false,
                            status = SimpleConnectionStatus.DISCONNECTED,
                            message = app.getString(R.string.simple_network_error_no_cache),
                        )
                    }
                }
            }
        }
    }

    private fun runPingTest(guids: List<String>) {
        if (guids.isEmpty()) {
            _uiState.update { it.copy(isRefreshing = false, canConnect = SimpleVpnRepository.canConnect() && it.licenseValid) }
            return
        }

        pingWorker?.cancel()
        val app = getApplication<Application>()
        val worker = RealPingWorkerService(
            context = app,
            guids = guids,
            onEvent = { event -> handlePingEvent(event) }
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
                SimpleVpnRepository.sortByPing()
                _uiState.update {
                    it.copy(
                        servers = SimpleVpnRepository.getServers(),
                        testingGuids = emptySet(),
                        isRefreshing = false,
                        canConnect = SimpleVpnRepository.canConnect() && _uiState.value.licenseValid,
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
        if (state.status != SimpleConnectionStatus.DISCONNECTED) {
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
        // Validate after the permission prompt, immediately before starting the VPN.
        _uiState.update { it.copy(status = SimpleConnectionStatus.CONNECTING) }
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
                    val canConnect = SimpleVpnRepository.canConnect()
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
                val canConnect = saved && SimpleVpnRepository.canConnect()
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
                if (stored != null && SubscriptionLicenseRepository.isExpired(stored)) {
                    LauncherManager.stopService(getApplication())
                }
                _uiState.update {
                    it.copy(
                        isValidatingLicense = false,
                        licenseValid = false,
                        canConnect = false,
                        licenseDialogVisible = showDialogOnFailure,
                        message = getApplication<Application>().getString(R.string.simple_license_network_error),
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

        _uiState.update { it.copy(isValidatingLicense = true) }
        viewModelScope.launch {
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
                    val canConnect = saved && SimpleVpnRepository.canConnect()
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
                    try {
                        LauncherManager.startService(app, guid)
                        _uiState.update {
                            it.copy(
                                status = SimpleConnectionStatus.CONNECTING,
                                isValidatingLicense = false,
                                licenseValid = true,
                                canConnect = true,
                            )
                        }
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
                    if (SubscriptionLicenseRepository.isExpired(stored)) {
                        LauncherManager.stopService(app)
                    }
                    _uiState.update {
                        it.copy(
                            status = SimpleConnectionStatus.DISCONNECTED,
                            isValidatingLicense = false,
                            licenseValid = false,
                            canConnect = false,
                            message = app.getString(R.string.simple_license_network_error),
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pingWorker?.cancel()
        val app = getApplication<Application>()
        runCatching { MessageHelper.sendMsg2Service(app, AppConfig.MSG_UNREGISTER_CLIENT, "") }
        runCatching { app.unregisterReceiver(serviceReceiver) }
    }
}

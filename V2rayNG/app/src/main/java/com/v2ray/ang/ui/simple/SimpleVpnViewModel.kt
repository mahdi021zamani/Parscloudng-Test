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
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.core.ServiceSessionContract
import com.v2ray.ang.dto.RealPingEvent
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.dto.entities.SubscriptionCache
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

enum class SimpleVpnProfileTab { WIREGUARD, V2RAY, COMBINED, SERVICES_FOR_IRAN, PERSONAL }

internal fun shouldAcceptSimpleVpnSessionEvent(
    expectedSessionId: Long?,
    activeSessionId: Long,
    eventSessionId: Long,
): Boolean {
    if (eventSessionId <= 0L) return expectedSessionId == null && activeSessionId <= 0L
    return if (activeSessionId > 0L) {
        eventSessionId == activeSessionId
    } else {
        expectedSessionId == null || eventSessionId == expectedSessionId
    }
}

data class SimpleVpnUiState(
    val servers: List<SimpleServerUiModel> = emptyList(),
    val testingGuids: Set<String> = emptySet(),
    val selectedGuid: String? = null,
    /** The actual feed currently shown; V2Ray has two independently cached subtabs. */
    val selectedSourceId: String = SimpleVpnRepository.AETHER_SUB_ID,
    val selectedV2RaySourceId: String = SimpleVpnRepository.SIMPLE_SUB_ID,
    val status: SimpleConnectionStatus = SimpleConnectionStatus.DISCONNECTED,
    val isRefreshing: Boolean = false,
    val refreshingPersonalSubscriptionIds: Set<String> = emptySet(),
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
    if (sourceId !in setOf(
            SimpleVpnRepository.SIMPLE_SUB_ID,
            SimpleVpnRepository.HAMRAH_SUB_ID,
            SimpleVpnRepository.AETHER_SUB_ID,
            SimpleVpnRepository.COMBINED_SUB_ID,
            SimpleVpnRepository.SERVICES_SUB_ID,
            SimpleVpnRepository.PERSONAL_SUB_ID,
        )
    ) return null

    val validSelection = selectedGuid?.takeIf { guid -> servers.any { it.guid == guid } }
    return current.copy(
        selectedSourceId = sourceId,
        selectedV2RaySourceId = if (sourceId == SimpleVpnRepository.SIMPLE_SUB_ID ||
            sourceId == SimpleVpnRepository.HAMRAH_SUB_ID
        ) sourceId else current.selectedV2RaySourceId,
        servers = servers,
        selectedGuid = validSelection,
        canConnect = canConnect && validSelection != null,
        // Keep in-flight ping state for profiles on other tabs; their rows are hidden
        // until the user returns to the owning source.
        testingGuids = current.testingGuids,
        isRefreshing = false,
        message = null,
    )
}

class SimpleVpnViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SimpleVpnUiState())
    val uiState: StateFlow<SimpleVpnUiState> = _uiState.asStateFlow()

    private var pingWorker: RealPingWorkerService? = null
    private var refreshJob: Job? = null
    private val personalSubscriptionRefreshJobs = mutableMapOf<String, Job>()
    private var connectValidationJob: Job? = null
    private var expectedServiceSessionId: Long? = null

    private fun stopCurrentService(context: Context) {
        LauncherManager.stopService(context, expectedServiceSessionId)
    }

    private fun canConnectForSource(sourceId: String): Boolean =
        if (sourceId == SimpleVpnRepository.PERSONAL_SUB_ID) {
            SimpleVpnRepository.getPersonalServers().isNotEmpty()
        } else {
            SimpleVpnRepository.canConnect(sourceId)
        }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val safeIntent = intent ?: return
            val key = safeIntent.getIntExtra("key", 0)
            val eventSessionId = safeIntent.getLongExtra(ServiceSessionContract.EXTRA_SESSION_ID, 0L)
            val activeSessionId = MmkvManager.getActiveServiceSession()
            val expectedSessionId = expectedServiceSessionId

            // Accept only the expected request or the session that is still the actual
            // owner. This lets a pending user start coexist with its old tearing-down
            // owner, while rejecting late status from a retired, unrelated session.
            if (!shouldAcceptSimpleVpnSessionEvent(expectedSessionId, activeSessionId, eventSessionId)) return
            if (eventSessionId > 0L &&
                (expectedSessionId == null ||
                    (eventSessionId == activeSessionId &&
                        (expectedSessionId == null ||
                            eventSessionId > expectedSessionId ||
                            MmkvManager.isServiceSessionRetired(expectedSessionId)))
                )
            ) {
                this@SimpleVpnViewModel.expectedServiceSessionId = eventSessionId
            }

            when (key) {
                AppConfig.MSG_STATE_CONNECTING ->
                    _uiState.update { it.copy(status = SimpleConnectionStatus.CONNECTING, message = null) }

                AppConfig.MSG_STATE_RUNNING ->
                    _uiState.update { it.copy(status = SimpleConnectionStatus.CONNECTED) }

                AppConfig.MSG_STATE_START_SUCCESS ->
                    _uiState.update {
                        it.copy(
                            status = SimpleConnectionStatus.CONNECTED,
                            message = getApplication<Application>().getString(R.string.toast_services_success),
                        )
                    }

                AppConfig.MSG_STATE_NOT_RUNNING ->
                    _uiState.update { state ->
                        state.selectedGuid?.takeIf { MmkvManager.decodeServerConfig(it) != null }?.let(MmkvManager::setSelectServer)
                        if (eventSessionId == 0L || expectedServiceSessionId == eventSessionId) {
                            this@SimpleVpnViewModel.expectedServiceSessionId = null
                        }
                        state.copy(status = SimpleConnectionStatus.DISCONNECTED, message = null)
                    }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    val stopNotice = safeIntent.getStringExtra("content")?.takeIf { it.isNotBlank() }
                    _uiState.update { state ->
                        state.selectedGuid?.takeIf { MmkvManager.decodeServerConfig(it) != null }?.let(MmkvManager::setSelectServer)
                        if (eventSessionId == 0L || expectedServiceSessionId == eventSessionId) {
                            this@SimpleVpnViewModel.expectedServiceSessionId = null
                        }
                        state.copy(status = SimpleConnectionStatus.DISCONNECTED, message = stopNotice)
                    }
                }

                AppConfig.MSG_STATE_START_FAILURE ->
                    _uiState.update { state ->
                        state.selectedGuid?.takeIf { MmkvManager.decodeServerConfig(it) != null }?.let(MmkvManager::setSelectServer)
                        if (eventSessionId == 0L || expectedServiceSessionId == eventSessionId) {
                            this@SimpleVpnViewModel.expectedServiceSessionId = null
                        }
                        state.copy(
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
                // Do not start feed refresh before the first license is entered.
                // Otherwise activation can race an in-flight refresh and its result
                // may dismiss the license dialog or skip the post-activation refresh.
                _uiState.update { it.copy(licenseDialogVisible = true) }
            } else {
                SubscriptionLicenseWorker.schedule(application)
                // Restore entitlement state only. Do not refresh feeds or run ping
                // tests on app launch/resume; users explicitly control refresh.
                validateStoredLicense(storedLicense.key, showDialogOnFailure = false)
            }
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
                        stopCurrentService(getApplication())
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
        SimpleVpnRepository.ensureSubscriptions()
        val sourceId = _uiState.value.selectedSourceId
        refreshJob?.cancel()
        refreshJob = null
        cancelPingWorker()
        val servers = if (sourceId == SimpleVpnRepository.PERSONAL_SUB_ID) SimpleVpnRepository.getPersonalServers() else SimpleVpnRepository.getServersForSource(sourceId)
        val selected = MmkvManager.getSelectServer(sourceId)
            ?.takeIf { guid -> servers.any { it.guid == guid } }
            ?: servers.firstOrNull()?.guid
        selected?.let { MmkvManager.setSelectServer(it, sourceId) }
        val activeGuid = MmkvManager.getSelectServer()
        if (!CoreServiceManager.isRunning() &&
            (activeGuid.isNullOrBlank() || MmkvManager.decodeServerConfig(activeGuid) == null)
        ) {
            selected?.let(MmkvManager::setSelectServer)
        }
        _uiState.update {
            it.copy(
                servers = servers,
                selectedGuid = selected,
                canConnect = canConnectForSource(sourceId) && _uiState.value.licenseValid,
            )
        }
    }

    fun onSelectProfile(tab: SimpleVpnProfileTab) {
        val sourceId = when (tab) {
            SimpleVpnProfileTab.WIREGUARD -> SimpleVpnRepository.AETHER_SUB_ID
            SimpleVpnProfileTab.V2RAY -> _uiState.value.selectedV2RaySourceId
            SimpleVpnProfileTab.COMBINED -> SimpleVpnRepository.COMBINED_SUB_ID
            SimpleVpnProfileTab.SERVICES_FOR_IRAN -> SimpleVpnRepository.SERVICES_SUB_ID
            SimpleVpnProfileTab.PERSONAL -> SimpleVpnRepository.PERSONAL_SUB_ID
        }
        onSelectSource(sourceId)
    }

    fun onSelectV2RaySubtab(sourceId: String) {
        if (_uiState.value.status == SimpleConnectionStatus.CONNECTING) return
        if (sourceId != SimpleVpnRepository.SIMPLE_SUB_ID &&
            sourceId != SimpleVpnRepository.HAMRAH_SUB_ID
        ) return
        val isV2RayVisible = _uiState.value.selectedSourceId == SimpleVpnRepository.SIMPLE_SUB_ID ||
            _uiState.value.selectedSourceId == SimpleVpnRepository.HAMRAH_SUB_ID
        _uiState.update { it.copy(selectedV2RaySourceId = sourceId) }
        if (isV2RayVisible) onSelectSource(sourceId)
    }

    fun onSelectSource(sourceId: String) {
        if (_uiState.value.status == SimpleConnectionStatus.CONNECTING) return
        if (sourceId !in setOf(
                SimpleVpnRepository.SIMPLE_SUB_ID,
                SimpleVpnRepository.HAMRAH_SUB_ID,
                SimpleVpnRepository.AETHER_SUB_ID,
                SimpleVpnRepository.COMBINED_SUB_ID,
                SimpleVpnRepository.SERVICES_SUB_ID,
                SimpleVpnRepository.PERSONAL_SUB_ID,
            )
        ) return
        refreshJob?.cancel()
        refreshJob = null
        // Keep the current ping batch alive while the user browses another source.
        val servers = if (sourceId == SimpleVpnRepository.PERSONAL_SUB_ID) SimpleVpnRepository.getPersonalServers() else SimpleVpnRepository.getServersForSource(sourceId)
        val selected = MmkvManager.getSelectServer(sourceId)
            ?.takeIf { guid -> servers.any { it.guid == guid } }
            ?: servers.firstOrNull()?.guid
        selected?.let {
            MmkvManager.setSelectServer(it, sourceId)
            if (_uiState.value.status == SimpleConnectionStatus.DISCONNECTED) {
                MmkvManager.setSelectServer(it)
            }
        }
        val current = _uiState.value
        val next = selectSimpleVpnSourceTab(
            current = current,
            sourceId = sourceId,
            servers = servers,
            selectedGuid = selected,
            canConnect = (if (sourceId == SimpleVpnRepository.PERSONAL_SUB_ID) servers.isNotEmpty() else canConnectForSource(sourceId)) && current.licenseValid,
        ) ?: return
        _uiState.value = next
    }


    fun importPersonalConfig(content: String) {
        val normalized = content.trim()
        if (normalized.isBlank()) {
            _uiState.update { it.copy(message = "متن کانفیگ خالی است") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val subscriptionUrls = normalized.lineSequence().map { it.trim() }
                .filter { Utils.isValidSubUrl(it) }.distinct().toList()
            val configLines = normalized.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !Utils.isValidSubUrl(it) }.joinToString("\n")
            var imported = 0
            val existingPersonalSubscriptions = MmkvManager.decodeSubscriptions()
                .filter { it.guid.startsWith("personal_sub_") }
            var nextSubscriptionNumber = existingPersonalSubscriptions.size + 1
            subscriptionUrls.forEach { url ->
                // Do not create a second entry for a URL that is already registered.
                if (existingPersonalSubscriptions.any { it.subscription.url == url }) return@forEach
                val id = "personal_sub_" + Utils.getUuid()
                val item = SubscriptionItem(
                    remarks = "اشتراک شخصی $nextSubscriptionNumber",
                    url = url,
                    enabled = true,
                    autoUpdate = false,
                )
                nextSubscriptionNumber++
                MmkvManager.encodeSubscription(id, item)
                imported += com.v2ray.ang.handler.AngConfigManager
                    .updateConfigViaSub(SubscriptionCache(id, item)).configCount
            }
            if (configLines.isNotBlank()) {
                imported += com.v2ray.ang.handler.AngConfigManager.importBatchConfigWithoutSubscriptions(
                    configLines, SimpleVpnRepository.PERSONAL_SUB_ID, true
                )
            }
            withContext(Dispatchers.Main) {
                val servers = SimpleVpnRepository.getPersonalServers()
                val selected = MmkvManager.getSelectServer(SimpleVpnRepository.PERSONAL_SUB_ID)
                    ?.takeIf { guid -> servers.any { it.guid == guid } } ?: servers.firstOrNull()?.guid
                _uiState.update {
                    it.copy(
                        selectedSourceId = SimpleVpnRepository.PERSONAL_SUB_ID,
                        servers = servers,
                        selectedGuid = selected,
                        canConnect = servers.isNotEmpty() && it.licenseValid,
                        message = if (imported > 0) "$imported مورد شخصی اضافه شد" else "لینک یا کانفیگ قابل شناسایی نیست",
                    )
                }
                selected?.let {
                    MmkvManager.setSelectServer(it, SimpleVpnRepository.PERSONAL_SUB_ID)
                    if (_uiState.value.status == SimpleConnectionStatus.DISCONNECTED) MmkvManager.setSelectServer(it)
                }
                runPingTest(servers.map { it.guid })
            }
        }
    }

    fun deletePersonalConfig(guid: String) {
        if (MmkvManager.isCentralProfile(guid)) return
        val profileSubId = MmkvManager.decodeServerConfig(guid)?.subscriptionId ?: return
        MmkvManager.removeServers(listOf(guid), profileSubId)
        if (MmkvManager.decodeServerList(profileSubId).isEmpty() && profileSubId.startsWith("personal_sub_")) {
            MmkvManager.removeSubscription(profileSubId)
        }
        val servers = SimpleVpnRepository.getPersonalServers()
        val selected = MmkvManager.getSelectServer(SimpleVpnRepository.PERSONAL_SUB_ID)
            ?.takeIf { id -> servers.any { it.guid == id } } ?: servers.firstOrNull()?.guid
        _uiState.update {
            it.copy(
                servers = servers,
                selectedGuid = selected,
                canConnect = servers.isNotEmpty() && it.licenseValid,
                message = "کانفیگ حذف شد",
            )
        }
    }

    /** Refreshes exactly one user-owned subscription without touching sibling groups. */
    fun refreshPersonalSubscription(subscriptionId: String) {
        if (!subscriptionId.startsWith("personal_sub_")) return
        if (personalSubscriptionRefreshJobs[subscriptionId]?.isActive == true) return
        val subscription = MmkvManager.decodeSubscription(subscriptionId)
        if (subscription == null) {
            _uiState.update { it.copy(message = "اشتراک پیدا نشد") }
            return
        }

        _uiState.update {
            it.copy(
                refreshingPersonalSubscriptionIds = it.refreshingPersonalSubscriptionIds + subscriptionId,
                message = null,
            )
        }
        val job = viewModelScope.launch(Dispatchers.IO) {
            val count = try {
                com.v2ray.ang.handler.AngConfigManager
                    .updateConfigViaSub(SubscriptionCache(subscriptionId, subscription)).configCount
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Personal subscription refresh failed for $subscriptionId", e)
                -1
            }
            val servers = SimpleVpnRepository.getPersonalServers()
            val groupGuids = MmkvManager.decodeServerList(subscriptionId)
                .filter { MmkvManager.decodeServerConfig(it) != null }
            withContext(Dispatchers.Main) {
                _uiState.update { state ->
                    state.copy(
                        servers = if (state.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID) servers else state.servers,
                        selectedGuid = if (state.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID) {
                            state.selectedGuid?.takeIf { guid -> servers.any { it.guid == guid } }
                                ?: servers.firstOrNull()?.guid
                        } else state.selectedGuid,
                        refreshingPersonalSubscriptionIds = state.refreshingPersonalSubscriptionIds - subscriptionId,
                        canConnect = if (state.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID) {
                            servers.isNotEmpty() && state.licenseValid
                        } else state.canConnect,
                        message = when {
                            count < 0 -> "به‌روزرسانی اشتراک ناموفق بود"
                            count > 0 -> "$count کانفیگ این اشتراک به‌روزرسانی شد"
                            else -> "برای این اشتراک کانفیگ جدیدی دریافت نشد"
                        },
                    )
                }
                if (groupGuids.isNotEmpty()) runPingTest(groupGuids)
            }
        }
        personalSubscriptionRefreshJobs[subscriptionId] = job
        job.invokeOnCompletion { personalSubscriptionRefreshJobs.remove(subscriptionId, job) }
    }

    /** Deletes only one personal subscription and all profiles indexed under its ID. */
    fun deletePersonalSubscription(subscriptionId: String) {
        if (!subscriptionId.startsWith("personal_sub_")) return
        personalSubscriptionRefreshJobs.remove(subscriptionId)?.cancel()
        val removedGuids = MmkvManager.decodeServerList(subscriptionId).toSet()
        MmkvManager.removeSubscription(subscriptionId)
        val servers = SimpleVpnRepository.getPersonalServers()
        val state = _uiState.value
        val selected = state.selectedGuid?.takeIf { it !in removedGuids && servers.any { server -> server.guid == it } }
            ?: servers.firstOrNull()?.guid
        val shouldRestartPing = state.testingGuids.any { it in removedGuids }
        if (shouldRestartPing) cancelPingWorker()
        _uiState.update {
            it.copy(
                servers = if (it.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID) servers else it.servers,
                selectedGuid = if (it.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID) selected else it.selectedGuid,
                testingGuids = if (shouldRestartPing) emptySet() else it.testingGuids - removedGuids,
                refreshingPersonalSubscriptionIds = it.refreshingPersonalSubscriptionIds - subscriptionId,
                canConnect = if (it.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID) {
                    servers.isNotEmpty() && it.licenseValid
                } else it.canConnect,
                message = "اشتراک شخصی حذف شد",
            )
        }
        if (shouldRestartPing) {
            val remainingGuids = servers.map { it.guid }
            if (remainingGuids.isNotEmpty()) runPingTest(remainingGuids)
        }
    }

    fun onSelectServer(guid: String) {
        val state = _uiState.value
        val sourceId = state.selectedSourceId
        if (state.servers.none { it.guid == guid }) return
        MmkvManager.setSelectServer(guid, sourceId)
        if (state.status == SimpleConnectionStatus.DISCONNECTED) {
            MmkvManager.setSelectServer(guid)
        }
        _uiState.update { it.copy(selectedGuid = guid) }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(message = null) }
    }

    fun onRefresh() {
        if (_uiState.value.isRefreshing) return
        if (_uiState.value.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID) {
            _uiState.update { it.copy(isRefreshing = true, message = null) }
            refreshJob = viewModelScope.launch(Dispatchers.IO) {
                val count = SimpleVpnRepository.refreshPersonalSubscriptions()
                val servers = SimpleVpnRepository.getPersonalServers()
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            servers = servers,
                            selectedGuid = it.selectedGuid?.takeIf { guid -> servers.any { s -> s.guid == guid } }
                                ?: servers.firstOrNull()?.guid,
                            isRefreshing = false,
                            canConnect = servers.isNotEmpty() && it.licenseValid,
                            message = if (count > 0) "$count کانفیگ شخصی به‌روزرسانی شد" else "اشتراک شخصی به‌روزرسانی نشد",
                        )
                    }
                    runPingTest(servers.map { it.guid })
                }
            }
            return
        }
        val requestedSourceId = _uiState.value.selectedSourceId
        _uiState.update { it.copy(isRefreshing = true, message = null) }

        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            when (val outcome = SimpleVpnRepository.refresh(app, requestedSourceId)) {
                is SimpleVpnRepository.RefreshOutcome.Updated -> {
                    val sourceId = requestedSourceId
                    val servers = SimpleVpnRepository.getServersForSource(sourceId)
                    val sourceGuids = servers.map { it.guid }
                    val selected = MmkvManager.getSelectServer(sourceId)
                        ?.takeIf { guid -> servers.any { it.guid == guid } }
                        ?: servers.firstOrNull()?.guid
                    val aetherFailure = outcome.aetherFailure.takeIf {
                        sourceId in setOf(
                            SimpleVpnRepository.AETHER_SUB_ID,
                            SimpleVpnRepository.COMBINED_SUB_ID,
                        ) && servers.isEmpty()
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
                                    canConnect = canConnectForSource(state.selectedSourceId) && state.licenseValid,
                                )
                            } else {
                                selected?.let {
                                    MmkvManager.setSelectServer(it, sourceId)
                                    if (state.status == SimpleConnectionStatus.DISCONNECTED) {
                                        MmkvManager.setSelectServer(it)
                                    }
                                }
                                state.copy(
                                    servers = servers,
                                    selectedGuid = selected,
                                    testingGuids = sourceGuids.toSet(),
                                    canConnect = canConnectForSource(sourceId) && state.licenseValid,
                                    message = aetherMessageId?.let(app::getString),
                                    // A normal feed refresh must not dismiss an already
                                    // visible first-run/license prompt. Only explicit
                                    // entitlement failures may force it open.
                                    licenseDialogVisible = state.licenseDialogVisible || needsLicenseDialog,
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
                                canConnect = canConnectForSource(state.selectedSourceId) && state.licenseValid,
                            )
                        } else {
                            state.copy(
                                servers = emptyList(),
                                selectedGuid = null,
                                testingGuids = emptySet(),
                                canConnect = false,
                                isRefreshing = false,
                                // The refresh may target a different tab than the running VPN.
                                // The service receiver alone reports an actual disconnect.
                                message = app.getString(R.string.simple_no_servers_available),
                            )
                        }
                    }
                }

                SimpleVpnRepository.RefreshOutcome.KeepingCacheAfterNetworkError -> withContext(Dispatchers.Main) {
                    _uiState.update { state ->
                        state.copy(
                            isRefreshing = false,
                            canConnect = canConnectForSource(state.selectedSourceId) && state.licenseValid,
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
                                canConnect = canConnectForSource(state.selectedSourceId) && state.licenseValid,
                            )
                        } else {
                            state.copy(
                                isRefreshing = false,
                                canConnect = false,
                                // A feed error does not stop an already-running tunnel.
                                status = state.status,
                                message = app.getString(messageId),
                                licenseDialogVisible = state.licenseDialogVisible ||
                                    outcome.aetherFailure == SimpleVpnRepository.AetherFailure.LICENSE_MISSING ||
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
            _uiState.update {
                it.copy(
                    testingGuids = emptySet(),
                    isRefreshing = false,
                    canConnect = canConnectForSource(it.selectedSourceId) && it.licenseValid,
                )
            }
            return
        }

        val app = getApplication<Application>()
        var worker: RealPingWorkerService? = null
        _uiState.update {
            it.copy(testingGuids = guids.toSet())
        }
        val newWorker = RealPingWorkerService(
            context = app,
            guids = guids,
            onEvent = { event ->
                // A cancelled batch may finish after a newer batch has started.
                // Ignore all late events from workers that no longer own the UI.
                if (pingWorker === worker) handlePingEvent(event)
            }
        )
        worker = newWorker
        pingWorker = newWorker
        newWorker.start()
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
                val activeSourceId = _uiState.value.selectedSourceId
                if (activeSourceId != SimpleVpnRepository.PERSONAL_SUB_ID) {
                    SimpleVpnRepository.sortByPing()
                }
                val refreshedServers = if (activeSourceId == SimpleVpnRepository.PERSONAL_SUB_ID)
                    SimpleVpnRepository.getPersonalServers()
                else SimpleVpnRepository.getServersForSource(activeSourceId)
                _uiState.update {
                    it.copy(
                        servers = refreshedServers,
                        testingGuids = emptySet(),
                        isRefreshing = false,
                        canConnect = canConnectForSource(it.selectedSourceId) && it.licenseValid,
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
            state.selectedGuid?.let(MmkvManager::setSelectServer)
            stopCurrentService(app)
            _uiState.update {
                it.copy(
                    status = SimpleConnectionStatus.DISCONNECTED,
                    isValidatingLicense = false,
                    canConnect = it.licenseValid && canConnectForSource(it.selectedSourceId),
                )
            }
            return
        }
        if (state.status == SimpleConnectionStatus.CONNECTED) {
            stopCurrentService(app)
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
                        stopCurrentService(app)
                    }
                    SubscriptionLicenseWorker.schedule(app)
                    val canConnect = canConnectForSource(_uiState.value.selectedSourceId)
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
                val canConnect = saved && canConnectForSource(_uiState.value.selectedSourceId)
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
                stopCurrentService(getApplication())
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
                    stopCurrentService(getApplication())
                }
                _uiState.update {
                    it.copy(
                        isValidatingLicense = false,
                        licenseValid = locallyActive,
                        canConnect = locallyActive && canConnectForSource(it.selectedSourceId),
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
                it.selectedGuid?.let(MmkvManager::setSelectServer)
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
                    val canConnect = saved && canConnectForSource(_uiState.value.selectedSourceId)
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
                        val startedSessionId = LauncherManager.startService(app, guid)
                        if (startedSessionId == null) {
                            expectedServiceSessionId = null
                            _uiState.update {
                                it.copy(
                                    status = SimpleConnectionStatus.DISCONNECTED,
                                    isValidatingLicense = false,
                                    licenseValid = true,
                                    canConnect = true,
                                    message = app.getString(R.string.simple_connect_failed),
                                )
                            }
                        } else {
                            expectedServiceSessionId = startedSessionId
                        }
                    } catch (e: Exception) {
                        expectedServiceSessionId = null
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
                    stopCurrentService(app)
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
                        stopCurrentService(app)
                    }
                    _uiState.update {
                        it.copy(
                            status = SimpleConnectionStatus.DISCONNECTED,
                            isValidatingLicense = false,
                            licenseValid = locallyActive,
                            canConnect = locallyActive && canConnectForSource(it.selectedSourceId),
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
        personalSubscriptionRefreshJobs.values.forEach { it.cancel() }
        personalSubscriptionRefreshJobs.clear()
        cancelPingWorker()
        val app = getApplication<Application>()
        runCatching { MessageHelper.sendMsg2Service(app, AppConfig.MSG_UNREGISTER_CLIENT, "") }
        runCatching { app.unregisterReceiver(serviceReceiver) }
    }
}

package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.RemoteConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.CountryFlagUtil
import com.v2ray.ang.util.LogUtil

/**
 * Coordinates independent remote feeds for the simplified PARSCLOUD client.
 *
 * Each source has its own subscription ID and cache. V2Ray and Aether are
 * presented in separate tabs; the emergency feed is shown in the V2Ray tab
 * only when neither primary feed has fresh usable data.
 */
object SimpleVpnRepository {

    /** Kept stable so existing installations migrate their old cache in place. */
    /** Legacy ID retained so existing irancell installations keep their cache. */
    const val SIMPLE_SUB_ID = "simple_free_vpn"
    const val HAMRAH_SUB_ID = "simple_hamrah"
    const val AETHER_SUB_ID = "simple_aether"
    const val COMBINED_SUB_ID = "simple_combined"
    const val SERVICES_SUB_ID = "simple_services_iran"
    const val PERSONAL_SUB_ID = "simple_personal"
    const val EMERGENCY_SUB_ID = "simple_emergency"

    private enum class SourceKind { V2RAY, AETHER, CUSTOM_JSON }

    private data class Source(
        val id: String,
        val remarks: String,
        val url: String,
        val kind: SourceKind,
    )

    private val v2raySources: List<Source>
        get() = primarySources.filter { it.kind == SourceKind.V2RAY }

    private val primarySources: List<Source>
        get() = listOf(
            Source(SIMPLE_SUB_ID, "PARSCLOUD • irancell", RemoteConfig.SERVER_LIST_URL, SourceKind.V2RAY),
            Source(HAMRAH_SUB_ID, "PARSCLOUD • Hamrah", RemoteConfig.HAMRAH_SERVER_LIST_URL, SourceKind.V2RAY),
            Source(AETHER_SUB_ID, "PARSCLOUD • WireGuard", RemoteConfig.AETHER_SERVER_LIST_URL, SourceKind.AETHER),
            Source(COMBINED_SUB_ID, "PARSCLOUD • Combined", RemoteConfig.COMBINED_AETHER_SERVER_LIST_URL, SourceKind.AETHER),
            Source(SERVICES_SUB_ID, "PARSCLOUD • Services for Iran", RemoteConfig.SERVICES_FOR_IRAN_URL, SourceKind.CUSTOM_JSON),
        )

    /** Empty until a private server-side proxy is configured for the secret panel URL. */
    private val emergencySource: Source?
        get() = RemoteConfig.EMERGENCY_FALLBACK_URL
            .takeIf { it.isNotBlank() }
            ?.let { Source(EMERGENCY_SUB_ID, "PARSCLOUD • Backup", it, SourceKind.V2RAY) }

    data class PersonalSubscriptionTab(val id: String, val name: String)

    data class SimpleServerUiModel(
        val guid: String,
        val displayName: String,
        val countryName: String?,
        val flagEmoji: String,
        /** null = not tested yet, -1 = failed/timeout, >=0 = ms */
        val delayMillis: Long?,
        val subscriptionId: String = "",
        val subscriptionName: String = "",
    )

    enum class AetherFailure { LICENSE_MISSING, LICENSE_EXPIRED, UNAUTHORIZED, HTTP_ERROR, NETWORK_ERROR, INVALID_CONTENT }

    sealed class RefreshOutcome {
        data class Updated(val serverCount: Int, val aetherFailure: AetherFailure? = null) : RefreshOutcome()
        data object EmptiedByServer : RefreshOutcome()
        data object KeepingCacheAfterNetworkError : RefreshOutcome()
        data class CacheUnavailableAfterNetworkError(
            val aetherFailure: AetherFailure? = null
        ) : RefreshOutcome()
    }

    private data class SourceRefreshResult(
        val result: AngConfigManager.SimpleRefreshResult,
        val aetherFailure: AetherFailure? = null,
    )

    private fun ensureSubscription(source: Source): SubscriptionCache {
        val existing = MmkvManager.decodeSubscription(source.id)
        val subItem = existing ?: SubscriptionItem(
            remarks = source.remarks,
            url = source.url,
            enabled = source.kind != SourceKind.AETHER,
            autoUpdate = false,
        )
        if (existing == null || existing.url != source.url || existing.remarks != source.remarks ||
            subItem.enabled != (source.kind != SourceKind.AETHER) || subItem.autoUpdate
        ) {
            subItem.url = source.url
            subItem.remarks = source.remarks
            // Aether feeds are fetched through the dedicated direct-raw repository.
            // Keep their generic MMKV subscriptions disabled so bulk updates cannot
            // bypass the controlled refresh path.
            subItem.enabled = source.kind != SourceKind.AETHER
            subItem.autoUpdate = false
            MmkvManager.encodeSubscription(source.id, subItem)
        }
        return SubscriptionCache(source.id, subItem)
    }

    /** Backwards-compatible initializer used by older call sites. */
    fun ensureSubscription(): SubscriptionCache = ensureSubscription(primarySources.first())

    /** Initialize all central source identities without performing any network request. */
    fun ensureSubscriptions() {
        primarySources.forEach { ensureSubscription(it) }
        emergencySource?.let(::ensureSubscription)
    }

    private fun allConfiguredSources(): List<Source> =
        primarySources + listOfNotNull(emergencySource)

    private fun isCacheExpired(sub: SubscriptionItem?): Boolean {
        if (sub == null || sub.lastUpdated <= 0L) return true
        val expiryMillis = RemoteConfig.REMOTE_DATA_EXPIRY_MINUTES * 60_000L
        return System.currentTimeMillis() - sub.lastUpdated > expiryMillis
    }

    private fun sourceGuids(source: Source): List<String> =
        MmkvManager.decodeServerList(source.id).toList()

    private fun sourceHasCachedProfiles(source: Source): Boolean =
        sourceGuids(source).any { MmkvManager.decodeServerConfig(it) != null }

    private fun sourceHasFreshConfigs(source: Source): Boolean {
        val sub = MmkvManager.decodeSubscription(source.id) ?: return false
        return sourceHasCachedProfiles(source) && !isCacheExpired(sub)
    }

    /**
     * Fresh primary feeds are exposed independently to their respective tabs.
     * The emergency feed is hidden unless all primary feeds lack fresh usable data.
     */
    private fun visibleSourceIds(): List<String> {
        val freshPrimary = primarySources.filter(::sourceHasFreshConfigs)
        if (freshPrimary.isNotEmpty()) return freshPrimary.map { it.id }

        emergencySource?.takeIf(::sourceHasFreshConfigs)?.let { return listOf(it.id) }

        // Preserve stale cached entries for visibility and offline use. Freshness
        // determines whether a refresh is due, not whether cached profiles can connect.
        val cachedPrimary = primarySources.filter(::sourceHasCachedProfiles)
        if (cachedPrimary.isNotEmpty()) return cachedPrimary.map { it.id }
        return listOfNotNull(emergencySource?.takeIf(::sourceHasCachedProfiles)?.id)
    }

    fun getServerGuids(): List<String> {
        val ids = visibleSourceIds()
        return ids.flatMap { MmkvManager.decodeServerList(it) }.distinct()
    }

    /**
     * Returns only the profiles belonging to the requested primary tab.
     * The emergency feed is shown in the V2Ray tab only when neither primary
     * feed has fresh usable profiles.
     */
    fun getServerGuidsForSource(sourceId: String): List<String> {
        val source = primarySources.firstOrNull { it.id == sourceId } ?: return emptyList()
        if (sourceHasFreshConfigs(source)) return sourceGuids(source).distinct()

        // Freshness affects refresh scheduling, never the usability of a valid local cache.
        val cached = sourceGuids(source).filter { MmkvManager.decodeServerConfig(it) != null }
        if (cached.isNotEmpty()) return cached.distinct()

        // The emergency feed is a V2Ray-only fallback, shared by the two V2Ray subtabs.
        if (source.kind == SourceKind.V2RAY && v2raySources.none(::sourceHasFreshConfigs)) {
            return emergencySource?.let(::sourceGuids).orEmpty()
                .filter { MmkvManager.decodeServerConfig(it) != null }
                .distinct()
        }
        return emptyList()
    }

    fun getServerUiModel(guid: String): SimpleServerUiModel? {
        val profile = MmkvManager.decodeServerConfig(guid) ?: return null
        val delay = MmkvManager.decodeServerAffiliationInfo(guid)?.testDelayMillis
        val name = profile.remarks.ifBlank { profile.description.orEmpty().ifBlank { guid } }
        val info = CountryFlagUtil.extract(profile.remarks)
        return SimpleServerUiModel(
            guid = guid,
            displayName = name,
            countryName = info.countryName,
            flagEmoji = info.flagEmoji,
            delayMillis = delay,
            subscriptionId = profile.subscriptionId,
            subscriptionName = MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks
                ?: if (profile.subscriptionId == PERSONAL_SUB_ID) "کانفیگ‌های دستی" else "اشتراک شخصی",
        )
    }

    fun getServers(): List<SimpleServerUiModel> = getServerGuids().mapNotNull(::getServerUiModel)

    /** User-owned profiles are isolated by their subscription ID. */
    fun getPersonalServers(sourceId: String = PERSONAL_SUB_ID): List<SimpleServerUiModel> {
        if (sourceId != PERSONAL_SUB_ID && !sourceId.startsWith("personal_sub_")) return emptyList()
        val servers = MmkvManager.decodeServerList(sourceId).mapNotNull(::getServerUiModel)
        return if (sourceId == PERSONAL_SUB_ID) {
            servers.map { it.copy(subscriptionName = "کانفیگ‌های دستی") }
        } else {
            servers
        }
    }

    /** Dynamic tabs retain the subscription order and the saved user-facing remarks. */
    fun getPersonalSubscriptionTabs(): List<PersonalSubscriptionTab> =
        MmkvManager.decodeSubscriptions()
            .filter { it.guid.startsWith("personal_sub_") }
            .map { item ->
                PersonalSubscriptionTab(
                    id = item.guid,
                    name = item.subscription.remarks.ifBlank {
                        personalSubscriptionName(item.subscription.url)
                    },
                )
            }

    /** Derive a useful initial label from the URL's explicit fragment or host. */
    fun personalSubscriptionName(url: String): String {
        val parsed = android.net.Uri.parse(url)
        return parsed.fragment?.takeIf { it.isNotBlank() }?.let(android.net.Uri::decode)
            ?: parsed.host?.takeIf { it.isNotBlank() }
            ?: "اشتراک شخصی"
    }

    /** Refreshes only user-created subscriptions; official PARSCLOUD feeds are never touched. */
    fun refreshPersonalSubscriptions(): Int {
        var imported = 0
        MmkvManager.decodeSubscriptions()
            .filter { it.guid.startsWith("personal_sub_") }
            .forEach { imported += AngConfigManager.updateConfigViaSub(it).configCount }
        return imported
    }

    fun getServersForSource(sourceId: String): List<SimpleServerUiModel> =
        getServerGuidsForSource(sourceId).mapNotNull(::getServerUiModel)

    fun sortByPing() {
        visibleSourceIds().forEach(AngConfigManager::sortByTestResultsForSub)
    }

    /** Returns true when the Aether cache is absent or older than the configured freshness window. */
    fun isAetherRefreshDue(): Boolean {
        val source = primarySources.first { it.id == AETHER_SUB_ID }
        return !sourceHasFreshConfigs(source)
    }

    fun canConnect(): Boolean = visibleSourceIds().any { id ->
        val source = allConfiguredSources().firstOrNull { it.id == id } ?: return@any false
        sourceHasCachedProfiles(source)
    }

    fun canConnect(sourceId: String): Boolean {
        val source = primarySources.firstOrNull { it.id == sourceId } ?: return false

        // Every profile tab owns its cache independently. Freshness only decides
        // whether a manual refresh is due; it does not disable a cached profile.
        if (sourceHasCachedProfiles(source)) return true

        // Emergency fallback is exposed only inside V2Ray, when neither V2Ray
        // subfeed has a fresh usable cache.
        if (source.kind == SourceKind.V2RAY && v2raySources.none(::sourceHasFreshConfigs)) {
            return emergencySource?.let(::sourceHasCachedProfiles) == true
        }
        return false
    }

    private fun clearSourceCache(source: Source) {
        MmkvManager.removeServers(sourceGuids(source), source.id)
        MmkvManager.decodeSubscription(source.id)?.let { sub ->
            sub.lastUpdated = -1L
            MmkvManager.encodeSubscription(source.id, sub)
        }
    }

    private suspend fun refreshSource(source: Source): SourceRefreshResult {
        val subscription = ensureSubscription(source)
        if (source.kind == SourceKind.AETHER) {
            return when (val result = AetherFeedRepository.fetch(source.url)) {
                is AetherFeedRepository.Result.Success -> {
                    val normalized = result.content
                        .removePrefix("\uFEFF")
                        .lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString("\n")
                    if (normalized.isEmpty()) {
                        SourceRefreshResult(AngConfigManager.SimpleRefreshResult.EmptyResponse)
                    } else {
                        val count = AngConfigManager.importBatchConfigWithoutSubscriptions(
                            normalized, source.id, false
                        )
                        if (count > 0) {
                            subscription.subscription.lastUpdated = System.currentTimeMillis()
                            MmkvManager.encodeSubscription(source.id, subscription.subscription)
                            SourceRefreshResult(AngConfigManager.SimpleRefreshResult.Success(count))
                        } else {
                            LogUtil.w(com.v2ray.ang.AppConfig.TAG, "Simple Aether feed returned non-empty content with no importable profiles; cache preserved")
                            SourceRefreshResult(AngConfigManager.SimpleRefreshResult.InvalidContent, AetherFailure.INVALID_CONTENT)
                        }
                    }
                }
                AetherFeedRepository.Result.LicenseMissing ->
                    SourceRefreshResult(AngConfigManager.SimpleRefreshResult.NetworkError, AetherFailure.LICENSE_MISSING).also {
                        LogUtil.w(com.v2ray.ang.AppConfig.TAG, "Simple Aether refresh skipped: license missing")
                    }
                AetherFeedRepository.Result.LicenseExpired ->
                    SourceRefreshResult(AngConfigManager.SimpleRefreshResult.NetworkError, AetherFailure.LICENSE_EXPIRED).also {
                        LogUtil.w(com.v2ray.ang.AppConfig.TAG, "Simple Aether refresh skipped: license expired")
                    }
                AetherFeedRepository.Result.Unauthorized ->
                    SourceRefreshResult(AngConfigManager.SimpleRefreshResult.NetworkError, AetherFailure.UNAUTHORIZED).also {
                        LogUtil.w(com.v2ray.ang.AppConfig.TAG, "Simple Aether refresh rejected: unauthorized")
                    }
                is AetherFeedRepository.Result.HttpError ->
                    SourceRefreshResult(AngConfigManager.SimpleRefreshResult.NetworkError, AetherFailure.HTTP_ERROR).also {
                        LogUtil.w(com.v2ray.ang.AppConfig.TAG, "Simple Aether refresh failed: HTTP ${result.code}")
                    }
                AetherFeedRepository.Result.NetworkError ->
                    SourceRefreshResult(AngConfigManager.SimpleRefreshResult.NetworkError, AetherFailure.NETWORK_ERROR)
            }
        }
        return SourceRefreshResult(AngConfigManager.refreshSimpleServerList(subscription))
    }

    /**
     * Refresh only the source currently displayed. This prevents a failure or
     * slow response from the other tab from blocking this tab's refresh/ping.
     */
    suspend fun refresh(context: Context, sourceId: String): RefreshOutcome {
        val source = primarySources.firstOrNull { it.id == sourceId }
            ?: return RefreshOutcome.CacheUnavailableAfterNetworkError()
        val refresh = refreshSource(source)
        when (refresh.result) {
            is AngConfigManager.SimpleRefreshResult.Success -> {
                return RefreshOutcome.Updated(sourceGuids(source).size, refresh.aetherFailure)
            }
            AngConfigManager.SimpleRefreshResult.EmptyResponse -> {
                clearSourceCache(source)
                if (primarySources.none(::sourceHasCachedProfiles) &&
                    emergencySource?.let(::sourceHasCachedProfiles) != true
                ) {
                    LauncherManager.stopService(context)
                }
                return RefreshOutcome.EmptiedByServer
            }
            AngConfigManager.SimpleRefreshResult.InvalidContent,
            AngConfigManager.SimpleRefreshResult.NetworkError -> {
                val explicitLicenseFailure = refresh.aetherFailure in setOf(
                    AetherFailure.LICENSE_MISSING,
                    AetherFailure.LICENSE_EXPIRED,
                    AetherFailure.UNAUTHORIZED,
                )
                // A stale but previously valid cache remains usable after a temporary
                // feed outage or malformed payload. Only an explicit entitlement failure
                // blocks the cached Aether feed.
                if (sourceHasCachedProfiles(source) && !explicitLicenseFailure) {
                    return RefreshOutcome.KeepingCacheAfterNetworkError
                }
                return RefreshOutcome.CacheUnavailableAfterNetworkError(refresh.aetherFailure)
            }
        }
    }

    /**
     * Refresh each primary independently. A bad/empty Aether response cannot
     * erase healthy V2Ray profiles (and vice versa). Only when neither primary
     * has fresh usable data is the optional emergency proxy attempted.
     */
    suspend fun refresh(context: Context): RefreshOutcome {
        var sawNetworkError = false
        var sawSuccessfulEmpty = false
        var aetherFailure: AetherFailure? = null

        primarySources.forEach { source ->
            val refresh = refreshSource(source)
            if (source.kind == SourceKind.AETHER) {
                aetherFailure = refresh.aetherFailure
                if (refresh.result == AngConfigManager.SimpleRefreshResult.NetworkError && aetherFailure == null) {
                    aetherFailure = AetherFailure.NETWORK_ERROR
                }
            }
            when (refresh.result) {
                is AngConfigManager.SimpleRefreshResult.Success -> Unit
                AngConfigManager.SimpleRefreshResult.EmptyResponse -> {
                    clearSourceCache(source)
                    sawSuccessfulEmpty = true
                }
                AngConfigManager.SimpleRefreshResult.InvalidContent,
                AngConfigManager.SimpleRefreshResult.NetworkError -> sawNetworkError = true
            }
        }

        val freshPrimaryGuids = primarySources
            .filter(::sourceHasFreshConfigs)
            .flatMap(::sourceGuids)
            .distinct()

        if (freshPrimaryGuids.isNotEmpty()) {
            return RefreshOutcome.Updated(freshPrimaryGuids.size, aetherFailure)
        }

        val fallback = emergencySource
        if (fallback != null) {
            when (refreshSource(fallback).result) {
                is AngConfigManager.SimpleRefreshResult.Success -> Unit
                AngConfigManager.SimpleRefreshResult.EmptyResponse -> {
                    clearSourceCache(fallback)
                    sawSuccessfulEmpty = true
                }
                AngConfigManager.SimpleRefreshResult.InvalidContent,
                AngConfigManager.SimpleRefreshResult.NetworkError -> sawNetworkError = true
            }
            val fallbackGuids = sourceGuids(fallback)
            if (fallbackGuids.isNotEmpty() && sourceHasFreshConfigs(fallback)) {
                return RefreshOutcome.Updated(fallbackGuids.size)
            }
        }

        val explicitAetherLicenseFailure = aetherFailure in setOf(
            AetherFailure.LICENSE_MISSING,
            AetherFailure.LICENSE_EXPIRED,
            AetherFailure.UNAUTHORIZED,
        )
        val cachedPrimaryGuids = primarySources
            .filter { source ->
                sourceHasCachedProfiles(source) &&
                    !(source.kind == SourceKind.AETHER && explicitAetherLicenseFailure)
            }
            .flatMap(::sourceGuids)
            .distinct()
        if (cachedPrimaryGuids.isNotEmpty()) {
            // Temporary network/HTTP/content errors do not invalidate a previously
            // downloaded profile. A definitive Aether entitlement failure blocks
            // only Aether, not the independent V2Ray source.
            return RefreshOutcome.Updated(cachedPrimaryGuids.size, aetherFailure)
        }

        val cachedFallbackGuids = if (primarySources.none(::sourceHasFreshConfigs)) {
            fallback?.takeIf(::sourceHasCachedProfiles)?.let(::sourceGuids).orEmpty()
        } else {
            emptyList()
        }
        if (cachedFallbackGuids.isNotEmpty()) {
            return RefreshOutcome.Updated(cachedFallbackGuids.size, aetherFailure)
        }

        if (sawNetworkError) {
            LauncherManager.stopService(context)
            return RefreshOutcome.CacheUnavailableAfterNetworkError(aetherFailure)
        }

        // Empty/invalid feeds are cleared per-source. Do not disconnect if another
        // source still has a usable cache.
        if (sawSuccessfulEmpty || allConfiguredSources().none { sourceGuids(it).isNotEmpty() }) {
            LauncherManager.stopService(context)
            return RefreshOutcome.EmptiedByServer
        }

        return RefreshOutcome.CacheUnavailableAfterNetworkError()
    }
}

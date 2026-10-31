package com.v2ray.ang.handler

import android.content.Context
import com.v2ray.ang.RemoteConfig
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.SubscriptionCache
import com.v2ray.ang.dto.entities.SubscriptionItem
import com.v2ray.ang.util.CountryFlagUtil

/**
 * Coordinates independent remote feeds for the simplified PARSCLOUD client.
 *
 * Each source has its own subscription ID and cache. Primary feeds are merged
 * for display; the emergency feed is consulted only when no fresh primary
 * configuration is available. The source selector/editor remains hidden from
 * regular users.
 */
object SimpleVpnRepository {

    /** Kept stable so existing installations migrate their old cache in place. */
    const val SIMPLE_SUB_ID = "simple_free_vpn"
    const val AETHER_SUB_ID = "simple_aether"
    const val EMERGENCY_SUB_ID = "simple_emergency"

    private data class Source(
        val id: String,
        val remarks: String,
        val url: String,
    )

    private val primarySources: List<Source>
        get() = listOf(
            Source(SIMPLE_SUB_ID, "PARSCLOUD • V2Ray", RemoteConfig.SERVER_LIST_URL),
            Source(AETHER_SUB_ID, "PARSCLOUD • Aether", RemoteConfig.AETHER_SERVER_LIST_URL),
        )

    /** Empty until a private server-side proxy is configured for the secret panel URL. */
    private val emergencySource: Source?
        get() = RemoteConfig.EMERGENCY_FALLBACK_URL
            .takeIf { it.isNotBlank() }
            ?.let { Source(EMERGENCY_SUB_ID, "PARSCLOUD • Backup", it) }

    data class SimpleServerUiModel(
        val guid: String,
        val displayName: String,
        val countryName: String?,
        val flagEmoji: String,
        /** null = not tested yet, -1 = failed/timeout, >=0 = ms */
        val delayMillis: Long?,
    )

    sealed class RefreshOutcome {
        data class Updated(val serverCount: Int) : RefreshOutcome()
        data object EmptiedByServer : RefreshOutcome()
        data object KeepingCacheAfterNetworkError : RefreshOutcome()
        data object CacheUnavailableAfterNetworkError : RefreshOutcome()
    }

    private fun ensureSubscription(source: Source): SubscriptionCache {
        val existing = MmkvManager.decodeSubscription(source.id)
        val subItem = existing ?: SubscriptionItem(
            remarks = source.remarks,
            url = source.url,
            enabled = true,
            autoUpdate = false,
        )
        if (existing == null || existing.url != source.url || existing.remarks != source.remarks) {
            subItem.url = source.url
            subItem.remarks = source.remarks
            subItem.enabled = true
            subItem.autoUpdate = false
            MmkvManager.encodeSubscription(source.id, subItem)
        }
        return SubscriptionCache(source.id, subItem)
    }

    /** Backwards-compatible initializer used by the existing screen. */
    fun ensureSubscription(): SubscriptionCache = ensureSubscription(primarySources.first())

    private fun allConfiguredSources(): List<Source> =
        primarySources + listOfNotNull(emergencySource)

    private fun isCacheExpired(sub: SubscriptionItem?): Boolean {
        if (sub == null || sub.lastUpdated <= 0L) return true
        val expiryMillis = RemoteConfig.REMOTE_DATA_EXPIRY_MINUTES * 60_000L
        return System.currentTimeMillis() - sub.lastUpdated > expiryMillis
    }

    private fun sourceHasFreshConfigs(source: Source): Boolean {
        val sub = MmkvManager.decodeSubscription(source.id) ?: return false
        return MmkvManager.decodeServerList(source.id).isNotEmpty() && !isCacheExpired(sub)
    }

    private fun sourceGuids(source: Source): List<String> =
        MmkvManager.decodeServerList(source.id).toList()

    /**
     * Primary feeds are always shown together when fresh. The emergency feed is
     * hidden unless all primary feeds lack fresh usable data.
     */
    private fun visibleSourceIds(): List<String> {
        val freshPrimary = primarySources.filter(::sourceHasFreshConfigs)
        if (freshPrimary.isNotEmpty()) return freshPrimary.map { it.id }

        emergencySource?.takeIf(::sourceHasFreshConfigs)?.let { return listOf(it.id) }

        // Preserve stale cached entries for visibility/diagnostics, but canConnect()
        // remains false until a source has a fresh cache.
        val cachedPrimary = primarySources.filter { sourceGuids(it).isNotEmpty() }
        if (cachedPrimary.isNotEmpty()) return cachedPrimary.map { it.id }
        return listOfNotNull(emergencySource?.takeIf { sourceGuids(it).isNotEmpty() }?.id)
    }

    fun getServerGuids(): List<String> {
        val ids = visibleSourceIds()
        return ids.flatMap { MmkvManager.decodeServerList(it) }.distinct()
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
        )
    }

    fun getServers(): List<SimpleServerUiModel> = getServerGuids().mapNotNull(::getServerUiModel)

    fun sortByPing() {
        visibleSourceIds().forEach(AngConfigManager::sortByTestResultsForSub)
    }

    fun canConnect(): Boolean = visibleSourceIds().any { id ->
        val source = allConfiguredSources().firstOrNull { it.id == id } ?: return@any false
        sourceHasFreshConfigs(source)
    }

    private fun clearSourceCache(source: Source) {
        MmkvManager.removeServers(sourceGuids(source), source.id)
        MmkvManager.decodeSubscription(source.id)?.let { sub ->
            sub.lastUpdated = -1L
            MmkvManager.encodeSubscription(source.id, sub)
        }
    }

    private fun refreshSource(source: Source): AngConfigManager.SimpleRefreshResult {
        val subscription = ensureSubscription(source)
        val requestHeaders = if (source.id == EMERGENCY_SUB_ID) {
            val license = SubscriptionLicenseRepository.getStoredLicense()
                ?.takeUnless(SubscriptionLicenseRepository::isExpired)
                ?: return AngConfigManager.SimpleRefreshResult.NetworkError
            """{"Authorization":"Bearer ${license.key}"}"""
        } else {
            null
        }
        return AngConfigManager.refreshSimpleServerList(subscription, requestHeaders)
    }

    /**
     * Refresh each primary independently. A bad/empty Aether response cannot
     * erase healthy V2Ray profiles (and vice versa). Only when neither primary
     * has fresh usable data is the optional emergency proxy attempted.
     */
    fun refresh(context: Context): RefreshOutcome {
        var sawNetworkError = false
        var sawSuccessfulEmpty = false

        primarySources.forEach { source ->
            when (refreshSource(source)) {
                is AngConfigManager.SimpleRefreshResult.Success -> Unit
                AngConfigManager.SimpleRefreshResult.EmptyOrInvalid -> {
                    clearSourceCache(source)
                    sawSuccessfulEmpty = true
                }
                AngConfigManager.SimpleRefreshResult.NetworkError -> sawNetworkError = true
            }
        }

        val freshPrimaryGuids = primarySources
            .filter(::sourceHasFreshConfigs)
            .flatMap(::sourceGuids)
            .distinct()

        if (freshPrimaryGuids.isNotEmpty()) {
            return RefreshOutcome.Updated(freshPrimaryGuids.size)
        }

        val fallback = emergencySource
        if (fallback != null) {
            when (refreshSource(fallback)) {
                is AngConfigManager.SimpleRefreshResult.Success -> Unit
                AngConfigManager.SimpleRefreshResult.EmptyOrInvalid -> {
                    clearSourceCache(fallback)
                    sawSuccessfulEmpty = true
                }
                AngConfigManager.SimpleRefreshResult.NetworkError -> sawNetworkError = true
            }
            val fallbackGuids = sourceGuids(fallback)
            if (fallbackGuids.isNotEmpty() && sourceHasFreshConfigs(fallback)) {
                return RefreshOutcome.Updated(fallbackGuids.size)
            }
        }

        val anyFreshCache = allConfiguredSources().any(::sourceHasFreshConfigs)
        if (anyFreshCache) {
            return RefreshOutcome.Updated(getServerGuids().size)
        }

        if (sawNetworkError) {
            // A stale cache may remain visible for diagnostics, but it must never
            // be treated as a usable offline cache after its expiry window.
            LauncherManager.stopService(context)
            return RefreshOutcome.CacheUnavailableAfterNetworkError
        }

        // Empty/invalid feeds are cleared per-source. Do not disconnect if another
        // source still has a usable cache.
        if (sawSuccessfulEmpty || allConfiguredSources().none { sourceGuids(it).isNotEmpty() }) {
            LauncherManager.stopService(context)
            return RefreshOutcome.EmptiedByServer
        }

        return RefreshOutcome.CacheUnavailableAfterNetworkError
    }
}

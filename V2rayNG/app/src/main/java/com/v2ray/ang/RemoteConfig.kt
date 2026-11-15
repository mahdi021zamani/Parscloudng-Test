package com.v2ray.ang

/**
 * Remote endpoints used by the simplified PARSCLOUD client.
 *
 * Aether is intentionally a public, read-only GitHub raw feed. No repository
 * credential or customer subscription key is embedded in the APK.
 */
object RemoteConfig {

    /** V2Ray / irancell share-link feed. Kept on the legacy ID for cache migration. */
    const val SERVER_LIST_URL = "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"

    /** V2Ray / Hamrah share-link feed owned by PARSCLOUD. */
    const val HAMRAH_SERVER_LIST_URL = "https://raw.githubusercontent.com/mahdi021zamani/parscloud-sub/main/HAMRAH"

    /** WireGuard / Aether feed; direct GitHub Raw access, bypassing the Worker. */
    const val AETHER_SERVER_LIST_URL = "https://raw.githubusercontent.com/mahdi021zamani/parscloud-sub/main/Aether"

    /** Combined Aether feed; independent cache and profile selection. */
    const val COMBINED_AETHER_SERVER_LIST_URL = "https://raw.githubusercontent.com/mahdi021zamani/parscloud-sub/main/%D8%AA%D8%B1%DA%A9%DB%8C%D8%A8%DB%8C"

    /** Full custom Xray JSON profiles for Services for Iran. */
    const val SERVICES_FOR_IRAN_URL = "https://raw.githubusercontent.com/mahdi021zamani/parscloud-sub/main/Services%20for%20Iran"

    /**
     * Optional private proxy endpoint that returns the emergency paid-panel feed.
     * Keep blank until the secret source URL is configured server-side. Never put
     * the original tokenized subscription URL in the APK or a public repository.
     */
    const val EMERGENCY_FALLBACK_URL = "https://parscloud-subscription-server.mahdi021zamani.workers.dev/v1/fallback-config"

    /** HTTPS endpoint used to verify each customer subscription key. */
    const val SUBSCRIPTION_VALIDATE_URL = "https://parscloud-subscription-server.mahdi021zamani.workers.dev/v1/validate"

    /** How long a successfully downloaded source remains connectable. */
    const val REMOTE_DATA_EXPIRY_MINUTES = 30

    /** Network timeout, in seconds, used when downloading source lists. */
    const val REQUEST_TIMEOUT_SECONDS = 15
}

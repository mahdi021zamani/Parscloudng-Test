package com.v2ray.ang

/**
 * Remote endpoints used by the simplified PARSCLOUD client.
 *
 * Public source URLs may live here. Secret-bearing subscription URLs must NOT be
 * embedded in the APK; the emergency source is intentionally supplied by a
 * private server-side proxy when that endpoint is configured.
 */
object RemoteConfig {

    /** Primary V2Ray-compatible share-link feed. */
    const val SERVER_LIST_URL = "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"

    /** Independent Aether feed. Keep this URL token-free and publicly readable. */
    const val AETHER_SERVER_LIST_URL = "https://raw.githubusercontent.com/mahdi021zamani/parscloud-sub/main/Aether"

    /**
     * Optional private proxy endpoint that returns the emergency paid-panel feed.
     * Keep blank until the secret source URL is configured server-side. Never put
     * the original tokenized subscription URL in the APK or a public repository.
     */
    const val EMERGENCY_FALLBACK_URL = ""

    /** HTTPS endpoint used to verify each customer subscription key. */
    const val SUBSCRIPTION_VALIDATE_URL = "https://parscloud-subscription-server.mahdi021zamani.workers.dev/v1/validate"

    /** How long a successfully downloaded source remains connectable. */
    const val REMOTE_DATA_EXPIRY_MINUTES = 30

    /** Network timeout, in seconds, used when downloading source lists. */
    const val REQUEST_TIMEOUT_SECONDS = 15
}

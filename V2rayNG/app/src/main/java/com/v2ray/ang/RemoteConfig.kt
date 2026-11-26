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

    /** Worker base URL is compile-time configurable for isolated test builds; production is the default. */
    private val SUBSCRIPTION_WORKER_BASE_URL = BuildConfig.SUBSCRIPTION_WORKER_BASE_URL.trimEnd('/')

    /** One-time challenge endpoint for a Keystore-backed installation identity. */
    val SUBSCRIPTION_DEVICE_CHALLENGE_URL = "$SUBSCRIPTION_WORKER_BASE_URL/v1/device-challenge"

    /** HTTPS endpoint used to verify each customer subscription key and device proof. */
    val SUBSCRIPTION_VALIDATE_URL = "$SUBSCRIPTION_WORKER_BASE_URL/v1/validate"

    /** How long a successfully downloaded source remains connectable. */
    const val REMOTE_DATA_EXPIRY_MINUTES = 30

    /** Network timeout, in seconds, used when downloading source lists. */
    const val REQUEST_TIMEOUT_SECONDS = 15
}

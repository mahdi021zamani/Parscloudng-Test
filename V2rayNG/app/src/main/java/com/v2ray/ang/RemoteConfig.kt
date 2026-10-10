package com.v2ray.ang

/**
 * Central configuration for the simplified "Free VPN" client screen.
 *
 * This is the ONLY file you need to edit to change where the app downloads its
 * server list from, or how long a downloaded list stays valid before the app
 * requires a fresh copy.
 *
 * After editing [SERVER_LIST_URL] (or the other values below), just rebuild the
 * APK — see the "Changing the server source" section in README.md for the exact
 * GitHub Actions steps.
 */
object RemoteConfig {

    /**
     * Raw-text URL of the server list.
     *
     * Must point to a plain-text file (for example a "raw" GitHub URL) where each
     * line is one share-link config, e.g.:
     *
     *   vless://...
     *   vless://...
     *
     * Empty lines and invalid lines are ignored automatically.
     */
    const val SERVER_LIST_URL = "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"

    /**
     * How many minutes a successfully downloaded server list stays valid.
     *
     * While the cached list is within this window, the app is allowed to connect
     * using it even without a fresh download. Once it expires, the app requires a
     * successful refresh before allowing a new connection (see
     * SimpleVpnRepository.canConnect()).
     */
    const val REMOTE_DATA_EXPIRY_MINUTES = 30

    /**
     * Network timeout, in seconds, used when downloading the server list.
     */
    const val REQUEST_TIMEOUT_SECONDS = 15
}

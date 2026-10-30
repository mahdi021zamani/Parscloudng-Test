package com.v2ray.ang.enums

/** Protocol names shared by Aether profile links and runtime configuration. */
enum class AetherProtocol(val type: String, val coreProtocol: String = type) {
    MASQUE("masque"), WIREGUARD("wg"), GOOL("gool"), MIM("mim"), WG_OVER_MASQUE("wg-over-masque", "gool");
    val overMasque get() = this == MASQUE || this == MIM || this == WG_OVER_MASQUE
    val twoHops get() = this == GOOL || this == MIM || this == WG_OVER_MASQUE
    val distinctHops get() = this == GOOL || this == MIM
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: WIREGUARD }
}
enum class AetherTransport(val type: String) {
    HTTP3("h3"), HTTP2("h2");
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: HTTP3 }
}
enum class AetherScanMode(val type: String) {
    TURBO("turbo"), BALANCED("balanced"), THOROUGH("thorough"), VERIFIED("verified"), IRONCLAD("ironclad");
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: if (value == "stealth") VERIFIED else BALANCED }
}
enum class AetherObfuscation(val type: String) {
    AUTO("auto"), OFF("off"), LIGHT("light"), FIREWALL("firewall"), BALANCED("balanced"), GFW("gfw"), AGGRESSIVE("aggressive");
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: AUTO }
}
enum class AetherIpVersion(val type: String) {
    V4("v4"), V6("v6"), DUAL("both");
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: V4 }
}
enum class AetherPsiphon(val type: String) {
    OFF("off"), CHAIN("chain"), REVERSE("reverse"), ONLY("only");
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: OFF }
}
enum class AetherPsiphonMode(val type: String) {
    AUTO("auto"), CDN("cdn"), DIRECT("direct");
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: AUTO }
}
enum class AetherTor(val type: String) {
    OFF("off"), CHAIN("chain"), REVERSE("reverse"), ONLY("only");
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: OFF }
}
enum class AetherTorBridges(val type: String) {
    AUTO("auto"), FIRST("first"), NEVER("never"), OWN("own");
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: AUTO }
}
enum class AetherTorRelays(val type: String) {
    AUTO("auto"), ONLY("only"), OFF("off");
    companion object { fun fromString(value: String?) = entries.firstOrNull { it.type == value } ?: AUTO }
}
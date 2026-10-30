package com.v2ray.ang.dto

import com.v2ray.ang.core.AetherCore

data class ConfigResult(
    var status: Boolean,
    var guid: String? = null,
    var content: String = "",
    var errorMessage: String = "",
    /** Aether runtime needed by the selected Aether profile. */
    var aetherCore: AetherCore? = null,
)


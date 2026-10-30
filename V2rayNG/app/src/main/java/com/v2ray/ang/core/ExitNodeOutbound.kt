package com.v2ray.ang.core

import com.v2ray.ang.dto.V2rayConfig.OutboundBean

/** Result of resolving a profile selected as an Aether core exit node. */
sealed interface ExitNodeOutbound {
    data class Built(val outbound: OutboundBean, val content: String? = null) : ExitNodeOutbound
    data object NoOutbound : ExitNodeOutbound
    data object NotFound : ExitNodeOutbound
    data object SameName : ExitNodeOutbound
    data object EchUnusable : ExitNodeOutbound
}
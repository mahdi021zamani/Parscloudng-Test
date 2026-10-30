package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem
import com.v2ray.ang.fmt.AetherFmt

/** Editor for Aether WARP profiles. Values are stored in ProfileItem and round-trip through aether:// links. */
class ServerAetherActivity : BaseServerActivity() {
    override val serverConfigType: EConfigType = EConfigType.AETHER

    @Composable
    override fun ScreenContent() {
        val state = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(initialConfig)
        }.apply { configType = EConfigType.AETHER }

        ServerEditorScaffold(
            title = "Aether",
            onSaveClick = { saveServer(state) }
        ) {
            CommonBasicFields(state)
            FormDropdownField(stringResource(R.string.aether_editor_protocol), state.aetherProtocol,
                listOf("wg", "masque", "gool", "mim", "wg-over-masque"), { state.aetherProtocol = it })
            if (state.aetherProtocol in listOf("masque", "mim", "wg-over-masque")) {
                FormDropdownField(stringResource(R.string.aether_editor_transport), state.aetherTransport,
                    listOf("h3", "h2"), { state.aetherTransport = it })
                FormTextField(stringResource(R.string.aether_editor_sni), state.aetherMasqueSni, { state.aetherMasqueSni = it })
                SettingsSwitchItem(title = stringResource(R.string.aether_editor_fragment), checked = state.aetherFragment,
                    onCheckedChange = { state.aetherFragment = it })
                if (state.aetherFragment) {
                    FormTextField(stringResource(R.string.aether_editor_fragment_size), state.aetherFragmentSize, { state.aetherFragmentSize = it })
                    FormTextField(stringResource(R.string.aether_editor_fragment_delay), state.aetherFragmentDelay, { state.aetherFragmentDelay = it })
                }
                SettingsSwitchItem(title = stringResource(R.string.aether_editor_ech), checked = state.aetherEch,
                    onCheckedChange = { state.aetherEch = it })
                if (state.aetherEch) {
                    FormTextField(stringResource(R.string.aether_editor_ech_dns), state.aetherEchDns, { state.aetherEchDns = it })
                    FormTextField(stringResource(R.string.aether_editor_ech_domain), state.aetherEchDomain, { state.aetherEchDomain = it })
                }
                FormDropdownField(stringResource(R.string.aether_editor_fingerprint), state.aetherFingerprint,
                    listOf("chrome", "firefox", "safari", "random"), { state.aetherFingerprint = it })
            }
            if (state.aetherProtocol in listOf("gool", "mim", "wg-over-masque")) {
                FormTextField(stringResource(R.string.aether_editor_outer), state.aetherWiwOuter, { state.aetherWiwOuter = it })
                FormTextField(stringResource(R.string.aether_editor_inner), state.aetherWiwInner, { state.aetherWiwInner = it })
            }
            FormDropdownField(stringResource(R.string.aether_editor_scan), state.aetherScanMode,
                listOf("turbo", "balanced", "thorough", "verified", "ironclad"), { state.aetherScanMode = it })
            FormDropdownField(stringResource(R.string.aether_editor_obfuscation), state.aetherObfuscation,
                listOf("auto", "off", "light", "firewall", "balanced", "gfw", "aggressive"), { state.aetherObfuscation = it })
            FormDropdownField(stringResource(R.string.aether_editor_ip_version), state.aetherIpVersion,
                listOf("v4", "v6", "both"), { state.aetherIpVersion = it })
            FormTextField(stringResource(R.string.aether_editor_dns), state.aetherDns, { state.aetherDns = it })
            FormTextField(stringResource(R.string.aether_editor_exit_loc), state.aetherExitLoc, { state.aetherExitLoc = it })
            FormDropdownField(stringResource(R.string.aether_editor_psiphon), state.aetherPsiphon,
                listOf("off", "chain", "reverse", "only"), { state.aetherPsiphon = it })
            if (state.aetherPsiphon != "off") {
                FormDropdownField(stringResource(R.string.aether_editor_psiphon_mode), state.aetherPsiphonMode,
                    listOf("auto", "cdn", "direct"), { state.aetherPsiphonMode = it })
                FormTextField(stringResource(R.string.aether_editor_psiphon_cdn_ips), state.aetherPsiphonCdnIps, { state.aetherPsiphonCdnIps = it })
                FormTextField(stringResource(R.string.aether_editor_psiphon_cdn_sni), state.aetherPsiphonCdnSni, { state.aetherPsiphonCdnSni = it })
                FormTextField(stringResource(R.string.aether_editor_psiphon_cdn_sets), state.aetherPsiphonCdnSets, { state.aetherPsiphonCdnSets = it })
                FormTextField(stringResource(R.string.aether_editor_psiphon_region), state.aetherPsiphonRegion, { state.aetherPsiphonRegion = it })
            }
            FormDropdownField(stringResource(R.string.aether_editor_tor), state.aetherTor,
                listOf("off", "chain", "reverse", "only"), { state.aetherTor = it })
            if (state.aetherTor != "off") {
                FormDropdownField(stringResource(R.string.aether_editor_tor_bridges), state.aetherTorBridges,
                    listOf("auto", "first", "never"), { state.aetherTorBridges = it })
                FormTextField(stringResource(R.string.aether_editor_tor_bridge_lines), state.aetherTorBridgeLines, { state.aetherTorBridgeLines = it })
                FormTextField(stringResource(R.string.aether_editor_tor_relays), state.aetherTorRelays, { state.aetherTorRelays = it })
            }
            FormTextField(stringResource(R.string.aether_editor_command), state.aetherCommand, { state.aetherCommand = it })
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (!config.aetherExitNode.isNullOrBlank() || !config.finalMask.isNullOrBlank() || !config.dialMode.isNullOrBlank()) {
            toast(R.string.aether_exit_settings_unsupported)
            return false
        }
        if (AetherFmt.normalize(config) != null) {
            toast(R.string.aether_profile_invalid)
            return false
        }
        return true
    }
}

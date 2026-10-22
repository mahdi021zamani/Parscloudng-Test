package com.v2ray.ang.ui.simple

import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.base.BaseComponentActivity

/**
 * Simplified single-screen "Free VPN" client: server list + Refresh + Connect/Disconnect.
 *
 * This is the app's launcher activity (see AndroidManifest.xml). It intentionally does not
 * expose manual config entry, QR/clipboard/file import, subscription-URL editing, or config
 * export — the user can only view servers, refresh, select one, and connect/disconnect.
 * All of that advanced functionality still exists in the app (ui.main.MainActivity and
 * friends) and is reused under the hood; it's just not reachable from this screen.
 */
class SimpleVpnActivity : BaseComponentActivity() {

    private val viewModel: SimpleVpnViewModel by viewModels()

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.confirmConnect()
            } else {
                viewModel.cancelConnectAttempt()
            }
        }

    override fun onResume() {
        super.onResume()
        viewModel.onAppResumed()
    }

    private fun requestPermissionAndConnect() {
        if (!SettingsManager.isVpnMode()) {
            viewModel.confirmConnect()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) {
            viewModel.confirmConnect()
        } else {
            requestVpnPermission.launch(intent)
        }
    }

    @Composable
    override fun ScreenContent() {
        SimpleVpnScreen(
            viewModel = viewModel,
            onConnectClick = { viewModel.onToggleConnect { requestPermissionAndConnect() } },
        )
    }
}

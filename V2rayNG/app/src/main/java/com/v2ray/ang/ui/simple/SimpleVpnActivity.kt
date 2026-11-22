package com.v2ray.ang.ui.simple

import android.content.Intent
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.ui.ScannerActivity
import com.v2ray.ang.ui.base.BaseComponentActivity
import com.v2ray.ang.util.LogUtil

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

    private val scanQr =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra("SCAN_RESULT")?.let(viewModel::importPersonalConfig)
            }
        }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                viewModel.confirmConnect()
            } else {
                viewModel.cancelConnectAttempt()
            }
        }

    override fun onStart() {
        super.onStart()
        LogUtil.w(message = "IMPORT_TRACE Activity onStart taskId=$taskId")
    }

    override fun onResume() {
        super.onResume()
        LogUtil.w(message = "IMPORT_TRACE Activity onResume taskId=$taskId")
        viewModel.onAppResumed()
    }

    override fun onPause() {
        LogUtil.w(message = "IMPORT_TRACE Activity onPause finishing=$isFinishing changingConfig=$isChangingConfigurations")
        super.onPause()
    }

    override fun onStop() {
        LogUtil.w(message = "IMPORT_TRACE Activity onStop finishing=$isFinishing changingConfig=$isChangingConfigurations")
        super.onStop()
    }

    override fun onUserLeaveHint() {
        LogUtil.w(message = "IMPORT_TRACE Activity onUserLeaveHint")
        super.onUserLeaveHint()
    }

    override fun onDestroy() {
        LogUtil.w(message = "IMPORT_TRACE Activity onDestroy finishing=$isFinishing changingConfig=$isChangingConfigurations")
        super.onDestroy()
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
            onScanClick = { scanQr.launch(Intent(this, ScannerActivity::class.java)) },
        )
    }
}

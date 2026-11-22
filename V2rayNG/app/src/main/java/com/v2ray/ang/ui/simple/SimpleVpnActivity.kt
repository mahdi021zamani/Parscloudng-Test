package com.v2ray.ang.ui.simple

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.widget.Toast
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
    private val diagnosticInstanceId = Integer.toHexString(System.identityHashCode(this))

    private val scanQr =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            ImportDiagnosticLog.record(this@SimpleVpnActivity, "Activity scan result resultCode=${result.resultCode} instance=$diagnosticInstanceId")
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra("SCAN_RESULT")?.let(viewModel::importPersonalConfig)
            }
        }

    private val exportImportDiagnostic =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            ImportDiagnosticLog.record(this@SimpleVpnActivity, "Activity diagnostic export result uriSelected=${uri != null} instance=$diagnosticInstanceId")
            if (uri != null) {
                val result = runCatching {
                    val output = contentResolver.openOutputStream(uri)
                        ?: error("Unable to open selected destination")
                    output.bufferedWriter(Charsets.UTF_8).use {
                        it.write(ImportDiagnosticLog.read(this@SimpleVpnActivity))
                    }
                }
                Toast.makeText(
                    this,
                    if (result.isSuccess) "گزارش ذخیره شد" else "ذخیره گزارش ناموفق بود",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            ImportDiagnosticLog.record(this@SimpleVpnActivity, "Activity VPN permission result resultCode=${it.resultCode} instance=$diagnosticInstanceId")
            if (it.resultCode == RESULT_OK) {
                viewModel.confirmConnect()
            } else {
                viewModel.cancelConnectAttempt()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ImportDiagnosticLog.record(this, "Activity onCreate taskId=$taskId instance=$diagnosticInstanceId savedState=${savedInstanceState != null} intentAction=${intent?.action}")
        LogUtil.w(message = "IMPORT_TRACE Activity onCreate taskId=$taskId instance=$diagnosticInstanceId savedState=${savedInstanceState != null}")
    }

    override fun onRestart() {
        super.onRestart()
        ImportDiagnosticLog.record(this, "Activity onRestart taskId=$taskId instance=$diagnosticInstanceId")
        LogUtil.w(message = "IMPORT_TRACE Activity onRestart taskId=$taskId instance=$diagnosticInstanceId")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ImportDiagnosticLog.record(this, "Activity onNewIntent taskId=$taskId instance=$diagnosticInstanceId action=${intent.action} flags=${intent.flags}")
        LogUtil.w(message = "IMPORT_TRACE Activity onNewIntent taskId=$taskId instance=$diagnosticInstanceId action=${intent.action}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        ImportDiagnosticLog.record(this, "Activity windowFocus=$hasFocus taskId=$taskId instance=$diagnosticInstanceId")
    }

    override fun onStart() {
        super.onStart()
        ImportDiagnosticLog.record(this, "Activity onStart taskId=$taskId instance=$diagnosticInstanceId")
        LogUtil.w(message = "IMPORT_TRACE Activity onStart taskId=$taskId instance=$diagnosticInstanceId")
    }

    override fun onResume() {
        super.onResume()
        ImportDiagnosticLog.record(this, "Activity onResume taskId=$taskId instance=$diagnosticInstanceId")
        LogUtil.w(message = "IMPORT_TRACE Activity onResume taskId=$taskId instance=$diagnosticInstanceId")
        viewModel.onAppResumed()
    }

    override fun onPause() {
        ImportDiagnosticLog.record(this, "Activity onPause finishing=$isFinishing changingConfig=$isChangingConfigurations taskId=$taskId instance=$diagnosticInstanceId hasFocus=$hasWindowFocus()")
        LogUtil.w(message = "IMPORT_TRACE Activity onPause finishing=$isFinishing changingConfig=$isChangingConfigurations taskId=$taskId instance=$diagnosticInstanceId")
        super.onPause()
    }

    override fun onStop() {
        ImportDiagnosticLog.record(this, "Activity onStop finishing=$isFinishing changingConfig=$isChangingConfigurations taskId=$taskId instance=$diagnosticInstanceId")
        LogUtil.w(message = "IMPORT_TRACE Activity onStop finishing=$isFinishing changingConfig=$isChangingConfigurations taskId=$taskId instance=$diagnosticInstanceId")
        super.onStop()
    }

    override fun onUserLeaveHint() {
        ImportDiagnosticLog.record(this, "Activity onUserLeaveHint taskId=$taskId instance=$diagnosticInstanceId")
        LogUtil.w(message = "IMPORT_TRACE Activity onUserLeaveHint taskId=$taskId instance=$diagnosticInstanceId")
        super.onUserLeaveHint()
    }

    override fun onDestroy() {
        ImportDiagnosticLog.record(this, "Activity onDestroy finishing=$isFinishing changingConfig=$isChangingConfigurations taskId=$taskId instance=$diagnosticInstanceId")
        LogUtil.w(message = "IMPORT_TRACE Activity onDestroy finishing=$isFinishing changingConfig=$isChangingConfigurations taskId=$taskId instance=$diagnosticInstanceId")
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
            onExportDiagnosticClick = { exportImportDiagnostic.launch("PARSCLOUD_import_diagnostic.txt") },
        )
    }
}

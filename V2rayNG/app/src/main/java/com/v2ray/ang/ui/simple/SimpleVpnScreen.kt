package com.v2ray.ang.ui.simple

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.handler.SimpleVpnRepository.SimpleServerUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleVpnScreen(
    viewModel: SimpleVpnViewModel,
    onConnectClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }


    if (uiState.licenseDialogVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissLicenseDialog,
            title = { Text(stringResource(R.string.simple_license_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .imePadding(),
                ) {
                    Text(stringResource(R.string.simple_license_prompt))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = uiState.licenseKeyInput,
                        onValueChange = viewModel::onLicenseKeyInputChanged,
                        label = { Text(stringResource(R.string.simple_license_key_hint)) },
                        enabled = !uiState.isValidatingLicense,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (uiState.isValidatingLicense) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.simple_license_checking))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = viewModel::activateLicense,
                    enabled = !uiState.isValidatingLicense && uiState.licenseKeyInput.isNotBlank(),
                ) {
                    Text(
                        if (uiState.isValidatingLicense) {
                            stringResource(R.string.simple_license_checking)
                        } else {
                            stringResource(R.string.simple_license_activate)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = viewModel::dismissLicenseDialog,
                    enabled = !uiState.isValidatingLicense,
                ) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.simple_app_title)) },
                actions = {
                    TextButton(onClick = { viewModel.showLicenseDialog() }) {
                        Text(stringResource(R.string.simple_license_manage))
                    }
                    TextButton(onClick = { viewModel.onRefresh() }, enabled = !uiState.isRefreshing) {
                        Text(stringResource(R.string.simple_refresh))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f)) {
                if (uiState.isRefreshing && uiState.servers.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (uiState.servers.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.simple_no_servers_available))
                    }
                } else {
                    ServerList(
                        servers = uiState.servers,
                        selectedGuid = uiState.selectedGuid,
                        testingGuids = uiState.testingGuids,
                        enabled = uiState.status == SimpleConnectionStatus.DISCONNECTED,
                        onSelect = viewModel::onSelectServer,
                    )
                }
            }

            HorizontalDivider()

            ConnectionBar(
                status = uiState.status,
                canConnect = uiState.canConnect && uiState.selectedGuid != null,
                onConnectClick = onConnectClick,
            )
        }
    }
}

@Composable
private fun ServerList(
    servers: List<SimpleServerUiModel>,
    selectedGuid: String?,
    testingGuids: Set<String>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(servers, key = { it.guid }) { server ->
            ServerRow(
                server = server,
                selected = server.guid == selectedGuid,
                testing = server.guid in testingGuids,
                enabled = enabled,
                onSelect = { onSelect(server.guid) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun ServerRow(
    server: SimpleServerUiModel,
    selected: Boolean,
    testing: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, enabled = enabled, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        if (server.flagEmoji.isNotEmpty()) {
            Text(server.flagEmoji)
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = server.displayName,
            modifier = Modifier.weight(1f),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        DelayLabel(delayMillis = server.delayMillis, testing = testing)
    }
}

@Composable
private fun DelayLabel(delayMillis: Long?, testing: Boolean) {
    val text = when {
        testing || delayMillis == null -> stringResource(R.string.simple_testing)
        delayMillis < 0 -> stringResource(R.string.simple_timeout)
        else -> stringResource(R.string.simple_delay_ms, delayMillis)
    }
    Text(text)
}

@Composable
private fun ConnectionBar(
    status: SimpleConnectionStatus,
    canConnect: Boolean,
    onConnectClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        val statusText = when (status) {
            SimpleConnectionStatus.DISCONNECTED -> stringResource(R.string.simple_status_disconnected)
            SimpleConnectionStatus.CONNECTING -> stringResource(R.string.simple_status_connecting)
            SimpleConnectionStatus.CONNECTED -> stringResource(R.string.simple_status_connected)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(statusText)
            if (status == SimpleConnectionStatus.CONNECTING) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp).padding(start = 8.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onConnectClick,
            enabled = status != SimpleConnectionStatus.CONNECTING && (status == SimpleConnectionStatus.CONNECTED || canConnect),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                if (status == SimpleConnectionStatus.DISCONNECTED)
                    stringResource(R.string.simple_connect)
                else
                    stringResource(R.string.simple_disconnect)
            )
        }
    }
}

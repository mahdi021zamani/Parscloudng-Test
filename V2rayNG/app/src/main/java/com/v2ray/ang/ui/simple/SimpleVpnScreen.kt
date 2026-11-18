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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.SimpleVpnRepository
import com.v2ray.ang.handler.SimpleVpnRepository.SimpleServerUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleVpnScreen(
    viewModel: SimpleVpnViewModel,
    onConnectClick: () -> Unit,
    onScanClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPersonalImport by remember { mutableStateOf(false) }
    var personalText by remember { mutableStateOf("") }
    var pendingDeleteSubscriptionId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteAllPersonal by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(uiState.message) {
        uiState.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissMessage()
        }
    }


    if (showPersonalImport) {
        AlertDialog(
            onDismissRequest = { showPersonalImport = false },
            title = { Text("افزودن کانفیگ شخصی") },
            text = {
                Column {
                    OutlinedTextField(
                        value = personalText,
                        onValueChange = { personalText = it },
                        label = { Text("لینک یا متن کانفیگ") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { personalText = clipboardManager.getText()?.text.orEmpty() }) {
                            Text("چسباندن از کلیپ‌بورد")
                        }
                        TextButton(onClick = {
                            showPersonalImport = false
                            onScanClick()
                        }) { Text("اسکن QR") }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.importPersonalConfig(personalText)
                    personalText = ""
                    showPersonalImport = false
                }, enabled = personalText.isNotBlank()) { Text("افزودن") }
            },
            dismissButton = { TextButton(onClick = { showPersonalImport = false }) { Text("انصراف") } },
        )
    }

    if (pendingDeleteAllPersonal) {
        AlertDialog(
            onDismissRequest = { pendingDeleteAllPersonal = false },
            title = { Text("حذف همه کانفیگ‌های شخصی") },
            text = { Text("تمام اشتراک‌ها و کانفیگ‌های دستی این بخش حذف می‌شوند. منابع اصلی برنامه دست‌نخورده می‌مانند.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllPersonalConfigs()
                    pendingDeleteAllPersonal = false
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteAllPersonal = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    pendingDeleteSubscriptionId?.let { subscriptionId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteSubscriptionId = null },
            title = { Text(stringResource(R.string.action_delete)) },
            text = { Text(stringResource(R.string.confirm_delete_subscription_group)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deletePersonalSubscription(subscriptionId)
                    pendingDeleteSubscriptionId = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteSubscriptionId = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
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
        containerColor = Color(0xFF0D0C12),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "PARSCLOUD",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                            color = Color(0xFFF5EAF0),
                            maxLines = 1,
                        )
                        Text(
                            "اتصال امن و پایدار",
                            fontSize = 10.sp,
                            color = Color(0xFFAAA0AC),
                            maxLines = 1,
                        )
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121117),
                    titleContentColor = Color(0xFFF5EAF0),
                ),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF29232B),
                    contentColor = Color(0xFFFFF6FA),
                    shape = RoundedCornerShape(20.dp),
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0D0C12))
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF17151D))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(
                    onClick = { viewModel.showLicenseDialog() },
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                ) { Text("اشتراک", fontSize = 11.sp, color = Color(0xFFE9B0C8)) }

                if (uiState.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID ||
                    uiState.selectedSourceId.startsWith("personal_sub_")) {
                    TextButton(
                        onClick = { viewModel.onRefresh() },
                        enabled = !uiState.isRefreshing &&
                            uiState.selectedSourceId !in uiState.refreshingPersonalSubscriptionIds,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                    ) { Text("به‌روزرسانی", fontSize = 11.sp) }
                    if (uiState.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID &&
                        uiState.servers.isNotEmpty()) {
                        TextButton(
                            onClick = { pendingDeleteAllPersonal = true },
                            contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                        ) { Text("حذف همه", fontSize = 11.sp) }
                    }
                } else {
                    val currentSourcePingRunning = uiState.servers.any { it.guid in uiState.testingGuids }
                    TextButton(
                        onClick = {
                            if (currentSourcePingRunning) viewModel.cancelCurrentSourcePing()
                            else viewModel.testCurrentSourcePing()
                        },
                        enabled = uiState.servers.isNotEmpty(),
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                    ) { Text(if (currentSourcePingRunning) "لغو پینگ" else "تست پینگ", fontSize = 11.sp) }
                    TextButton(
                        onClick = { viewModel.onRefresh() },
                        enabled = !uiState.isRefreshing,
                        contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                    ) { Text("تازه‌سازی", fontSize = 11.sp) }
                }

                TextButton(
                    onClick = { showPersonalImport = true },
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp),
                ) { Text("افزودن", fontSize = 11.sp) }
            }

            val isV2RaySelected = uiState.selectedSourceId == SimpleVpnRepository.SIMPLE_SUB_ID ||
                uiState.selectedSourceId == SimpleVpnRepository.HAMRAH_SUB_ID
            val personalSubscriptionTabs = SimpleVpnRepository.getPersonalSubscriptionTabs()
            val hasManualPersonal = SimpleVpnRepository.getPersonalServers().isNotEmpty()
            val isManualPersonal = uiState.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID
            val selectedPersonalIndex = personalSubscriptionTabs.indexOfFirst { it.id == uiState.selectedSourceId }
            val isPersonalSelected = isManualPersonal || selectedPersonalIndex >= 0
            val manualTabIndex = if (hasManualPersonal) 4 else -1
            val personalTabsStartIndex = if (hasManualPersonal) 5 else 4
            // The manual tab is shown only while it actually contains profiles.
            val selectedProfileIndex = when (uiState.selectedSourceId) {
                SimpleVpnRepository.AETHER_SUB_ID -> 0
                SimpleVpnRepository.COMBINED_SUB_ID -> 1
                SimpleVpnRepository.SERVICES_SUB_ID -> 2
                SimpleVpnRepository.SIMPLE_SUB_ID,
                SimpleVpnRepository.HAMRAH_SUB_ID -> 3
                SimpleVpnRepository.PERSONAL_SUB_ID -> if (hasManualPersonal) manualTabIndex else 0
                else -> if (selectedPersonalIndex >= 0) personalTabsStartIndex + selectedPersonalIndex else 0
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ScrollableTabRow(
                    selectedTabIndex = selectedProfileIndex,
                    edgePadding = 8.dp,
                    containerColor = Color(0xFF17151D),
                    contentColor = Color(0xFFE9B0C8),
                ) {
                    Tab(
                        selected = selectedProfileIndex == 0,
                        onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.WIREGUARD) },
                        text = {
                            Text(
                                stringResource(R.string.simple_tab_aether),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                            )
                        },
                    )
                    Tab(
                        selected = selectedProfileIndex == 1,
                        onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.COMBINED) },
                        text = {
                            Text(
                                stringResource(R.string.simple_tab_combined),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                            )
                        },
                    )
                    Tab(
                        selected = selectedProfileIndex == 2,
                        onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.SERVICES_FOR_IRAN) },
                        text = {
                            Text(
                                stringResource(R.string.simple_tab_services_iran),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                            )
                        },
                    )
                    Tab(
                        selected = selectedProfileIndex == 3,
                        onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.V2RAY) },
                        text = {
                            Text(
                                stringResource(R.string.simple_tab_v2ray),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 12.sp,
                            )
                        },
                    )
                    if (hasManualPersonal) {
                        Tab(
                            selected = selectedProfileIndex == manualTabIndex,
                            onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.PERSONAL) },
                            text = { Text("کانفیگ‌های دستی", textAlign = TextAlign.Center, fontSize = 12.sp) },
                        )
                    }
                    personalSubscriptionTabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = selectedProfileIndex == personalTabsStartIndex + index,
                            onClick = { viewModel.onSelectSource(tab.id) },
                            text = {
                                Text(
                                    tab.name,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                )
                            },
                        )
                    }
                }
            }
            if (isV2RaySelected) {
                TabRow(
                    selectedTabIndex = if (uiState.selectedSourceId == SimpleVpnRepository.HAMRAH_SUB_ID) 0 else 1,
                    containerColor = Color(0xFF121117),
                    contentColor = Color(0xFFE9B0C8),
                ) {
                    Tab(
                        selected = uiState.selectedSourceId == SimpleVpnRepository.HAMRAH_SUB_ID,
                        onClick = { viewModel.onSelectV2RaySubtab(SimpleVpnRepository.HAMRAH_SUB_ID) },
                        text = { Text(stringResource(R.string.simple_subtab_hamrah)) },
                    )
                    Tab(
                        selected = uiState.selectedSourceId == SimpleVpnRepository.SIMPLE_SUB_ID,
                        onClick = { viewModel.onSelectV2RaySubtab(SimpleVpnRepository.SIMPLE_SUB_ID) },
                        text = { Text(stringResource(R.string.simple_subtab_irancell)) },
                    )
                }
            }
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
                        onDelete = if (isPersonalSelected) viewModel::deletePersonalConfig else null,
                        groupBySubscription = isPersonalSelected,
                        refreshingSubscriptionIds = uiState.refreshingPersonalSubscriptionIds,
                        onRefreshSubscription = viewModel::refreshPersonalSubscription,
                        onTestPingSubscription = viewModel::testPersonalSubscriptionPing,
                        onCancelPingSubscription = viewModel::cancelPersonalSubscriptionPing,
                        onDeleteSubscription = { pendingDeleteSubscriptionId = it },
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
    onDelete: ((String) -> Unit)? = null,
    groupBySubscription: Boolean = false,
    refreshingSubscriptionIds: Set<String> = emptySet(),
    onRefreshSubscription: ((String) -> Unit)? = null,
    onTestPingSubscription: ((String) -> Unit)? = null,
    onCancelPingSubscription: ((String) -> Unit)? = null,
    onDeleteSubscription: ((String) -> Unit)? = null,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        if (groupBySubscription) {
            servers.groupBy { it.subscriptionId }.forEach { (subscriptionId, groupServers) ->
                item(key = "subscription-header:$subscriptionId") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF2A2631))
                            .padding(start = 16.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = groupServers.first().subscriptionName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (subscriptionId == SimpleVpnRepository.PERSONAL_SUB_ID || subscriptionId.startsWith("personal_sub_")) {
                            val pingIsRunning = groupServers.any { it.guid in testingGuids }
                            TextButton(
                                onClick = {
                                    if (pingIsRunning) onCancelPingSubscription?.invoke(subscriptionId)
                                    else onTestPingSubscription?.invoke(subscriptionId)
                                },
                            ) { Text(if (pingIsRunning) "لغو پینگ" else "تست پینگ") }
                            if (subscriptionId.startsWith("personal_sub_")) TextButton(
                                onClick = { onRefreshSubscription?.invoke(subscriptionId) },
                                enabled = subscriptionId !in refreshingSubscriptionIds,
                            ) {
                                if (subscriptionId in refreshingSubscriptionIds) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.width(16.dp).height(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Text(stringResource(R.string.simple_refresh))
                                }
                            }
                            if (subscriptionId.startsWith("personal_sub_")) {
                                TextButton(onClick = { onDeleteSubscription?.invoke(subscriptionId) }) {
                                    Text(stringResource(R.string.action_delete))
                                }
                            }
                        }
                    }
                }
                items(groupServers, key = { it.guid }) { server ->
                    ServerRow(
                        server = server,
                        selected = server.guid == selectedGuid,
                        testing = server.guid in testingGuids,
                        enabled = enabled,
                        onSelect = { onSelect(server.guid) },
                        onDelete = onDelete?.let { delete -> { delete(server.guid) } },
                    )
                    HorizontalDivider()
                }
            }
        } else {
            items(servers, key = { it.guid }) { server ->
                ServerRow(
                    server = server,
                    selected = server.guid == selectedGuid,
                    testing = server.guid in testingGuids,
                    enabled = enabled,
                    onSelect = { onSelect(server.guid) },
                    onDelete = onDelete?.let { delete -> { delete(server.guid) } },
                )
                HorizontalDivider()
            }
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
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Color(0xFF493344) else Color(0xFF292832))
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(\n            selected = selected,\n            enabled = enabled,\n            onClick = onSelect,\n            colors = androidx.compose.material3.RadioButtonDefaults.colors(\n                selectedColor = Color(0xFFFF8FBD),\n                unselectedColor = Color(0xFFB8B1BF),\n                disabledSelectedColor = Color(0xFFB77C9B),\n                disabledUnselectedColor = Color(0xFF77717D),\n            ),\n        )
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
        if (onDelete != null) TextButton(onClick = onDelete) { Text("حذف") }
    }
}

@Composable
private fun DelayLabel(delayMillis: Long?, testing: Boolean) {
    val text = when {
        testing -> stringResource(R.string.simple_testing)
        // AetherDelayTester uses 0 as its explicit "not tested" sentinel.
        delayMillis == null || delayMillis == 0L -> stringResource(R.string.simple_not_tested)
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
    val statusText = when (status) {
        SimpleConnectionStatus.DISCONNECTED -> stringResource(R.string.simple_status_disconnected)
        SimpleConnectionStatus.CONNECTING -> stringResource(R.string.simple_status_connecting)
        SimpleConnectionStatus.CONNECTED -> stringResource(R.string.simple_status_connected)
    }
    val statusColor = when (status) {
        SimpleConnectionStatus.DISCONNECTED -> Color(0xFF8D8792)
        SimpleConnectionStatus.CONNECTING -> Color(0xFFE7B36B)
        SimpleConnectionStatus.CONNECTED -> Color(0xFF65C99A)
    }
    val actionContainerColor = when (status) {
        SimpleConnectionStatus.DISCONNECTED -> Color(0xFFE9A9C4)
        SimpleConnectionStatus.CONNECTING -> Color(0xFFE7B36B)
        SimpleConnectionStatus.CONNECTED -> Color(0xFF65C99A)
    }
    val actionContentColor = Color(0xFF241820)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121117))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.FloatingActionButton(
            onClick = {
                // CONNECTING remains cancellable: a failed Aether start must not trap the UI.
                if (status == SimpleConnectionStatus.CONNECTING ||
                    status == SimpleConnectionStatus.CONNECTED || canConnect
                ) onConnectClick()
            },
            modifier = Modifier.size(78.dp),
            shape = CircleShape,
            containerColor = actionContainerColor,
            contentColor = actionContentColor,
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(
                    if (status != SimpleConnectionStatus.DISCONNECTED) R.drawable.ic_stop_24dp
                    else R.drawable.ic_play_24dp
                ),
                contentDescription = stringResource(
                    if (status != SimpleConnectionStatus.DISCONNECTED) R.string.acc_stop else R.string.acc_start
                ),
                tint = actionContentColor,
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(30.dp))
                .background(Color(0xFF2A2631))
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .shadow(3.dp, CircleShape)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "وضعیت اتصال: $statusText",
                color = Color(0xFFEDE4EB),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

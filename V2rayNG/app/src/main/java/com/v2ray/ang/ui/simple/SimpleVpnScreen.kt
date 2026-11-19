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
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.delay

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
                    .padding(horizontal = 10.dp, vertical = 7.dp)
                    .shadow(12.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xCC302638), Color(0xCC191923), Color(0xCC292231))
                        )
                    )
                    .border(
                        1.dp,
                        Brush.linearGradient(listOf(Color(0x66FFD5E8), Color(0x1AFFFFFF), Color(0x55B58AA8))),
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                TextButton(
                    onClick = { viewModel.showLicenseDialog() },
                    modifier = Modifier
                        .clip(RoundedCornerShape(13.dp))
                        .background(Color(0x44E9B0C8))
                        .border(1.dp, Color(0x55E9B0C8), RoundedCornerShape(13.dp)),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                ) { Text("اشتراک", fontSize = 11.sp, color = Color(0xFFFFD9E9), fontWeight = FontWeight.SemiBold) }

                if (uiState.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID ||
                    uiState.selectedSourceId.startsWith("personal_sub_")) {
                    TextButton(
                        onClick = { viewModel.onRefresh() },
                        enabled = !uiState.isRefreshing &&
                            uiState.selectedSourceId !in uiState.refreshingPersonalSubscriptionIds,
                        modifier = Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0x3324BFA0))
                            .border(1.dp, Color(0x5524BFA0), RoundedCornerShape(13.dp)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("به‌روزرسانی", fontSize = 11.sp, color = Color(0xFFB8F1DF)) }
                    if (uiState.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID &&
                        uiState.servers.isNotEmpty()) {
                        TextButton(
                            onClick = { pendingDeleteAllPersonal = true },
                            modifier = Modifier
                                .clip(RoundedCornerShape(13.dp))
                                .background(Color(0x33E16D83))
                                .border(1.dp, Color(0x55E16D83), RoundedCornerShape(13.dp)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        ) { Text("حذف همه", fontSize = 11.sp, color = Color(0xFFFFB9C5)) }
                    }
                } else {
                    val currentSourcePingRunning = uiState.servers.any { it.guid in uiState.testingGuids }
                    TextButton(
                        onClick = {
                            if (currentSourcePingRunning) viewModel.cancelCurrentSourcePing()
                            else viewModel.testCurrentSourcePing()
                        },
                        enabled = uiState.servers.isNotEmpty(),
                        modifier = Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0x334E9BD0))
                            .border(1.dp, Color(0x555EACE0), RoundedCornerShape(13.dp)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text(if (currentSourcePingRunning) "لغو پینگ" else "تست پینگ", fontSize = 11.sp, color = Color(0xFFC8E7FF)) }
                    TextButton(
                        onClick = { viewModel.onRefresh() },
                        enabled = !uiState.isRefreshing,
                        modifier = Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(Color(0x334E9BD0))
                            .border(1.dp, Color(0x555EACE0), RoundedCornerShape(13.dp)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) { Text("تازه‌سازی", fontSize = 11.sp, color = Color(0xFFC8E7FF)) }
                }

                TextButton(
                    onClick = { showPersonalImport = true },
                    modifier = Modifier
                        .clip(RoundedCornerShape(13.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFFE7A6C5), Color(0xFFB77FA8))))
                        .border(1.dp, Color(0x99FFE5F0), RoundedCornerShape(13.dp)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("＋ افزودن", fontSize = 11.sp, color = Color(0xFF251722), fontWeight = FontWeight.Bold) }
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
                selectedServerName = uiState.servers.firstOrNull { it.guid == uiState.selectedGuid }?.displayName,
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
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .shadow(7.dp, RoundedCornerShape(15.dp))
                            .clip(RoundedCornerShape(15.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xCC49394D), Color(0xCC292431)))
                            )
                            .border(1.dp, Color(0x44F4D8E8), RoundedCornerShape(15.dp))
                            .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
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
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .shadow(if (selected) 9.dp else 4.dp, RoundedCornerShape(17.dp))
            .clip(RoundedCornerShape(17.dp))
            .background(
                if (selected) {
                    Brush.linearGradient(listOf(Color(0xFF65465D), Color(0xFF392B40), Color(0xFF493344)))
                } else {
                    Brush.linearGradient(listOf(Color(0xCC35313D), Color(0xCC22212B)))
                }
            )
            .border(
                1.dp,
                if (selected) {
                    Brush.linearGradient(listOf(Color(0x99FFD2E7), Color(0x338E7390)))
                } else {
                    Brush.linearGradient(listOf(Color(0x44FFFFFF), Color(0x117F7485)))
                },
                RoundedCornerShape(17.dp)
            )
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = onSelect,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                selectedColor = Color(0xFFFF8FBD),
                unselectedColor = Color(0xFFB8B1BF),
                disabledSelectedColor = Color(0xFFB77C9B),
                disabledUnselectedColor = Color(0xFF77717D),
            ),
        )
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
    val labelColor = when {
        testing -> Color(0xFFE7B36B)
        delayMillis == null || delayMillis == 0L -> Color(0xFFAAA3AE)
        delayMillis < 0L || delayMillis > 160L -> Color(0xFFFF8293)
        delayMillis < 90L -> Color(0xFF78D6A8)
        else -> Color(0xFFE8C66F)
    }
    Text(text, color = labelColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun ConnectionBar(
    status: SimpleConnectionStatus,
    selectedServerName: String?,
    canConnect: Boolean,
    onConnectClick: () -> Unit,
) {
    var elapsedSeconds by remember { mutableStateOf(0L) }

    LaunchedEffect(status) {
        if (status == SimpleConnectionStatus.CONNECTED) {
            val connectedAt = System.currentTimeMillis()
            while (true) {
                elapsedSeconds = ((System.currentTimeMillis() - connectedAt) / 1000L).coerceAtLeast(0L)
                delay(1_000)
            }
        } else {
            elapsedSeconds = 0L
        }
    }

    val statusText = when (status) {
        SimpleConnectionStatus.DISCONNECTED -> stringResource(R.string.simple_status_disconnected)
        SimpleConnectionStatus.CONNECTING -> stringResource(R.string.simple_status_connecting)
        SimpleConnectionStatus.CONNECTED -> ""
    }
    val statusColor = when (status) {
        SimpleConnectionStatus.DISCONNECTED -> Color(0xFFAAA3AE)
        SimpleConnectionStatus.CONNECTING -> Color(0xFFE7B36B)
        SimpleConnectionStatus.CONNECTED -> Color(0xFF78D6A8)
    }
    val actionContainerColor = when (status) {
        SimpleConnectionStatus.DISCONNECTED -> Color(0xFFE9A9C4)
        SimpleConnectionStatus.CONNECTING -> Color(0xFFE7B36B)
        SimpleConnectionStatus.CONNECTED -> Color(0xFF302B35)
    }
    val actionContentColor = when (status) {
        SimpleConnectionStatus.DISCONNECTED -> Color(0xFF241820)
        SimpleConnectionStatus.CONNECTING -> Color(0xFF241820)
        SimpleConnectionStatus.CONNECTED -> Color(0xFFF5EAF0)
    }
    val actionText = when (status) {
        SimpleConnectionStatus.DISCONNECTED -> "اتصال"
        SimpleConnectionStatus.CONNECTING -> "لغو اتصال"
        SimpleConnectionStatus.CONNECTED -> "قطع اتصال"
    }
    val timerText = "%02d:%02d:%02d".format(
        elapsedSeconds / 3600L,
        (elapsedSeconds % 3600L) / 60L,
        elapsedSeconds % 60L,
    )
    val actionEnabled = status != SimpleConnectionStatus.DISCONNECTED || canConnect

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF211B27), Color(0xFF111018))))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = selectedServerName?.takeIf { it.isNotBlank() } ?: "یک سرور انتخاب کن",
            color = Color(0xFFF5EAF0),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (status != SimpleConnectionStatus.CONNECTED) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = statusText,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.material3.Button(
            onClick = {
                // Keep CONNECTING cancellable and preserve the existing connection callback.
                if (status == SimpleConnectionStatus.CONNECTING ||
                    status == SimpleConnectionStatus.CONNECTED || canConnect
                ) onConnectClick()
            },
            enabled = actionEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = RoundedCornerShape(18.dp),
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = actionContainerColor,
                contentColor = actionContentColor,
                disabledContainerColor = Color(0xFF51434D),
                disabledContentColor = Color(0xFFB8AAB2),
            ),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        ) {
            androidx.compose.material3.Icon(
                painter = androidx.compose.ui.res.painterResource(
                    if (status == SimpleConnectionStatus.CONNECTING) R.drawable.ic_stop_24dp
                    else android.R.drawable.ic_lock_power_off
                ),
                contentDescription = if (status == SimpleConnectionStatus.CONNECTED) "قطع اتصال" else "اتصال",
                modifier = Modifier.size(21.dp),
            )
            Spacer(modifier = Modifier.width(9.dp))
            Text(
                text = if (status == SimpleConnectionStatus.CONNECTED) "$actionText  •  $timerText" else actionText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

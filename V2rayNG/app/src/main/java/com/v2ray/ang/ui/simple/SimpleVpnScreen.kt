package com.v2ray.ang.ui.simple

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import com.v2ray.ang.util.LogUtil
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R
import com.v2ray.ang.handler.SimpleVpnRepository
import com.v2ray.ang.handler.SimpleVpnRepository.SimpleServerUiModel
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---------------------------------------------------------------------------------------------
// Visual identity (UI only): near-black navy canvas, solid dark cards with thin borders,
// turquoise accent, chrome power button. None of these values is used by any connection logic.
// ---------------------------------------------------------------------------------------------
private val ScreenBg = Color(0xFF070B12)
private val CardColor = Color(0xFF0F1620)
private val CardSelectedColor = Color(0xFF0C1D22)
private val LineColor = Color(0xFF1F2B3B)
private val Turquoise = Color(0xFF2EE6D0)
private val Gold = Color(0xFFF4C24E)
private val Silver = Color(0xFFDBE6F3)
private val TextPrimary = Color(0xFFF3F7FC)
private val TextSecondary = Color(0xFFA9B8CF)
private val TextDim = Color(0xFF8A99AE)

// Tabler "cloud" outline, used only for the decorative brand tile in the header.
private const val CLOUD_PATH =
    "M6.657 18c-2.572 0 -4.657 -2.007 -4.657 -4.483c0 -2.475 2.085 -4.482 4.657 -4.482" +
        "c.393 -1.762 1.794 -3.2 3.675 -3.773c1.88 -.572 3.956 -.193 5.444 1" +
        "c1.488 1.19 2.162 3.007 1.77 4.769h.99c1.913 0 3.464 1.56 3.464 3.486" +
        "c0 1.927 -1.551 3.487 -3.465 3.487h-11.878"

/** Solid dark card with a thin border. [selected] switches to the teal-tinted variant. */
private fun Modifier.cardSurface(shape: Shape, selected: Boolean = false): Modifier = this
    .clip(shape)
    .background(if (selected) CardSelectedColor else CardColor)
    .border(0.8.dp, if (selected) Turquoise else LineColor, shape)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SimpleVpnScreen(
    viewModel: SimpleVpnViewModel,
    onConnectClick: () -> Unit,
    onScanClick: () -> Unit,
    onExportDiagnosticClick: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPersonalImport by remember { mutableStateOf(false) }
    var personalText by remember { mutableStateOf("") }
    var pendingDeleteSubscriptionId by remember { mutableStateOf<String?>(null) }
    var pendingDeleteAllPersonal by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

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
                    ImportDiagnosticLog.record(context, "UI personalAdd clicked chars=${personalText.length} lines=${personalText.lineSequence().count()}")
                    LogUtil.w(message = "IMPORT_TRACE UI add clicked chars=${personalText.length} lines=${personalText.lineSequence().count()}")
                    viewModel.importPersonalConfig(personalText)
                    ImportDiagnosticLog.record(context, "UI personalAdd importCallReturned")
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
        containerColor = ScreenBg,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BrandTile()
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "PARSCLOUD",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.4.sp,
                                color = TextPrimary,
                                maxLines = 1,
                            )
                            Text(
                                "اتصال امن و پایدار",
                                fontSize = 12.sp,
                                color = TextDim,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    TextButton(onClick = onExportDiagnosticClick) {
                        Text("گزارش مشکل", fontSize = 11.sp, color = TextSecondary)
                    }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = TextPrimary,
                ),
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = Color(0xFF142642),
                    contentColor = Color(0xFFEAF7FF),
                    shape = RoundedCornerShape(20.dp),
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBg)
                .padding(padding)
        ) {
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
            // Name of the active profile, taken from the same tab labels the strip below uses.
            val tabAetherLabel = stringResource(R.string.simple_tab_aether)
            val tabCombinedLabel = stringResource(R.string.simple_tab_combined)
            val tabServicesLabel = stringResource(R.string.simple_tab_services_iran)
            val tabV2RayLabel = stringResource(R.string.simple_tab_v2ray)
            val selectedProfileLabel = when {
                selectedProfileIndex == 0 -> tabAetherLabel
                selectedProfileIndex == 1 -> tabCombinedLabel
                selectedProfileIndex == 2 -> tabServicesLabel
                selectedProfileIndex == 3 -> tabV2RayLabel
                hasManualPersonal && selectedProfileIndex == manualTabIndex -> "کانفیگ‌های دستی"
                else -> personalSubscriptionTabs.getOrNull(selectedProfileIndex - personalTabsStartIndex)?.name
            }

            // Hero: power button, selected server, status + timer, credit and volume cards.
            ConnectionBar(
                status = uiState.status,
                selectedServerName = uiState.servers.firstOrNull { it.guid == uiState.selectedGuid }?.displayName,
                profileLabel = selectedProfileLabel,
                canConnect = uiState.canConnect && uiState.selectedGuid != null,
                licenseExpiresAtMillis = uiState.licenseExpiresAtMillis,
                onConnectClick = onConnectClick,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // The profile strip stays left-to-right exactly like the original screen.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ScrollableTabRow(
                    selectedTabIndex = selectedProfileIndex,
                    edgePadding = 10.dp,
                    containerColor = Color.Transparent,
                    contentColor = Turquoise,
                    indicator = {},
                    divider = {},
                ) {
                    ProfileTab(
                        selected = selectedProfileIndex == 0,
                        onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.WIREGUARD) },
                        label = tabAetherLabel,
                    )
                    ProfileTab(
                        selected = selectedProfileIndex == 1,
                        onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.COMBINED) },
                        label = tabCombinedLabel,
                    )
                    ProfileTab(
                        selected = selectedProfileIndex == 2,
                        onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.SERVICES_FOR_IRAN) },
                        label = tabServicesLabel,
                    )
                    ProfileTab(
                        selected = selectedProfileIndex == 3,
                        onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.V2RAY) },
                        label = tabV2RayLabel,
                    )
                    if (hasManualPersonal) {
                        ProfileTab(
                            selected = selectedProfileIndex == manualTabIndex,
                            onClick = { viewModel.onSelectProfile(SimpleVpnProfileTab.PERSONAL) },
                            label = "کانفیگ‌های دستی",
                            limitLines = false,
                        )
                    }
                    personalSubscriptionTabs.forEachIndexed { index, tab ->
                        ProfileTab(
                            selected = selectedProfileIndex == personalTabsStartIndex + index,
                            onClick = { viewModel.onSelectSource(tab.id) },
                            label = tab.name,
                        )
                    }
                }
            }
            if (isV2RaySelected) {
                TabRow(
                    selectedTabIndex = if (uiState.selectedSourceId == SimpleVpnRepository.HAMRAH_SUB_ID) 0 else 1,
                    modifier = Modifier.padding(horizontal = 10.dp),
                    containerColor = Color.Transparent,
                    contentColor = Turquoise,
                    indicator = {},
                    divider = {},
                ) {
                    ProfileTab(
                        selected = uiState.selectedSourceId == SimpleVpnRepository.HAMRAH_SUB_ID,
                        onClick = { viewModel.onSelectV2RaySubtab(SimpleVpnRepository.HAMRAH_SUB_ID) },
                        label = stringResource(R.string.simple_subtab_hamrah),
                    )
                    ProfileTab(
                        selected = uiState.selectedSourceId == SimpleVpnRepository.SIMPLE_SUB_ID,
                        onClick = { viewModel.onSelectV2RaySubtab(SimpleVpnRepository.SIMPLE_SUB_ID) },
                        label = stringResource(R.string.simple_subtab_irancell),
                    )
                }
            }
            Box(modifier = Modifier.weight(1f)) {
                if (uiState.isRefreshing && uiState.servers.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Turquoise)
                    }
                } else if (uiState.servers.isEmpty()) {
                    if (uiState.selectedSourceId.startsWith("personal_sub_")) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    personalSubscriptionTabs.firstOrNull { it.id == uiState.selectedSourceId }?.name
                                        ?: "اشتراک شخصی",
                                    modifier = Modifier.weight(1f),
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextButton(
                                    onClick = { viewModel.onRefresh() },
                                    enabled = !uiState.isRefreshing &&
                                        uiState.selectedSourceId !in uiState.refreshingPersonalSubscriptionIds,
                                ) { Text(stringResource(R.string.simple_refresh)) }
                                TextButton(
                                    onClick = { pendingDeleteSubscriptionId = uiState.selectedSourceId },
                                ) {
                                    Text(stringResource(R.string.action_delete), color = Color(0xFFFF8D9A))
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            if (uiState.isRefreshing || uiState.selectedSourceId in uiState.refreshingPersonalSubscriptionIds) {
                                CircularProgressIndicator(color = Turquoise)
                            } else {
                                Text(stringResource(R.string.simple_no_servers_available), color = TextSecondary)
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.simple_no_servers_available), color = TextSecondary)
                        }
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

            // Bottom action bar: the same actions, same conditions and same callbacks as before.
            ActionBarContainer {
                BottomAction(
                    label = "اشتراک",
                    glyph = ActionGlyph.CROWN,
                    onClick = { viewModel.showLicenseDialog() },
                )

                if (uiState.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID ||
                    uiState.selectedSourceId.startsWith("personal_sub_")) {
                    BottomAction(
                        label = "به‌روزرسانی",
                        glyph = ActionGlyph.REFRESH,
                        onClick = { viewModel.onRefresh() },
                        enabled = !uiState.isRefreshing &&
                            uiState.selectedSourceId !in uiState.refreshingPersonalSubscriptionIds,
                    )
                    if (uiState.selectedSourceId == SimpleVpnRepository.PERSONAL_SUB_ID &&
                        uiState.servers.isNotEmpty()) {
                        BottomAction(
                            label = "حذف همه",
                            glyph = ActionGlyph.TRASH,
                            onClick = { pendingDeleteAllPersonal = true },
                            tint = Color(0xFFFFB9C5),
                        )
                    }
                } else {
                    val currentSourcePingRunning = uiState.servers.any { it.guid in uiState.testingGuids }
                    BottomAction(
                        label = if (currentSourcePingRunning) "لغو پینگ" else "تست پینگ",
                        glyph = if (currentSourcePingRunning) ActionGlyph.STOP else ActionGlyph.ACTIVITY,
                        onClick = {
                            if (currentSourcePingRunning) viewModel.cancelCurrentSourcePing()
                            else viewModel.testCurrentSourcePing()
                        },
                        enabled = uiState.servers.isNotEmpty(),
                    )
                    BottomAction(
                        label = "تازه‌سازی",
                        glyph = ActionGlyph.REFRESH,
                        onClick = { viewModel.onRefresh() },
                        enabled = !uiState.isRefreshing,
                    )
                }

                BottomAction(
                    label = "افزودن",
                    glyph = ActionGlyph.PLUS,
                    onClick = { showPersonalImport = true },
                )
            }
        }
    }
}

/** Decorative brand tile next to the title (cloud outline). Not clickable. */
@Composable
private fun BrandTile() {
    val cloud = remember { runCatching { PathParser().parsePathString(CLOUD_PATH).toPath() }.getOrNull() }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF0E2B2E))
            .border(1.dp, Color(0xFF1B5A58), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            if (cloud != null) {
                val u = size.minDimension / 24f
                withTransform({ scale(u, u, Offset.Zero) }) {
                    drawPath(
                        path = cloud,
                        color = Turquoise,
                        style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

/** Visual-only wrapper around the original [Tab]: same selected/onClick, pill styling. */
@Composable
private fun ProfileTab(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    limitLines: Boolean = true,
) {
    Tab(
        selected = selected,
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xFF0E2B2E) else CardColor)
            .border(
                0.8.dp,
                if (selected) Turquoise else LineColor,
                RoundedCornerShape(50),
            ),
        selectedContentColor = Turquoise,
        unselectedContentColor = TextSecondary,
        text = {
            Text(
                label,
                textAlign = TextAlign.Center,
                maxLines = if (limitLines) 2 else Int.MAX_VALUE,
                overflow = if (limitLines) TextOverflow.Ellipsis else TextOverflow.Clip,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
        },
    )
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
                            .padding(horizontal = 14.dp, vertical = 4.dp)
                            .cardSurface(RoundedCornerShape(14.dp))
                            .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = groupServers.first().subscriptionName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                        )
                        if (subscriptionId == SimpleVpnRepository.PERSONAL_SUB_ID || subscriptionId.startsWith("personal_sub_")) {
                            val pingIsRunning = groupServers.any { it.guid in testingGuids }
                            TextButton(
                                onClick = {
                                    if (pingIsRunning) onCancelPingSubscription?.invoke(subscriptionId)
                                    else onTestPingSubscription?.invoke(subscriptionId)
                                },
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = Color(0xFF7FDCF5),
                                ),
                            ) { Text(if (pingIsRunning) "لغو پینگ" else "تست پینگ") }
                            if (subscriptionId.startsWith("personal_sub_")) TextButton(
                                onClick = { onRefreshSubscription?.invoke(subscriptionId) },
                                enabled = subscriptionId !in refreshingSubscriptionIds,
                                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                    contentColor = Color(0xFF7FDCF5),
                                ),
                            ) {
                                if (subscriptionId in refreshingSubscriptionIds) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.width(16.dp).height(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Turquoise,
                                    )
                                } else {
                                    Text(stringResource(R.string.simple_refresh))
                                }
                            }
                            if (subscriptionId.startsWith("personal_sub_")) {
                                TextButton(
                                    onClick = { onDeleteSubscription?.invoke(subscriptionId) },
                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        contentColor = Color(0xFFFF8D9A),
                                    ),
                                ) {
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
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .cardSurface(RoundedCornerShape(16.dp), selected)
            .selectable(selected = selected, enabled = enabled, onClick = onSelect)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = onSelect,
            colors = androidx.compose.material3.RadioButtonDefaults.colors(
                selectedColor = Turquoise,
                unselectedColor = Color(0xFF5A6B88),
                disabledSelectedColor = Color(0x802EE6D0),
                disabledUnselectedColor = Color(0xFF3F4C61),
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (server.flagEmoji.isNotEmpty()) {
            Text(server.flagEmoji)
            Spacer(modifier = Modifier.width(8.dp))
        }
        // Server name and info always come from the real server model.
        Text(
            text = server.displayName,
            modifier = Modifier.weight(1f),
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        DelayLabel(delayMillis = server.delayMillis, testing = testing)
        if (onDelete != null) {
            TextButton(
                onClick = onDelete,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                    contentColor = Color(0xFFFF8D9A),
                ),
            ) { Text("حذف") }
        }
    }
}

@Composable
private fun DelayLabel(delayMillis: Long?, testing: Boolean) {
    // Match the V2Ray list: keep the delay area empty until a test has run.
    if (!testing && (delayMillis == null || delayMillis == 0L)) return
    Spacer(modifier = Modifier.width(8.dp))
    if (testing) {
        // Progress of a running test is shown as plain text, never as a result chip.
        Text(
            stringResource(R.string.simple_testing),
            color = Color(0xFFE7B36B),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        return
    }
    val value = delayMillis ?: return
    val text = if (value < 0L) {
        stringResource(R.string.simple_timeout)
    } else {
        stringResource(R.string.simple_delay_ms, value)
    }
    // Colour comes only from the real test result:
    // up to 100ms turquoise, 101-250ms gold, above 250ms red, failed test red.
    // Red means high latency or a failed test, not that the server is offline.
    val chipBackground: Color
    val chipText: Color
    when {
        value < 0L || value > 250L -> {
            chipBackground = Color(0xFF2E1519)
            chipText = Color(0xFFFF8D9A)
        }
        value <= 100L -> {
            chipBackground = Color(0xFF0D2B2A)
            chipText = Color(0xFF2EE6D0)
        }
        else -> {
            chipBackground = Color(0xFF2E2711)
            chipText = Color(0xFFF2C14E)
        }
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(chipBackground)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, color = chipText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConnectionBar(
    status: SimpleConnectionStatus,
    selectedServerName: String?,
    profileLabel: String?,
    canConnect: Boolean,
    licenseExpiresAtMillis: Long?,
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

    // Lighting colour of the button, status dot and timer: silver / gold / turquoise.
    val stateColor = when (status) {
        SimpleConnectionStatus.DISCONNECTED -> Silver
        SimpleConnectionStatus.CONNECTING -> Gold
        SimpleConnectionStatus.CONNECTED -> Turquoise
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
    val compactScreen = LocalConfiguration.current.screenHeightDp < 700

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ChromePowerButton(
            status = status,
            stateColor = stateColor,
            enabled = actionEnabled,
            label = actionText,
            onClick = onConnectClick,
            size = if (compactScreen) 140.dp else 168.dp,
        )

        Spacer(modifier = Modifier.height(10.dp))
        val serverName = selectedServerName?.takeIf { it.isNotBlank() }
        if (serverName != null) {
            // Server names are Latin in most profiles: keep "name  profile" reading left-to-right.
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = serverName,
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (!profileLabel.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = profileLabel,
                            color = TextDim,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        } else {
            Text(
                text = "یک سرور انتخاب کن",
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        // Status on one side of a thin divider, timer on the other. Both sides are equal-width,
        // so the timer appearing never moves the status text or the power button.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (status) {
                            SimpleConnectionStatus.DISCONNECTED -> "آماده اتصال"
                            SimpleConnectionStatus.CONNECTING -> "در حال برقراری اتصال..."
                            SimpleConnectionStatus.CONNECTED -> "متصل"
                        },
                        color = Color(0xFFC9D6E8),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .width(1.dp)
                    .height(14.dp)
                    .background(if (status == SimpleConnectionStatus.CONNECTED) Color(0x2EFFFFFF) else Color.Transparent)
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (status == SimpleConnectionStatus.CONNECTED) {
                    Text(
                        text = timerText,
                        color = stateColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        val remainingMillis = (licenseExpiresAtMillis ?: 0L) - System.currentTimeMillis()
        val remainingDays = if (licenseExpiresAtMillis != null && remainingMillis > 0L) {
            (remainingMillis + 86_399_999L) / 86_400_000L
        } else 0L
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .cardSurface(RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("اعتبار PARSCLOUD", color = TextDim, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardGlyph(type = CardGlyphType.CLOCK, color = Gold, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (licenseExpiresAtMillis == null) "در حال دریافت اطلاعات"
                        else if (remainingDays > 0L) "$remainingDays روز باقی‌مانده"
                        else "منقضی‌شده",
                        color = if (remainingDays > 0L) TextPrimary else Color(0xFFFF9AA8),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .cardSurface(RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text("حجم مصرفی", color = TextDim, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CardGlyph(type = CardGlyphType.INFINITY, color = Turquoise, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("نامحدود", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private enum class CardGlyphType { CLOCK, INFINITY }

/** Small line glyphs for the credit and volume cards, drawn on a Canvas (no icon library). */
@Composable
private fun CardGlyph(type: CardGlyphType, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (type) {
            CardGlyphType.CLOCK -> {
                drawCircle(color = color, radius = 9f * u, center = Offset(12f * u, 12f * u), style = stroke)
                val hands = Path().apply {
                    moveTo(12f * u, 7.5f * u)
                    lineTo(12f * u, 12f * u)
                    lineTo(15.2f * u, 14f * u)
                }
                drawPath(hands, color, style = stroke)
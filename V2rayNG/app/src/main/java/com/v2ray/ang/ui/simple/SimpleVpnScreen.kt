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
import androidx.compose.foundation.layout.heightIn
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
import com.v2ray.ang.util.LogUtil
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
                        Text(
                            "گزارش مشکل",
                            fontSize = 11.sp,
                            color = TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, LineColor, RoundedCornerShape(50))
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                        )
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .cardSurface(RoundedCornerShape(14.dp))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
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
    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .height(36.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color(0xFF0E2B2E) else CardColor)
            .border(
                0.8.dp,
                if (selected) Turquoise else LineColor,
                RoundedCornerShape(50),
            )
            .selectable(selected = selected, onClick = onClick, role = Role.Tab)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            textAlign = TextAlign.Center,
            maxLines = if (limitLines) 2 else Int.MAX_VALUE,
            overflow = if (limitLines) TextOverflow.Ellipsis else TextOverflow.Clip,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) Turquoise else TextSecondary,
        )
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = 12.dp),
    ) {
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
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            enabled = enabled,
            onClick = null,
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

        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = profileLabel?.takeIf { it.isNotBlank() } ?: "یک پروفایل انتخاب کن",
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))

        // Keep the status centered; show the timer beside it only while connected.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
                if (status == SimpleConnectionStatus.CONNECTED) {
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = timerText.toPersianDigits(),
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
                        else if (remainingDays > 0L) "${remainingDays.toString().toPersianDigits()} روز باقی‌مانده"
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

private fun String.toPersianDigits(): String {
    val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')
    return map { char -> if (char in '0'..'9') persianDigits[char - '0'] else char }.joinToString("")
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
            }
            CardGlyphType.INFINITY -> {
                val loop = Path().apply {
                    moveTo(12f * u, 12f * u)
                    cubicTo(13.5f * u, 9.5f * u, 15f * u, 8f * u, 17f * u, 8f * u)
                    cubicTo(19.2f * u, 8f * u, 21f * u, 9.8f * u, 21f * u, 12f * u)
                    cubicTo(21f * u, 14.2f * u, 19.2f * u, 16f * u, 17f * u, 16f * u)
                    cubicTo(15f * u, 16f * u, 13.5f * u, 14.5f * u, 12f * u, 12f * u)
                    cubicTo(10.5f * u, 9.5f * u, 9f * u, 8f * u, 7f * u, 8f * u)
                    cubicTo(4.8f * u, 8f * u, 3f * u, 9.8f * u, 3f * u, 12f * u)
                    cubicTo(3f * u, 14.2f * u, 4.8f * u, 16f * u, 7f * u, 16f * u)
                    cubicTo(9f * u, 16f * u, 10.5f * u, 14.5f * u, 12f * u, 12f * u)
                }
                drawPath(loop, color, style = stroke)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Chrome power button. Pure drawing: it only receives the state and forwards the click to the
// original onConnectClick through the same material3 Button (same enabled rule as before).
// The power glyph geometry is identical in every state; only colour and lighting change.
// ---------------------------------------------------------------------------------------------
@Composable
private fun ChromePowerButton(
    status: SimpleConnectionStatus,
    stateColor: Color,
    enabled: Boolean,
    label: String,
    onClick: () -> Unit,
    size: Dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed && enabled) 1f else 0f, tween(110))
    val color by animateColorAsState(stateColor, tween(300))
    val faceTint by animateFloatAsState(
        when (status) {
            SimpleConnectionStatus.DISCONNECTED -> 0f
            SimpleConnectionStatus.CONNECTING -> 0.40f
            SimpleConnectionStatus.CONNECTED -> 0.50f
        },
        tween(400),
    )
    val reflection by animateFloatAsState(
        when (status) {
            SimpleConnectionStatus.DISCONNECTED -> 0f
            SimpleConnectionStatus.CONNECTING -> 0.40f
            SimpleConnectionStatus.CONNECTED -> 0.50f
        },
        tween(400),
    )
    val glow by animateFloatAsState(
        if (!enabled) 0f else when (status) {
            SimpleConnectionStatus.DISCONNECTED -> 0f
            SimpleConnectionStatus.CONNECTING -> 0.28f
            SimpleConnectionStatus.CONNECTED -> 0.36f
        },
        tween(400),
    )
    val density = LocalDensity.current
    val unitPx = with(density) { size.toPx() } / 224f
    val floorExtra = 10.dp

    Box(
        modifier = Modifier.size(width = size, height = size + floorExtra),
        contentAlignment = Alignment.TopCenter,
    ) {
        if (status == SimpleConnectionStatus.CONNECTED) {
            PulsingHeroGlow(size = size, color = color, alpha = glow)
        } else {
            HeroGlow(size = size, color = color, alpha = glow)
        }

        Canvas(
            modifier = Modifier
                .size(width = size, height = size + floorExtra)
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f },
        ) {
            drawChromeButton(
                stateColor = color,
                reflection = reflection,
                faceTint = faceTint,
                press = press,
            )
        }

        if (status == SimpleConnectionStatus.CONNECTING) {
            ConnectingSweep(size = size, color = color)
        }

        Text(
            text = label,
            color = color.copy(alpha = if (enabled) 1f else 0.45f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier
                .offset(y = size * (146f / 224f) - 7.dp)
                .graphicsLayer { translationY = press * 2.2f * unitPx },
        )

        androidx.compose.material3.Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .size(size)
                .semantics { contentDescription = label },
            shape = CircleShape,
            colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White,
                disabledContainerColor = Color.Transparent,
                disabledContentColor = Color.White,
            ),
            contentPadding = PaddingValues(0.dp),
            interactionSource = interaction,
        ) {}
    }
}

@Composable
private fun HeroGlow(size: Dp, color: Color, alpha: Float) {
    Canvas(modifier = Modifier.size(size)) {
        val u = this.size.width / 224f
        val c = Offset(this.size.width / 2f, this.size.width / 2f)
        if (alpha > 0.01f) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = c,
                    radius = 134f * u,
                ),
                radius = 134f * u,
                center = c,
            )
        }
    }
}

@Composable
private fun PulsingHeroGlow(size: Dp, color: Color, alpha: Float) {
    val transition = rememberInfiniteTransition()
    val factor by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(tween(1300), RepeatMode.Reverse),
    )
    HeroGlow(size = size, color = color, alpha = alpha * factor)
}

/** Light sweeping around the chrome rim while connecting. Only composed in that state. */
@Composable
private fun ConnectingSweep(size: Dp, color: Color) {
    val transition = rememberInfiniteTransition()
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
    )
    Canvas(modifier = Modifier.size(size)) {
        val u = this.size.width / 224f
        val c = Offset(this.size.width / 2f, this.size.width / 2f)
        rotate(degrees = angle, pivot = c) {
            drawCircle(
                brush = Brush.sweepGradient(
                    0f to Color.Transparent,
                    0.62f to Color.Transparent,
                    1f to color,
                    center = c,
                ),
                radius = 95f * u,
                center = c,
                style = Stroke(width = 12f * u),
                blendMode = BlendMode.Screen,
            )
        }
    }
}

/**
 * Draws the layered chrome button on a square of width = size.width (extra height below is used
 * for the floor shadow). All measures are in "design units": 224 units = button width.
 * Layers: floor shadow, outer chrome ring, reversed inner chrome ring, dark groove, graphite
 * glass face with gloss, constant power glyph.
 */
private fun DrawScope.drawChromeButton(
    stateColor: Color,
    reflection: Float,
    faceTint: Float,
    press: Float,
) {
    val u = size.width / 224f
    val c = Offset(size.width / 2f, size.width / 2f)
    val cx = c.x
    val cy = c.y

    // Floor shadow under the whole button.
    val floorC = Offset(cx, cy + 114f * u)
    withTransform({ scale(1f, 0.14f, floorC) }) {
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color(0xCC000000), Color.Transparent),
                center = floorC,
                radius = 96f * u,
            ),
            radius = 96f * u,
            center = floorC,
        )
    }

    // Outer chrome ring: bright top, dark horizon band, bright bottom.
    drawCircle(
        brush = Brush.verticalGradient(
            0f to Color(0xFFFFFFFF),
            0.28f to Color(0xFFB4BCC8),
            0.50f to Color(0xFF3A414D),
            0.55f to Color(0xFF262C36),
            0.76f to Color(0xFFB9C2CE),
            1f to Color(0xFFF1F4F8),
            startY = cy - 100f * u,
            endY = cy + 100f * u,
        ),
        radius = 100f * u,
        center = c,
    )
    // Inner chrome ring with reversed reflection, so the rim reads as a rounded, raised edge.
    drawCircle(
        brush = Brush.verticalGradient(
            0f to Color(0xFFFFFFFF),
            0.40f to Color(0xFF2A313C),
            0.65f to Color(0xFF7B8696),
            1f to Color(0xFFDFE5ED),
            startY = cy - 88f * u,
            endY = cy + 88f * u,
        ),
        radius = 88f * u,
        center = c,
    )
    // Raised edge: bright top rim and dark bottom rim on the outer ring.
    drawCircle(
        brush = Brush.verticalGradient(
            0f to Color(0xF2FFFFFF),
            0.45f to Color.Transparent,
            startY = cy - 100f * u,
            endY = cy + 100f * u,
        ),
        radius = 99f * u,
        center = c,
        style = Stroke(2f * u),
    )
    drawCircle(
        brush = Brush.verticalGradient(
            0.6f to Color.Transparent,
            1f to Color(0x73000000),
            startY = cy - 100f * u,
            endY = cy + 100f * u,
        ),
        radius = 99f * u,
        center = c,
        style = Stroke(2.4f * u),
    )
    // Light of the current state reflected on the lower chrome edge.
    if (reflection > 0.01f) {
        val reflC = Offset(cx, cy + 108f * u)
        clipPath(Path().apply { addOval(Rect(c, 100f * u)) }) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(stateColor.copy(alpha = reflection), Color.Transparent),
                    center = reflC,
                    radius = 88f * u,
                ),
                radius = 88f * u,
                center = reflC,
                blendMode = BlendMode.Screen,
            )
        }
    }

    // Dark groove the face sits in, with a soft inner shadow at the top.
    drawCircle(color = Color(0xFF05080D), radius = 79f * u, center = c)
    drawCircle(
        brush = Brush.verticalGradient(
            0f to Color(0xCC000000),
            0.5f to Color.Transparent,
            startY = cy - 79f * u,
            endY = cy + 79f * u,
        ),
        radius = 77.5f * u,
        center = c,
        style = Stroke(3f * u),
    )

    // Glass face, glyph and gloss. They sink slightly while pressed.
    withTransform({
        translate(left = 0f, top = 2.2f * u * press)
        scale(1f - 0.015f * press, 1f - 0.015f * press, c)
    }) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF2F3948),
                0.45f to Color(0xFF141A24),
                1f to Color(0xFF090D13),
                center = Offset(cx - 14.6f * u, cy - 32f * u),
                radius = 131f * u,
            ),
            radius = 73f * u,
            center = c,
        )
        if (faceTint > 0.01f) {
            val tintC = Offset(cx, cy + 18f * u)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(stateColor.copy(alpha = faceTint), Color.Transparent),
                    center = tintC,
                    radius = 101f * u,
                ),
                radius = 73f * u,
                center = c,
            )
        }
        // Thin inner ring on the face.
        drawCircle(color = Color(0x1FFFFFFF), radius = 60f * u, center = c, style = Stroke(1f * u))

        // Power glyph: same shape and position in every state, only colour and glow change.
        val arcR = 22f * u
        val arcCenter = Offset(cx, cy - 8f * u)
        fun glyph(color: Color, strokeWidth: Float) {
            drawArc(
                color = color,
                startAngle = 305f,
                sweepAngle = 290f,
                useCenter = false,
                topLeft = Offset(arcCenter.x - arcR, arcCenter.y - arcR),
                size = Size(arcR * 2f, arcR * 2f),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
            drawLine(
                color = color,
                start = Offset(cx, cy - 38f * u),
                end = Offset(cx, cy - 12f * u),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
        glyph(stateColor.copy(alpha = 0.26f), 14f * u)
        glyph(stateColor, 6f * u)

        // Glossy highlight on the upper left of the face.
        val glossC = Offset(cx - 20f * u, cy - 46f * u)
        clipPath(Path().apply { addOval(Rect(c, 73f * u)) }) {
            rotate(degrees = -22f, pivot = glossC) {
                drawOval(
                    color = Color(0x40FFFFFF),
                    topLeft = Offset(glossC.x - 38f * u, glossC.y - 12f * u),
                    size = Size(76f * u, 24f * u),
                )
            }
        }
        // Fine outline that catches the light on the face edge.
        drawCircle(color = Color(0x47FFFFFF), radius = 73f * u, center = c, style = Stroke(1f * u))
    }
}

// ---------------------------------------------------------------------------------------------
// Bottom action bar helpers (visual only; callbacks and enabled rules come from the caller).
// ---------------------------------------------------------------------------------------------
@Composable
private fun ActionBarContainer(content: @Composable RowScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(LineColor)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            content = content,
        )
    }
}

@Composable
private fun BottomAction(
    label: String,
    glyph: ActionGlyph,
    onClick: () -> Unit,
    enabled: Boolean = true,
    tint: Color = TextPrimary,
) {
    val alpha = if (enabled) 1f else 0.4f
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ActionGlyphIcon(glyph = glyph, color = tint.copy(alpha = alpha), modifier = Modifier.size(24.dp))
        Text(label, color = TextSecondary.copy(alpha = alpha), fontSize = 12.sp, maxLines = 1)
    }
}

private enum class ActionGlyph { CROWN, ACTIVITY, STOP, REFRESH, TRASH, PLUS }

/** Small line icons drawn on a Canvas so no icon library dependency is needed. */
@Composable
private fun ActionGlyphIcon(glyph: ActionGlyph, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 1.9f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(x: Float, y: Float) = Offset(x * u, y * u)
        when (glyph) {
            ActionGlyph.PLUS -> {
                drawLine(color = color, start = p(6f, 12f), end = p(18f, 12f), strokeWidth = 2.2f * u, cap = StrokeCap.Round)
                drawLine(color = color, start = p(12f, 6f), end = p(12f, 18f), strokeWidth = 2.2f * u, cap = StrokeCap.Round)
            }
            ActionGlyph.REFRESH -> {
                val r = 7.5f * u
                val center = p(12f, 12f)
                drawArc(
                    color = color,
                    startAngle = 20f,
                    sweepAngle = 290f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = stroke,
                )
                val a = 310.0 * PI / 180.0
                val ex = center.x + r * cos(a).toFloat()
                val ey = center.y + r * sin(a).toFloat()
                val tx = (-sin(a)).toFloat()
                val ty = cos(a).toFloat()
                val nx = cos(a).toFloat()
                val ny = sin(a).toFloat()
                val head = Path().apply {
                    moveTo(ex + tx * 3.6f * u, ey + ty * 3.6f * u)
                    lineTo(ex + nx * 3f * u, ey + ny * 3f * u)
                    lineTo(ex - nx * 3f * u, ey - ny * 3f * u)
                    close()
                }
                drawPath(head, color)
            }
            ActionGlyph.ACTIVITY -> {
                val path = Path().apply {
                    moveTo(3f * u, 12f * u)
                    lineTo(8f * u, 12f * u)
                    lineTo(10.5f * u, 6f * u)
                    lineTo(13.5f * u, 18f * u)
                    lineTo(16f * u, 12f * u)
                    lineTo(21f * u, 12f * u)
                }
                drawPath(path, color, style = stroke)
            }
            ActionGlyph.CROWN -> {
                val path = Path().apply {
                    moveTo(4f * u, 17f * u)
                    lineTo(4f * u, 8f * u)
                    lineTo(8.5f * u, 12f * u)
                    lineTo(12f * u, 6f * u)
                    lineTo(15.5f * u, 12f * u)
                    lineTo(20f * u, 8f * u)
                    lineTo(20f * u, 17f * u)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(color = color, start = p(4.5f, 20f), end = p(19.5f, 20f), strokeWidth = 1.9f * u, cap = StrokeCap.Round)
            }
            ActionGlyph.TRASH -> {
                drawLine(color = color, start = p(5f, 7f), end = p(19f, 7f), strokeWidth = 1.9f * u, cap = StrokeCap.Round)
                val handle = Path().apply {
                    moveTo(9.5f * u, 7f * u)
                    lineTo(9.5f * u, 4.5f * u)
                    lineTo(14.5f * u, 4.5f * u)
                    lineTo(14.5f * u, 7f * u)
                }
                drawPath(handle, color, style = stroke)
                val body = Path().apply {
                    moveTo(6.5f * u, 7f * u)
                    lineTo(7.5f * u, 19.5f * u)
                    lineTo(16.5f * u, 19.5f * u)
                    lineTo(17.5f * u, 7f * u)
                }
                drawPath(body, color, style = stroke)
            }
            ActionGlyph.STOP -> {
                drawRoundRect(
                    color = color,
                    topLeft = p(6.5f, 6.5f),
                    size = Size(11f * u, 11f * u),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f * u, 2f * u),
                    style = stroke,
                )
            }
        }
    }
}
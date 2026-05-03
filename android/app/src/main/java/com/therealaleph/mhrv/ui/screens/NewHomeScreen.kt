package com.therealaleph.mhrv.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.therealaleph.mhrv.*
import com.therealaleph.mhrv.ui.components.*
import com.therealaleph.mhrv.ui.components.formatDuration
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.ui.CaInstallOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import com.therealaleph.mhrv.ui.screens.UnlockSheet
import kotlinx.coroutines.delay
import org.json.JSONObject


@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun NewHomeScreen(
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInstallCaConfirmed: () -> Unit,
    onNavigateToSettings: () -> Unit,
    caOutcome: CaInstallOutcome?,
    onCaOutcomeConsumed: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(ConfigStore.load(ctx)) }
    val isRunning by VpnState.isRunning.collectAsState()
    val healthState by VpnHealthState.healthState.collectAsState()
    val verificationProgress by VpnHealthState.verificationProgress.collectAsState()
    val connectedSince by VpnState.connectedSince.collectAsState()
    val handle by VpnState.proxyHandle.collectAsState()
    var statsJson by remember { mutableStateOf("") }
    val todayCalls = remember(statsJson) {
        if (statsJson.isBlank()) 0L
        else runCatching { JSONObject(statsJson).optLong("today_calls", 0L) }.getOrDefault(0L)
    }
    var uptimeDisplay by remember { mutableStateOf("") }
    var checkJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    LaunchedEffect(isRunning, connectedSince) {
        if (isRunning && connectedSince != null) {
            while (true) {
                val secs = (System.currentTimeMillis() - connectedSince!!) / 1000
                uptimeDisplay = formatDuration(secs)
                delay(1000)
            }
        } else {
            uptimeDisplay = ""
        }
    }

    LaunchedEffect(handle) {
        if (handle != 0L) {
            while (true) {
                statsJson = withContext(Dispatchers.IO) {
                    runCatching { Native.statsJson(handle) }.getOrDefault("")
                }
                delay(1000)
            }
        } else {
            statsJson = ""
        }
    }

    val sheetState = rememberModalBottomSheetState()
    val certGuideSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showUnlockSheet by remember { mutableStateOf(false) }
    var showCertGuide by remember { mutableStateOf(false) }

    // Sync config when returning from settings or other changes
    LaunchedEffect(Unit) {
        cfg = ConfigStore.load(ctx)
        if (SecretsManager.hasEmbeddedSecrets() && 
            (!SecretsManager.hasUnlockedSecrets(ctx) || SecretsManager.isUpdateAvailable(ctx))) {
            showUnlockSheet = true
        }
    }

    LaunchedEffect(isRunning, cfg.mode) {
        if (isRunning) {
            if (cfg.mode == Mode.APPS_SCRIPT) {
                val fingerprint = withContext(Dispatchers.IO) { CaInstall.fingerprint(ctx) }
                if (fingerprint == null || !CaInstall.isInstalled(fingerprint)) {
                    VpnHealthState.setHealthState(HealthState.NEEDS_CERTIFICATE)
                    return@LaunchedEffect
                }
            }
            // Reset to UNKNOWN — user decides if/when to check
            if (healthState == HealthState.UNKNOWN) {
                // Stay unknown — no auto-verification
            }
        } else {
            VpnHealthState.reset()
        }
    }

    // Surface CA install result as a snackbar.
    LaunchedEffect(caOutcome) {
        val o = caOutcome ?: return@LaunchedEffect
        val msg = when (o) {
            is CaInstallOutcome.Installed ->
                "Certificate installed ✓"
            is CaInstallOutcome.NotInstalled -> buildString {
                append("Certificate not yet installed.")
                if (!o.downloadPath.isNullOrBlank()) {
                    append(" Saved to ${o.downloadPath}. ")
                    append("In Settings, search for \"CA certificate\" and install from there — NOT \"VPN & app user certificate\" or \"Wi-Fi\".")
                } else {
                    append(" Tap Install again to retry.")
                }
            }
            is CaInstallOutcome.Failed -> o.message
        }
        snackbarHostState.showSnackbar(msg, withDismissAction = true)
        onCaOutcomeConsumed()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("mhrv-rs", style = MaterialTheme.typography.titleLarge) },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .padding(inner)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val isConfigMissing = cfg.mode != Mode.DIRECT && (!cfg.hasDeploymentId || cfg.authKey.isBlank())

                UpdateBanner(
                    isPending = SecretsManager.isUpdatePending(ctx),
                    onClick = { showUnlockSheet = true }
                )

                ModeIndicator(
                    mode = cfg.mode,
                    onModeChanged = { newMode ->
                        cfg = cfg.copy(mode = newMode)
                        ConfigStore.save(ctx, cfg)
                    },
                    enabled = !isRunning,
                )

                ConnectionArea(
                    isRunning = isRunning,
                    isConfigMissing = isConfigMissing,
                    healthState = healthState,
                    verificationProgress = verificationProgress,
                    todayCalls = todayCalls,
                    uptimeDisplay = uptimeDisplay,
                    onStart = {
                        if (isConfigMissing) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    ctx.getString(R.string.err_config_required),
                                    withDismissAction = true
                                )
                            }
                            return@ConnectionArea false
                        }

                        scope.launch {
                            var updated = cfg
                            if (updated.googleIp.isBlank()) {
                                val fresh = withContext(Dispatchers.IO) {
                                    NetworkDetect.resolveGoogleIp()
                                }
                                if (!fresh.isNullOrBlank()) {
                                    updated = updated.copy(googleIp = fresh)
                                }
                            }
                            if (updated.frontDomain.isBlank() || 
                                updated.frontDomain.any { it.isDigit() || it == '.' || it == ':' }
                            ) {
                                updated = updated.copy(frontDomain = "www.google.com")
                            }
                            if (updated !== cfg) {
                                cfg = updated
                                ConfigStore.save(ctx, updated)
                            }
                            onStart()
                        }
                        true
                    },
                    onStop = onStop,
                    onCheckConnection = {
                        if (checkJob?.isActive == true) {
                            checkJob?.cancel()
                            VpnHealthState.setHealthState(HealthState.UNKNOWN)
                        } else {
                            checkJob = scope.launch {
                                if (cfg.mode == Mode.APPS_SCRIPT) {
                                    val fingerprint = withContext(Dispatchers.IO) { CaInstall.fingerprint(ctx) }
                                    if (fingerprint == null || !CaInstall.isInstalled(fingerprint)) {
                                        VpnHealthState.setHealthState(HealthState.NEEDS_CERTIFICATE)
                                        return@launch
                                    }
                                }
                                VpnHealthState.setHealthState(HealthState.VERIFYING)
                                try {
                                    val isHealthy = ConnectionTester.verifyConnection(
                                        mode = cfg.mode,
                                        proxyPort = cfg.listenPort,
                                        onProgress = { current, total ->
                                            VpnHealthState.setVerificationProgress(ctx.getString(R.string.status_attempt_prefix, current, total))
                                        }
                                    )
                                    VpnHealthState.setHealthState(if (isHealthy) HealthState.HEALTHY else HealthState.UNHEALTHY)
                                } catch (_: kotlinx.coroutines.CancellationException) {
                                    VpnHealthState.setHealthState(HealthState.UNKNOWN)
                                }
                            }
                        }
                    }
                )

                Spacer(Modifier.height(20.dp))

                // Visibility managed with default animations
                AnimatedVisibility(
                    visible = isRunning,
                    enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                    exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        LiveStatsCard(statsJson = statsJson)

                        ProxyShareCard(
                            httpPort = cfg.listenPort,
                            socks5Port = cfg.socks5Port ?: (cfg.listenPort + 1)
                        )

                        if (cfg.connectionMode == ConnectionMode.VPN_TUN) {
                            AppSplitButton(
                                cfg = cfg,
                                enabled = !isRunning,
                                onCfgChanged = {
                                    cfg = it
                                    ConfigStore.save(ctx, it)
                                },
                            )
                        }
                    }
                }

                // App Splitting button — visible when VPN is NOT running so
                // the user can set up splitting before connecting.
                AnimatedVisibility(
                    visible = !isRunning && cfg.connectionMode == ConnectionMode.VPN_TUN,
                    enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                    exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                ) {
                    AppSplitButton(
                        cfg = cfg,
                        enabled = true,
                        onCfgChanged = {
                            cfg = it
                            ConfigStore.save(ctx, it)
                        },
                    )
                }

                AnimatedVisibility(
                    visible = cfg.mode == Mode.APPS_SCRIPT,
                    enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(300)),
                    exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(300))
                ) {
                    CertActionCard(onInstallClick = { showCertGuide = true })
                }

                GoogleIpCard(
                    cfg = cfg,
                    onUpdate = { 
                        cfg = it
                        ConfigStore.save(ctx, it)
                    }
                )
            }

            if (showUnlockSheet) {
                ModalBottomSheet(
                    onDismissRequest = { showUnlockSheet = false },
                    sheetState = sheetState,
                    dragHandle = { BottomSheetDefaults.DragHandle() },
                ) {
                    UnlockSheet(
                        isUpdate = SecretsManager.hasUnlockedSecrets(ctx),
                        onUnlocked = {
                            val updated = SecretsManager.forceApplySecrets(ctx, ConfigStore.load(ctx))
                            ConfigStore.save(ctx, updated)
                            cfg = updated 
                            showUnlockSheet = false
                        },
                        onSkip = {
                            SecretsManager.skipUpdate(ctx)
                            showUnlockSheet = false
                        }
                    )
                }
            }

            if (showCertGuide) {
                ModalBottomSheet(
                    onDismissRequest = { showCertGuide = false },
                    sheetState = certGuideSheetState,
                    dragHandle = { BottomSheetDefaults.DragHandle() },
                ) {
                    CertInstallGuide(
                        onOpenSettings = onInstallCaConfirmed,
                        onDismiss = { showCertGuide = false }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun UpdateBanner(
    isPending: Boolean,
    onClick: () -> Unit
) {
    AnimatedVisibility(
        visible = isPending,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.15f),
            shape = MaterialTheme.shapes.small
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    stringResource(R.string.banner_update_desc),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = onClick,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        stringResource(R.string.banner_btn_apply),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun ConnectionArea(
    isRunning: Boolean,
    isConfigMissing: Boolean,
    healthState: HealthState,
    verificationProgress: String?,
    todayCalls: Long,
    uptimeDisplay: String,
    onStart: () -> Boolean,
    onStop: () -> Unit,
    onCheckConnection: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ConnectButton(
                enabled = isRunning || !isConfigMissing,
                uptimeDisplay = uptimeDisplay,
                onStart = onStart,
                onStop = onStop
            )

            AnimatedContent(
                targetState = Triple(isRunning, healthState, isConfigMissing),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "StatusText"
            ) { state ->
                val (running, health, missing) = state
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = when {
                            running && health == HealthState.NEEDS_CERTIFICATE -> stringResource(R.string.status_needs_certificate)
                            running -> stringResource(R.string.label_requests_sent, todayCalls)
                            missing -> stringResource(R.string.status_config_incomplete)
                            else -> ""
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            running && health == HealthState.NEEDS_CERTIFICATE -> MaterialTheme.colorScheme.error
                            running -> com.therealaleph.mhrv.ui.theme.OkGreen
                            missing -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (missing) {
                        Text(
                            text = stringResource(R.string.help_config_required_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isRunning,
            enter = slideInVertically { it / 2 } + fadeIn(),
            exit = slideOutVertically { it / 2 } + fadeOut()
        ) {
            OutlinedButton(
                onClick = onCheckConnection,
                modifier = Modifier.height(36.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    when (healthState) {
                        HealthState.HEALTHY -> com.therealaleph.mhrv.ui.theme.OkGreen.copy(alpha = 0.5f)
                        HealthState.UNHEALTHY, HealthState.VERIFYING -> com.therealaleph.mhrv.ui.theme.ConnectingAmber.copy(alpha = 0.5f)
                        else -> MaterialTheme.colorScheme.outline
                    }
                )
            ) {
                AnimatedContent(
                    targetState = healthState,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "HealthCheckContent"
                ) { targetState ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        when (targetState) {
                            HealthState.VERIFYING -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = com.therealaleph.mhrv.ui.theme.ConnectingAmber
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = verificationProgress ?: stringResource(R.string.status_verifying),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = com.therealaleph.mhrv.ui.theme.ConnectingAmber
                                )
                            }
                            HealthState.HEALTHY -> {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp), tint = com.therealaleph.mhrv.ui.theme.OkGreen)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.status_check_passed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = com.therealaleph.mhrv.ui.theme.OkGreen
                                )
                            }
                            HealthState.UNHEALTHY -> {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp), tint = com.therealaleph.mhrv.ui.theme.ConnectingAmber)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.status_connected_no_internet),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = com.therealaleph.mhrv.ui.theme.ConnectingAmber
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.btn_check_connection),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

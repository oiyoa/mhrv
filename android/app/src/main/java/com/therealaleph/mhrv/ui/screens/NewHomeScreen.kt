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
import androidx.compose.ui.unit.dp
import com.therealaleph.mhrv.*
import com.therealaleph.mhrv.ui.components.*
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.ui.CaInstallOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
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

    // Sync config when returning from settings or other changes
    LaunchedEffect(Unit) {
        cfg = ConfigStore.load(ctx)
    }

    LaunchedEffect(isRunning) {
        if (isRunning) {
            if (healthState == HealthState.UNKNOWN) {
                VpnHealthState.setHealthState(HealthState.VERIFYING)
                val isHealthy = ConnectionTester.verifyConnection(proxyPort = cfg.listenPort)
                VpnHealthState.setHealthState(if (isHealthy) HealthState.HEALTHY else HealthState.UNHEALTHY)
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
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            val isConfigMissing = cfg.mode != Mode.GOOGLE_ONLY && (!cfg.hasDeploymentId || cfg.authKey.isBlank())

            ModeIndicator(
                mode = cfg.mode,
                onModeChanged = { newMode ->
                    cfg = cfg.copy(mode = newMode)
                    ConfigStore.save(ctx, cfg)
                },
                enabled = !isRunning,
            )

            Spacer(Modifier.height(8.dp))

            ConnectButton(
                enabled = isRunning || !isConfigMissing,
                onStart = {
                    if (isConfigMissing) {
                        scope.launch {
                            snackbarHostState.showSnackbar(
                                ctx.getString(R.string.err_config_required),
                                withDismissAction = true
                            )
                        }
                        return@ConnectButton false
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
                        // Reuse the repair logic for front_domain
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
                onStop = onStop
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            isRunning && (healthState == HealthState.VERIFYING || healthState == HealthState.UNKNOWN) -> stringResource(R.string.status_verifying)
                            isRunning && healthState == HealthState.UNHEALTHY -> stringResource(R.string.status_connected_no_internet)
                            isRunning && healthState == HealthState.HEALTHY -> stringResource(R.string.status_protected)
                            isRunning -> stringResource(R.string.status_verifying) // Fallback for transition
                            isConfigMissing -> stringResource(R.string.status_config_incomplete)
                            else -> stringResource(R.string.status_not_connected)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = when {
                            isRunning && (healthState == HealthState.VERIFYING || healthState == HealthState.UNKNOWN) -> com.therealaleph.mhrv.ui.theme.ConnectingAmber
                            isRunning && healthState == HealthState.UNHEALTHY -> com.therealaleph.mhrv.ui.theme.ErrRed
                            isRunning && healthState == HealthState.HEALTHY -> com.therealaleph.mhrv.ui.theme.OkGreen
                            isRunning -> com.therealaleph.mhrv.ui.theme.ConnectingAmber
                            isConfigMissing -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    
                    if (isRunning && healthState != HealthState.VERIFYING) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    VpnHealthState.setHealthState(HealthState.VERIFYING)
                                    val isHealthy = ConnectionTester.verifyConnection(proxyPort = cfg.listenPort)
                                    VpnHealthState.setHealthState(if (isHealthy) HealthState.HEALTHY else HealthState.UNHEALTHY)
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.btn_verify_connection),
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }

                if (isConfigMissing) {
                    Text(
                        text = stringResource(R.string.help_config_required_sub),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }

            LiveStatsCard()

            if (cfg.mode == Mode.APPS_SCRIPT) {
                CertActionCard(onInstallClick = onInstallCaConfirmed)
            }

            // Compact connectivity check row — no card wrapper needed
            GoogleIpCard(
                cfg = cfg,
                onUpdate = { 
                    cfg = it
                    ConfigStore.save(ctx, it)
                }
            )
        }
    }
}

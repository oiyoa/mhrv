package com.therealaleph.mhrv.ui.components

import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.therealaleph.mhrv.*
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.ui.theme.ErrRed
import com.therealaleph.mhrv.ui.theme.OkGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun GoogleIpCard(
    cfg: MhrvConfig,
    onUpdate: (MhrvConfig) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    
    val result by GoogleReachabilityState.overallResult.collectAsState()
    val checking by GoogleReachabilityState.isChecking.collectAsState()
    val sniResults = GoogleReachabilityState.sniResults.collectAsState().value
    
    val snisToTest = GoogleReachabilityState.snisToTest
    var expanded by remember { mutableStateOf(false) }

    // Auto-run on open (respects hasAutoRun internally)
    LaunchedEffect(Unit) {
        GoogleReachabilityState.checkAll(ctx, cfg, scope, force = false, onUpdate = onUpdate)
    }

    val hasResult = result is GoogleCheckResult.Connected || result is GoogleCheckResult.NotConnected
    val isTestingSnis = snisToTest.any { sniResults[it] is GoogleCheckResult.Checking }
    val connectedCount = snisToTest.count { sniResults[it] is GoogleCheckResult.Connected }

    val statusColor by animateColorAsState(
        targetValue = when {
            !hasResult || isTestingSnis -> MaterialTheme.colorScheme.onSurfaceVariant
            connectedCount == snisToTest.size -> OkGreen
            connectedCount > 0 -> androidx.compose.ui.graphics.Color(0xFFFFA000) // Amber
            else -> ErrRed
        },
        animationSpec = tween(300),
        label = "StatusColor",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val isLoading = checking || isTestingSnis
                val statusTextRes = when {
                    isLoading -> R.string.status_google_checking
                    !hasResult -> R.string.status_google_idle
                    connectedCount == snisToTest.size -> R.string.status_google_reachable
                    connectedCount > 0 -> R.string.status_google_partially_reachable
                    else -> R.string.status_google_unreachable
                }
                val titleColor = if (isLoading || !hasResult) MaterialTheme.colorScheme.onSurface else statusColor

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasResult && !isLoading) {
                            val icon = when {
                                connectedCount == snisToTest.size -> Icons.Default.CheckCircle
                                connectedCount > 0 -> Icons.Default.Warning
                                else -> Icons.Default.Cancel
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = statusColor
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        AnimatedContent(
                            targetState = statusTextRes,
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label = "GoogleStatusText"
                        ) { targetRes ->
                            Text(
                                text = stringResource(targetRes),
                                style = MaterialTheme.typography.titleSmall,
                                color = titleColor
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "IP: ${if (cfg.googleIp.isNotBlank()) cfg.googleIp else "Unknown"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { GoogleReachabilityState.checkAll(ctx, cfg, scope, force = true, onUpdate = onUpdate) },
                        enabled = !isLoading,
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Refresh",
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(Modifier.width(4.dp))

                    Icon(
                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    snisToTest.forEach { sni ->
                        val res = sniResults[sni] ?: GoogleCheckResult.Idle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            val icon = when (res) {
                                is GoogleCheckResult.Connected -> Icons.Default.CheckCircle
                                is GoogleCheckResult.NotConnected -> Icons.Default.Cancel
                                else -> null
                            }
                            val color = when (res) {
                                is GoogleCheckResult.Connected -> OkGreen
                                is GoogleCheckResult.NotConnected -> ErrRed
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            
                            if (res is GoogleCheckResult.Checking) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (icon != null) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = color
                                )
                            } else {
                                Spacer(Modifier.size(12.dp))
                            }

                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = sni,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            if (res is GoogleCheckResult.Connected && res.latencyMs > 0) {
                                Spacer(Modifier.weight(1f))
                                Text(
                                    text = "${res.latencyMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = color
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.therealaleph.mhrv.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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

/**
 * Result state of a connectivity check.
 */
private sealed interface CheckResult {
    data object Idle : CheckResult
    data object Checking : CheckResult
    data class Connected(val latencyMs: Int) : CheckResult
    data object NotConnected : CheckResult
}

@Composable
fun GoogleIpCard(
    cfg: MhrvConfig,
    onUpdate: (MhrvConfig) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<CheckResult>(CheckResult.Idle) }
    var checking by remember { mutableStateOf(false) }
    
    // Track results for individual SNIs
    val sniResults = remember { mutableStateMapOf<String, CheckResult>() }
    // Take a small list of SNIs to test
    val snisToTest = remember { DEFAULT_SNI_POOL.take(5) }
    var expanded by remember { mutableStateOf(false) }

    fun checkAll() {
        if (checking) return
        checking = true
        result = CheckResult.Checking
        snisToTest.forEach { sni -> sniResults[sni] = CheckResult.Checking }

        scope.launch {
            var currentCfg = cfg
            var connected = false
            var latency = -1

            // 1. Initial Test
            if (currentCfg.googleIp.isNotBlank()) {
                val json = withContext(Dispatchers.IO) {
                    runCatching { Native.testSni(currentCfg.googleIp, currentCfg.frontDomain) }.getOrNull()
                }
                if (json != null) {
                    val obj = try { JSONObject(json) } catch (_: Exception) { null }
                    if (obj?.optBoolean("ok") == true) {
                        connected = true
                        latency = obj.optInt("latencyMs", -1)
                    }
                }
            }

            // 2. Auto-detect if failed or no IP
            if (!connected) {
                val fresh = withContext(Dispatchers.IO) {
                    NetworkDetect.resolveGoogleIp()
                }
                if (!fresh.isNullOrBlank() && fresh != currentCfg.googleIp) {
                    currentCfg = currentCfg.copy(googleIp = fresh)
                    onUpdate(currentCfg)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "IP updated to $fresh", Toast.LENGTH_SHORT).show()
                    }

                    // 3. Retest with new IP
                    val json2 = withContext(Dispatchers.IO) {
                        runCatching { Native.testSni(currentCfg.googleIp, currentCfg.frontDomain) }.getOrNull()
                    }
                    if (json2 != null) {
                        val obj = try { JSONObject(json2) } catch (_: Exception) { null }
                        if (obj?.optBoolean("ok") == true) {
                            connected = true
                            latency = obj.optInt("latencyMs", -1)
                        }
                    }
                } else if (!fresh.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "IP unchanged, still unreachable", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "DNS lookup failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            result = if (connected) CheckResult.Connected(latency) else CheckResult.NotConnected

            // 4. Test SNI pool in parallel
            snisToTest.forEach { sni ->
                scope.launch {
                    val resJson = withContext(Dispatchers.IO) {
                        runCatching { Native.testSni(currentCfg.googleIp, sni) }.getOrNull()
                    }
                    var sniConnected = false
                    var sniLatency = -1
                    if (resJson != null) {
                        val obj = try { JSONObject(resJson) } catch (_: Exception) { null }
                        if (obj?.optBoolean("ok") == true) {
                            sniConnected = true
                            sniLatency = obj.optInt("latencyMs", -1)
                        }
                    }
                    sniResults[sni] = if (sniConnected) CheckResult.Connected(sniLatency) else CheckResult.NotConnected
                }
            }
            
            checking = false
        }
    }

    // Auto-run on open
    LaunchedEffect(Unit) {
        checkAll()
    }

    val hasResult = result is CheckResult.Connected || result is CheckResult.NotConnected
    val isTestingSnis = snisToTest.any { sniResults[it] is CheckResult.Checking }
    val connectedCount = snisToTest.count { sniResults[it] is CheckResult.Connected }

    val statusColor by animateColorAsState(
        targetValue = when {
            !hasResult || isTestingSnis -> MaterialTheme.colorScheme.onSurfaceVariant
            connectedCount == snisToTest.size -> OkGreen
            connectedCount > 0 -> androidx.compose.ui.graphics.Color(0xFFFFA000) // Amber
            else -> ErrRed
        },
        animationSpec = tween(400),
        label = "StatusColor",
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
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
                        Text(
                            text = stringResource(statusTextRes),
                            style = MaterialTheme.typography.titleSmall,
                            color = titleColor
                        )
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
                        onClick = { checkAll() },
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
                        val res = sniResults[sni] ?: CheckResult.Idle
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            val icon = when (res) {
                                is CheckResult.Connected -> Icons.Default.CheckCircle
                                is CheckResult.NotConnected -> Icons.Default.Cancel
                                else -> null
                            }
                            val color = when (res) {
                                is CheckResult.Connected -> OkGreen
                                is CheckResult.NotConnected -> ErrRed
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }

                            if (res is CheckResult.Checking) {
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

                            if (res is CheckResult.Connected && res.latencyMs > 0) {
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

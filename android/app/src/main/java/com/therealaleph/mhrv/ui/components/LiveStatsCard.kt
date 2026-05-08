package com.therealaleph.mhrv.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.therealaleph.mhrv.Native
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun LiveStatsCard(statsJson: String) {
    val freeQuotaPerDay = 20_000
    var expanded by remember { mutableStateOf(false) }

    val obj = remember(statsJson) {
        if (statsJson.isBlank()) null
        else runCatching { JSONObject(statsJson) }.getOrNull()
    }

    val todayCalls = obj?.optLong("today_calls", 0L) ?: 0L
    val todayBytes = obj?.optLong("today_bytes", 0L) ?: 0L
    val todayKey = obj?.optString("today_key", "") ?: ""
    val resetSecs = obj?.optLong("today_reset_secs", 0L) ?: 0L
    val pct = if (freeQuotaPerDay > 0) (todayCalls.toDouble() / freeQuotaPerDay) * 100.0 else 0.0

    val ctx = LocalContext.current

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.sec_usage_stats),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(top = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Quota Section
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = stringResource(R.string.label_calls_today),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(
                                    R.string.usage_calls_of_quota,
                                    todayCalls.toInt(),
                                    freeQuotaPerDay,
                                    pct
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { (pct.toFloat() / 100f).coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = if (pct > 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }

                    // Stats Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(24.dp)
                    ) {
                        StatItem(
                            label = stringResource(R.string.label_bytes_today),
                            value = fmtBytes(todayBytes),
                            modifier = Modifier.weight(1f)
                        )
                        StatItem(
                            label = stringResource(R.string.label_resets_in),
                            value = stringResource(
                                R.string.usage_resets_hm,
                                (resetSecs / 3600).toInt(),
                                ((resetSecs / 60) % 60).toInt(),
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (todayKey.isNotEmpty()) {
                        Text(
                            text = "${stringResource(R.string.label_pt_day)}: $todayKey",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}


@Composable
private fun fmtBytes(b: Long): String {
    val k = 1024L
    val m = k * k
    val g = m * k
    return when {
        b >= g -> String.format("%.2f %s", b.toDouble() / g, stringResource(R.string.unit_gb))
        b >= m -> String.format("%.2f %s", b.toDouble() / m, stringResource(R.string.unit_mb))
        b >= k -> String.format("%.1f %s", b.toDouble() / k, stringResource(R.string.unit_kb))
        else -> String.format("%d %s", b, stringResource(R.string.unit_b))
    }
}

fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}

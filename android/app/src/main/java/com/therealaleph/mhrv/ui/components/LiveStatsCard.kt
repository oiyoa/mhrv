package com.therealaleph.mhrv.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.therealaleph.mhrv.Native
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
fun LiveStatsCard() {
    val freeQuotaPerDay = 20_000
    val handle by VpnState.proxyHandle.collectAsState()
    val isRunning by VpnState.isRunning.collectAsState()
    val connectedSince by VpnState.connectedSince.collectAsState()

    if (!isRunning || handle == 0L) return

    var statsJson by remember { mutableStateOf("") }
    var uptimeSecs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(handle) {
        statsJson = ""
        while (true) {
            statsJson = withContext(Dispatchers.IO) {
                runCatching { Native.statsJson(handle) }.getOrDefault("")
            }
            delay(1000)
        }
    }

    LaunchedEffect(connectedSince) {
        while (connectedSince != null) {
            uptimeSecs = (System.currentTimeMillis() - connectedSince!!) / 1000
            delay(1000)
        }
    }

    val obj = remember(statsJson) {
        if (statsJson.isBlank()) null
        else runCatching { JSONObject(statsJson) }.getOrNull()
    }

    val todayCalls = obj?.optLong("today_calls", 0L) ?: 0L
    val todayBytes = obj?.optLong("today_bytes", 0L) ?: 0L
    val todayKey = obj?.optString("today_key", "") ?: ""
    val resetSecs = obj?.optLong("today_reset_secs", 0L) ?: 0L
    val pct = if (freeQuotaPerDay > 0) {
        (todayCalls.toDouble() / freeQuotaPerDay) * 100.0
    } else 0.0

    val ctx = LocalContext.current

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.sec_usage_today),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = formatDuration(uptimeSecs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            UsageRow(
                label = stringResource(R.string.label_calls_today),
                value = stringResource(
                    R.string.usage_calls_of_quota,
                    todayCalls.toInt(),
                    freeQuotaPerDay,
                    pct,
                ),
            )
            UsageRow(
                label = stringResource(R.string.label_bytes_today),
                value = fmtBytes(todayBytes),
            )
            if (todayKey.isNotEmpty()) {
                UsageRow(
                    label = stringResource(R.string.label_utc_day),
                    value = todayKey,
                )
            }
            UsageRow(
                label = stringResource(R.string.label_resets_in),
                value = stringResource(
                    R.string.usage_resets_hm,
                    (resetSecs / 3600).toInt(),
                    ((resetSecs / 60) % 60).toInt(),
                ),
            )

            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = {
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://script.google.com/home/usage"),
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { ctx.startActivity(intent) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.btn_view_quota_on_google))
            }
            Text(
                stringResource(R.string.usage_today_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

private fun fmtBytes(b: Long): String {
    val k = 1024L
    val m = k * k
    val g = m * k
    return when {
        b >= g -> String.format("%.2f GB", b.toDouble() / g)
        b >= m -> String.format("%.2f MB", b.toDouble() / m)
        b >= k -> String.format("%.1f KB", b.toDouble() / k)
        else -> "$b B"
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format("%02d:%02d:%02d", h, m, s)
}

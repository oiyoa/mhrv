package com.therealaleph.mhrv.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.therealaleph.mhrv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore

/**
 * Collapsible card with proxy sharing details.
 */
@Composable
fun ProxyShareCard(
    httpPort: Int,
    socks5Port: Int
) {
    var expanded by remember { mutableStateOf(false) }
    var localIp by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current

    LaunchedEffect(Unit) {
        while (true) {
            localIp = getLocalIpAddress()
            delay(5000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
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
                        imageVector = Icons.Default.Laptop,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.share_proxy_title),
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
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (localIp != null) {
                        ProxyRow(
                            label = stringResource(R.string.share_proxy_http),
                            address = "$localIp:$httpPort",
                            onCopy = {
                                clipboardManager.setText(AnnotatedString("$localIp:$httpPort"))
                            }
                        )
                        ProxyRow(
                            label = stringResource(R.string.share_proxy_socks5),
                            address = "$localIp:$socks5Port",
                            onCopy = {
                                clipboardManager.setText(AnnotatedString("$localIp:$socks5Port"))
                            }
                        )
                        
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.share_proxy_hint),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "No Wi-Fi or Hotspot detected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProxyRow(
    label: String,
    address: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(60.dp)
        )
        Text(
            text = address,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(24.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

private suspend fun getLocalIpAddress(): String? = withContext(Dispatchers.IO) {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            
            // Prioritize Wi-Fi and Hotspot interfaces
            val name = iface.name.lowercase()
            if (name.contains("wlan") || name.contains("ap") || name.contains("softap")) {
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address) {
                        return@withContext addr.hostAddress
                    }
                }
            }
        }
        
        // Fallback to any non-loopback IPv4
        val allInterfaces = NetworkInterface.getNetworkInterfaces()
        while (allInterfaces.hasMoreElements()) {
            val iface = allInterfaces.nextElement()
            if (iface.isLoopback || !iface.isUp) continue
            val addresses = iface.inetAddresses
            while (addresses.hasMoreElements()) {
                val addr = addresses.nextElement()
                if (addr is Inet4Address) {
                    return@withContext addr.hostAddress
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
    null
}

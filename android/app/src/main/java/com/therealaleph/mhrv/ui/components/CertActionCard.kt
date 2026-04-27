package com.therealaleph.mhrv.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.therealaleph.mhrv.CaInstall
import com.therealaleph.mhrv.ui.theme.OkGreen

@Composable
fun CertActionCard(
    onInstallClick: () -> Unit
) {
    val ctx = LocalContext.current
    var isInstalled by remember { mutableStateOf(false) }
    
    // Periodically re-check (or on entry) if the cert is in the AndroidCAStore.
    LaunchedEffect(Unit) {
        while (true) {
            val fp = CaInstall.fingerprint(ctx)
            isInstalled = fp?.let { CaInstall.isInstalled(it) } ?: false
            kotlinx.coroutines.delay(3000)
        }
    }

    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isInstalled) CardDefaults.outlinedCardColors() 
                 else CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (isInstalled) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (isInstalled) OkGreen else MaterialTheme.colorScheme.error
            )
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isInstalled) "Certificate Installed" else "Certificate Required",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = if (isInstalled) "MITM decryption is active" else "Install to enable MITM mode",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (!isInstalled) {
                Button(
                    onClick = onInstallClick,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.VerifiedUser, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Install", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

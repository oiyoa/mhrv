package com.therealaleph.mhrv.ui.components

import androidx.compose.animation.*
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.CaInstall
import com.therealaleph.mhrv.ui.theme.OkGreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CertActionCard(
    onInstallClick: () -> Unit
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var isInstalled by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    // Re-check on every ON_RESUME so the card flips to "installed" the moment
    // the user returns from the system Settings cert-install flow. A timed
    // poll loop here would leave a stale "Install" button on screen for up to
    // its interval and would miss the case where the activity is recreated
    // while backgrounded. Lifecycle events fire forward only, so we also do
    // an initial check on first composition.
    DisposableEffect(lifecycleOwner) {
        val recheck: () -> Unit = {
            scope.launch {
                val fp = withContext(Dispatchers.IO) { CaInstall.fingerprint(ctx) }
                isInstalled = fp != null &&
                    withContext(Dispatchers.IO) { CaInstall.isInstalled(fp) }
                isLoading = false
            }
        }
        recheck()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AnimatedContent(
        targetState = isLoading,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "CertCardState"
    ) { loading ->
        if (loading) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.cert_status_checking), style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
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
                            text = if (isInstalled) stringResource(R.string.cert_status_installed) else stringResource(R.string.cert_status_required),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = if (isInstalled) stringResource(R.string.cert_status_active) else stringResource(R.string.cert_status_enable),
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
                            Text(stringResource(R.string.btn_install), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

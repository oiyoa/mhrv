package com.therealaleph.mhrv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.therealaleph.mhrv.HealthState
import com.therealaleph.mhrv.VpnHealthState
import com.therealaleph.mhrv.VpnState
import com.therealaleph.mhrv.ui.theme.ConnectedGreen
import com.therealaleph.mhrv.ui.theme.ConnectingAmber
import com.therealaleph.mhrv.ui.theme.DisconnectedGray
import com.therealaleph.mhrv.ui.theme.ErrRed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectButton(
    onStart: () -> Boolean,
    onStop: () -> Unit,
    enabled: Boolean = true
) {
    val isRunning by VpnState.isRunning.collectAsState()
    val healthState by VpnHealthState.healthState.collectAsState()
    var awaitingRunning by remember { mutableStateOf<Boolean?>(null) }
    val transitioning = awaitingRunning != null

    LaunchedEffect(awaitingRunning) {
        val target = awaitingRunning ?: return@LaunchedEffect
        try {
            withTimeoutOrNull(12_000) {
                VpnState.isRunning.first { it == target }
            }
        } finally {
            awaitingRunning = null
        }
    }

    val targetColor = when {
        transitioning || (isRunning && (healthState == HealthState.VERIFYING || healthState == HealthState.UNKNOWN)) -> ConnectingAmber
        isRunning && healthState == HealthState.UNHEALTHY -> ErrRed
        isRunning && healthState == HealthState.HEALTHY -> ConnectedGreen
        isRunning -> ConnectingAmber
        !enabled -> DisconnectedGray.copy(alpha = 0.5f)
        else -> DisconnectedGray
    }

    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 500),
        label = "ButtonColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "Rotation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "RotationAngle"
    )

    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        val showProgress = transitioning || (isRunning && (healthState == HealthState.VERIFYING || healthState == HealthState.UNKNOWN))
        if (showProgress) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = ConnectingAmber,
                    startAngle = rotation,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        Surface(
            onClick = {
                if (transitioning) return@Surface
                if (isRunning) {
                    awaitingRunning = false
                    onStop()
                } else {
                    if (onStart()) {
                        awaitingRunning = true
                    }
                }
            },
            shape = CircleShape,
            color = animatedColor,
            modifier = Modifier.size(160.dp),
            shadowElevation = if (isRunning && enabled) 8.dp else 2.dp,
            tonalElevation = if (isRunning && enabled) 8.dp else 2.dp
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = "",
                    modifier = Modifier.size(48.dp),
                    tint = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        transitioning -> "Wait..."
                        isRunning -> "Stop"
                        else -> "Connect"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
            }
        }
    }
}

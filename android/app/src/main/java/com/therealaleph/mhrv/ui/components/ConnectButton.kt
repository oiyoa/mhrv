package com.therealaleph.mhrv.ui.components

import androidx.compose.animation.*
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.VpnState
import com.therealaleph.mhrv.ui.theme.ConnectedGreen
import com.therealaleph.mhrv.ui.theme.ConnectingAmber
import com.therealaleph.mhrv.ui.theme.DisconnectedGray
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private enum class ConnectButtonState {
    DISCONNECTED,
    STARTING,
    CONNECTED,
    STOPPING
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectButton(
    onStart: () -> Boolean,
    onStop: () -> Unit,
    enabled: Boolean = true,
    uptimeDisplay: String = ""
) {
    val isRunning by VpnState.isRunning.collectAsState()
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

    val state = when {
        awaitingRunning == true -> ConnectButtonState.STARTING
        awaitingRunning == false -> ConnectButtonState.STOPPING
        isRunning -> ConnectButtonState.CONNECTED
        else -> ConnectButtonState.DISCONNECTED
    }

    val targetColor = when (state) {
        ConnectButtonState.STARTING -> ConnectingAmber
        ConnectButtonState.CONNECTED -> ConnectedGreen
        ConnectButtonState.STOPPING, ConnectButtonState.DISCONNECTED -> {
            if (!enabled) DisconnectedGray.copy(alpha = 0.5f) else DisconnectedGray
        }
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

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseScale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseAlpha"
    )

    Box(
        modifier = Modifier.size(200.dp),
        contentAlignment = Alignment.Center
    ) {
        if (state == ConnectButtonState.STARTING) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    color = ConnectingAmber,
                    startAngle = rotation,
                    sweepAngle = 90f,
                    useCenter = false,
                    style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        } else if (state == ConnectButtonState.CONNECTED) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = ConnectedGreen,
                    radius = (160.dp.toPx() / 2f) * pulseScale,
                    alpha = pulseAlpha
                )
            }
        }

    val elevation by animateDpAsState(
        targetValue = if (isRunning && enabled) 12.dp else 2.dp,
        animationSpec = tween(durationMillis = 600),
        label = "ButtonElevation"
    )



    Surface(
        onClick = {
            if (transitioning || !enabled) return@Surface
            if (isRunning) {
                awaitingRunning = false
                onStop()
            } else {
                if (onStart()) {
                    awaitingRunning = true
                }
            }
        },
        enabled = enabled,
        shape = CircleShape,
        color = animatedColor,
        modifier = Modifier.size(160.dp),
        shadowElevation = elevation,
        tonalElevation = elevation
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
                AnimatedContent(
                    targetState = when (state) {
                        ConnectButtonState.STARTING, ConnectButtonState.STOPPING -> stringResource(R.string.status_wait)
                        ConnectButtonState.DISCONNECTED -> stringResource(R.string.btn_connect)
                        else -> uptimeDisplay.ifBlank { stringResource(R.string.btn_disconnect) }
                    },
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "ButtonText"
                ) { targetText ->
                    Text(
                        text = targetText,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }
        }
    }
}

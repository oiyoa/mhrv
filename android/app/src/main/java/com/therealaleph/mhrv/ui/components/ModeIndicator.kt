package com.therealaleph.mhrv.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.therealaleph.mhrv.*
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.ui.theme.*

private data class ModeOption(
    val mode: Mode,
    val label: String,
    val shortLabel: String,
    val icon: @Composable (Color) -> Unit,
    val accent: Color,
)

private val modeOptions = listOf(
    ModeOption(
        mode = Mode.DIRECT,
        label = "Direct",
        shortLabel = "Direct",
        icon = { color ->
            Text(
                text = "G",
                color = color,
                fontWeight = FontWeight.Black,
                fontSize = 16.sp,
            )
        },
        accent = ModeBlueAccent,
    ),
    ModeOption(
        mode = Mode.FULL,
        label = "Full",
        shortLabel = "Full Tunnel",
        icon = { color ->
            Icon(
                imageVector = Icons.Default.VpnLock,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = color,
            )
        },
        accent = ModeGreenAccent,
    ),
    ModeOption(
        mode = Mode.APPS_SCRIPT,
        label = "Relay",
        shortLabel = "Relay",
        icon = { color ->
            Icon(
                imageVector = Icons.Default.Terminal,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = color,
            )
        },
        accent = ModeTealAccent,
    ),
)

@Composable
fun ModeIndicator(
    mode: Mode,
    onModeChanged: (Mode) -> Unit,
    enabled: Boolean = true,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Mode",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            modeOptions.forEachIndexed { index, option ->
                val selected = mode == option.mode
                val isMiddle = index == 1

                // Use vibrant accent ONLY when VPN is running (!enabled)
                // Otherwise use a neutral primary color
                val activeAccent = if (!enabled) option.accent else MaterialTheme.colorScheme.primary

                val bgColor by animateColorAsState(
                    targetValue = if (selected) activeAccent.copy(alpha = 0.18f)
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    animationSpec = tween(200),
                    label = "ModeBg${option.mode}",
                )
                val contentColor by animateColorAsState(
                    targetValue = if (selected) activeAccent
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    animationSpec = tween(200),
                    label = "ModeFg${option.mode}",
                )
                val borderColor by animateColorAsState(
                    targetValue = if (selected) activeAccent.copy(alpha = 0.6f)
                    else Color.Transparent,
                    animationSpec = tween(200),
                    label = "ModeBorder${option.mode}",
                )
                val elevation by animateDpAsState(
                    targetValue = if (selected) 4.dp else 0.dp,
                    animationSpec = tween(200),
                    label = "ModeElevation${option.mode}"
                )
                val borderWidth by animateDpAsState(
                    targetValue = if (selected) 1.5.dp else 0.dp,
                    animationSpec = tween(200),
                    label = "ModeBorderWidth${option.mode}"
                )

                val weight = if (isMiddle) 1.2f else 0.9f
                val height = if (isMiddle) 68.dp else 56.dp
                val iconSize = if (isMiddle) 22.dp else 18.dp
                val fontSize = if (isMiddle) 12.sp else 11.sp

                Surface(
                    onClick = { if (enabled) onModeChanged(option.mode) },
                    modifier = Modifier
                        .weight(weight)
                        .height(height),
                    shape = RoundedCornerShape(12.dp),
                    color = bgColor,
                    border = if (selected) BorderStroke(borderWidth, borderColor) else null,
                    shadowElevation = elevation,
                    tonalElevation = elevation,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(iconSize),
                            contentAlignment = Alignment.Center
                        ) {
                            option.icon(contentColor)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = option.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = fontSize,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = contentColor,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Descriptive subtitle for current mode
        val description = when (mode) {
            Mode.APPS_SCRIPT -> stringResource(R.string.mode_desc_apps_script)
            Mode.DIRECT -> stringResource(R.string.mode_desc_direct)
            Mode.FULL -> stringResource(R.string.mode_desc_full)
        }
        AnimatedContent(
            targetState = description,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "ModeDescription"
        ) { targetDesc ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.padding(horizontal = 24.dp)
            ) {
                Text(
                    text = targetDesc,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }
        }
    }
}

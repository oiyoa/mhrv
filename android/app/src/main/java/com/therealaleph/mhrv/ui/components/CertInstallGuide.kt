package com.therealaleph.mhrv.ui.components

import android.content.Context
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.ui.CertInstallViewModel
import com.therealaleph.mhrv.ui.theme.OkGreen

@Composable
fun CertInstallGuide(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: CertInstallViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    // Initialize logic on launch
    LaunchedEffect(Unit) {
        viewModel.initialize(ctx)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Spacer(Modifier.height(16.dp))

        if (viewModel.isInstalled) {
            // Dedicated Success View
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(OkGreen.copy(alpha = 0.1f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = OkGreen,
                        modifier = Modifier.size(48.dp)
                    )
                }
                
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.cert_guide_step4_success),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = OkGreen,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.cert_guide_success_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.cert_guide_btn_done))
                }
            }
        } else {
            Text(
                text = stringResource(R.string.cert_guide_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Step 1: Save
            StepperItem(
                stepNumber = 1,
                title = stringResource(R.string.cert_guide_step1_title),
                isLast = false,
                isActive = viewModel.currentStep == 1,
                isCompleted = viewModel.currentStep > 1
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.cert_guide_step1_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (viewModel.saveError != null) {
                        InfoCallout(
                            text = viewModel.saveError!!,
                            icon = Icons.Default.Warning,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    } else if (viewModel.saveStatus != null) {
                        InfoCallout(
                            text = viewModel.saveStatus!!,
                            icon = Icons.Default.CheckCircle,
                            containerColor = OkGreen.copy(alpha = 0.1f),
                            contentColor = OkGreen
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (viewModel.saveStatus == null) {
                            Button(
                                onClick = { viewModel.handleSave(ctx) },
                                enabled = !viewModel.isSaving
                            ) {
                                if (viewModel.isSaving) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Icon(Icons.Default.SaveAlt, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.cert_guide_btn_save))
                            }
                        } else {
                            Button(
                                onClick = { viewModel.nextStep() }
                            ) {
                                Text(stringResource(R.string.btn_continue))
                                Spacer(Modifier.width(8.dp))
                                Icon(Icons.AutoMirrored.Filled.ArrowForward, null, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            // Step 2: Settings & Instructions
            StepperItem(
                stepNumber = 2,
                title = stringResource(R.string.cert_guide_step2_title),
                isLast = false,
                isActive = viewModel.currentStep >= 2 && viewModel.currentStep < 4,
                isCompleted = viewModel.currentStep >= 4
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(R.string.cert_guide_step2_search),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    SearchBarIllustration(stringResource(R.string.cert_guide_step2_correct))

                    SearchResultsIllustration()

                    val sdk = Build.VERSION.SDK_INT
                    val pathHint = when {
                        isSamsung && sdk <= Build.VERSION_CODES.S -> stringResource(R.string.cert_guide_path_samsung_12)
                        isSamsung -> stringResource(R.string.cert_guide_path_samsung_13)
                        sdk <= Build.VERSION_CODES.Q -> stringResource(R.string.cert_guide_path_stock_10)
                        sdk <= Build.VERSION_CODES.S -> stringResource(R.string.cert_guide_path_stock_11)
                        else -> stringResource(R.string.cert_guide_path_stock_13)
                    }

                    InfoCallout(
                        text = pathHint,
                        icon = Icons.Default.Explore
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    Text(
                        text = stringResource(R.string.cert_guide_step3_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    Text(
                        text = stringResource(R.string.cert_guide_step3_desc),
                        style = MaterialTheme.typography.bodyMedium
                    )

                    FilePickerIllustration(viewModel.detectedFileName)

                    WarningCallout(stringResource(R.string.cert_guide_step3_warning))

                    InfoCallout(
                        text = stringResource(R.string.cert_guide_step3_lock),
                        icon = Icons.Default.Lock
                    )

                    Button(
                        onClick = {
                            onOpenSettings()
                            viewModel.nextStep()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Settings, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cert_guide_step2_btn))
                    }
                }
            }

            // Final Step: Verify (Only visible if not installed)
            StepperItem(
                stepNumber = 3,
                title = stringResource(R.string.cert_guide_step4_title),
                isLast = true,
                isActive = viewModel.currentStep >= 3,
                isCompleted = false
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Text(
                        text = stringResource(R.string.cert_guide_step4_waiting),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = stringResource(R.string.cert_guide_step4_retry),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    OutlinedButton(
                        onClick = { viewModel.triggerManualCheck() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.cert_guide_btn_check))
                    }
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun StepperItem(
    stepNumber: Int,
    title: String,
    isLast: Boolean,
    isActive: Boolean,
    isCompleted: Boolean,
    content: @Composable () -> Unit
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isCompleted -> OkGreen
                            isActive -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (isCompleted) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp), tint = Color.White)
                } else {
                    Text(
                        text = stepNumber.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .weight(1f)
                        .padding(vertical = 4.dp)
                        .background(
                            if (isCompleted) OkGreen else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }

        Spacer(Modifier.width(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 24.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )

            AnimatedVisibility(
                visible = isActive,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun SearchBarIllustration(searchText: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                text = searchText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SearchResultsIllustration() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SearchResultRow(stringResource(R.string.cert_guide_step2_correct), true)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        SearchResultRow(stringResource(R.string.cert_guide_step2_wrong1), false)
        HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
        SearchResultRow(stringResource(R.string.cert_guide_step2_wrong2), false)
    }
}

@Composable
private fun SearchResultRow(text: String, isCorrect: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isCorrect) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = if (isCorrect) OkGreen else MaterialTheme.colorScheme.error.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (isCorrect) FontWeight.Bold else FontWeight.Normal,
            textDecoration = if (!isCorrect) TextDecoration.LineThrough else null,
            color = if (isCorrect) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun FilePickerIllustration(fileName: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Folder, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.label_downloads), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Description, null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun InfoCallout(
    text: String,
    icon: ImageVector,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(icon, null, modifier = Modifier.size(18.dp), tint = contentColor)
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor
            )
        }
    }
}

@Composable
private fun WarningCallout(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ReportProblem, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

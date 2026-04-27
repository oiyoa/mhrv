package com.therealaleph.mhrv.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.therealaleph.mhrv.R
import com.therealaleph.mhrv.SecretsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnlockScreen(
    isUpdate: Boolean = false,
    onUnlocked: () -> Unit,
    onSkip: () -> Unit,
    onEnterManually: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isDecrypting by remember { mutableStateOf(false) }
    var showLoading by remember { mutableStateOf(false) }

    // Debounce the loading state to avoid flicker for fast (cached or invalid) attempts
    LaunchedEffect(isDecrypting) {
        if (isDecrypting) {
            delay(300)
            showLoading = true
        } else {
            showLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            text = if (isUpdate) stringResource(R.string.unlock_update_title) else stringResource(R.string.unlock_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Text(
            text = if (isUpdate) 
                stringResource(R.string.unlock_update_desc) 
                else stringResource(R.string.unlock_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        
        Spacer(Modifier.height(32.dp))
        
        OutlinedTextField(
            value = password,
            onValueChange = { 
                password = it
                error = null
            },
            label = { Text(stringResource(R.string.unlock_field_password)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error != null,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                val image = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(imageVector = image, contentDescription = null)
                }
            }
        )
        
        if (error != null) {
            Text(
                text = error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp).align(Alignment.Start)
            )
        }
        
        Spacer(Modifier.height(24.dp))
        
        Button(
            onClick = {
                scope.launch {
                    isDecrypting = true
                    val secrets = withContext(Dispatchers.Default) {
                        SecretsManager.decryptBuildSecrets(password)
                    }
                    if (secrets != null) {
                        SecretsManager.storeUnlockedSecrets(ctx, secrets)
                        onUnlocked()
                    } else {
                        error = ctx.getString(R.string.unlock_err_invalid)
                        isDecrypting = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = password.isNotBlank() && !isDecrypting
        ) {
            if (showLoading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), color = Color.White)
            } else {
                Text(stringResource(R.string.unlock_btn_unlock))
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        TextButton(onClick = onSkip) {
            Text(if (isUpdate) stringResource(R.string.unlock_btn_skip_update) else stringResource(R.string.unlock_btn_skip_manual))
        }
        
        TextButton(onClick = onEnterManually) {
            Text(stringResource(R.string.title_settings))
        }
    }
}

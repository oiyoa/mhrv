package com.therealaleph.mhrv.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.therealaleph.mhrv.CaInstall
import com.therealaleph.mhrv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Certificate Installation Guide.
 * Manages the multi-step installation state and handles background checks.
 */
class CertInstallViewModel : ViewModel() {

    var currentStep by mutableStateOf(1)
        private set
    
    var isInstalled by mutableStateOf(false)
        private set
    
    var saveStatus by mutableStateOf<String?>(null)
        private set
    
    var saveError by mutableStateOf<String?>(null)
        private set
    
    var isSaving by mutableStateOf(false)
        private set

    var detectedFileName by mutableStateOf("mhrv-ca.crt")
        private set

    var manualCheckTrigger by mutableStateOf(0)
        private set

    /**
     * Initializes the state, checking if the cert is already installed or saved.
     */
    fun initialize(ctx: Context) {
        viewModelScope.launch {
            // Ensure the cert is exported to filesDir so we can read its fingerprint
            withContext(Dispatchers.IO) { CaInstall.export(ctx) }
            
            val fp = withContext(Dispatchers.IO) { CaInstall.fingerprint(ctx) }
            if (fp != null) {
                // 1. Check if already installed
                val installed = withContext(Dispatchers.IO) { CaInstall.isInstalled(fp) }
                if (installed) {
                    isInstalled = true
                    currentStep = 4
                    return@launch
                }

                // 2. Check if already saved to skip Step 1
                if (currentStep == 1) {
                    val result = withContext(Dispatchers.IO) {
                        CertStorageHelper.findExistingCertInDownloads(ctx, fp)
                    }
                    if (result != null) {
                        detectedFileName = result
                        saveStatus = ctx.getString(R.string.cert_guide_step1_done)
                        currentStep = 2
                    }
                }

                // 3. Start polling for installation success
                pollForInstallation(fp)
            }
        }
    }

    private suspend fun pollForInstallation(fp: ByteArray) {
        while (!isInstalled) {
            val installed = withContext(Dispatchers.IO) { CaInstall.isInstalled(fp) }
            if (installed) {
                isInstalled = true
                currentStep = 4
                break
            }
            delay(2000)
        }
    }

    fun handleSave(ctx: Context) {
        viewModelScope.launch {
            isSaving = true
            saveError = null
            val exported = withContext(Dispatchers.IO) { CaInstall.export(ctx) }
            if (exported) {
                val path = withContext(Dispatchers.IO) { CaInstall.saveToDownloads(ctx) }
                if (path != null) {
                    saveStatus = ctx.getString(R.string.cert_guide_step1_done)
                } else {
                    saveError = ctx.getString(R.string.cert_guide_save_error)
                }
            } else {
                saveError = ctx.getString(R.string.cert_guide_step1_error)
            }
            isSaving = false
        }
    }

    fun nextStep() {
        if (currentStep < 3) {
            currentStep++
        }
    }

    fun triggerManualCheck() {
        manualCheckTrigger++
    }
}

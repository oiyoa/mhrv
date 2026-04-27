package com.therealaleph.mhrv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HealthState {
    UNKNOWN,
    VERIFYING,
    HEALTHY,
    UNHEALTHY,
    NEEDS_CERTIFICATE
}

/**
 * Isolated state for VPN health to avoid modifying upstream VpnState.kt.
 */
object VpnHealthState {
    private val _healthState = MutableStateFlow(HealthState.UNKNOWN)
    val healthState: StateFlow<HealthState> = _healthState.asStateFlow()

    private val _verificationProgress = MutableStateFlow<String?>(null)
    val verificationProgress: StateFlow<String?> = _verificationProgress.asStateFlow()

    fun setHealthState(state: HealthState) {
        _healthState.value = state
        if (state != HealthState.VERIFYING) {
            _verificationProgress.value = null
        }
    }

    fun setVerificationProgress(progress: String?) {
        _verificationProgress.value = progress
    }
    
    fun reset() {
        _healthState.value = HealthState.UNKNOWN
        _verificationProgress.value = null
    }
}

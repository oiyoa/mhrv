package com.therealaleph.mhrv

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HealthState {
    UNKNOWN,
    VERIFYING,
    HEALTHY,
    UNHEALTHY
}

/**
 * Isolated state for VPN health to avoid modifying upstream VpnState.kt.
 */
object VpnHealthState {
    private val _healthState = MutableStateFlow(HealthState.UNKNOWN)
    val healthState: StateFlow<HealthState> = _healthState.asStateFlow()

    fun setHealthState(state: HealthState) {
        _healthState.value = state
    }
    
    fun reset() {
        _healthState.value = HealthState.UNKNOWN
    }
}

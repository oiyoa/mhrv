package com.therealaleph.mhrv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject

/**
 * Result state of a Google reachability check.
 */
sealed interface GoogleCheckResult {
    data object Idle : GoogleCheckResult
    data object Checking : GoogleCheckResult
    data class Connected(val latencyMs: Int) : GoogleCheckResult
    data object NotConnected : GoogleCheckResult
}

/**
 * One-time UI events from the reachability logic.
 */
sealed class GoogleReachabilityEvent {
    data class IpUpdated(val newIp: String) : GoogleReachabilityEvent()
}

/**
 * Singleton state for Google reachability. Following modern Android patterns
 * with StateFlow to ensure the check only happens once automatically and
 * persists across screen transitions.
 */
object GoogleReachabilityState {
    private const val MAX_RETRIES = 2
    private const val STAGGER_DELAY_MS = 50L

    private val _overallResult = MutableStateFlow<GoogleCheckResult>(GoogleCheckResult.Idle)
    val overallResult: StateFlow<GoogleCheckResult> = _overallResult.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _sniResults = MutableStateFlow<Map<String, GoogleCheckResult>>(emptyMap())
    val sniResults: StateFlow<Map<String, GoogleCheckResult>> = _sniResults.asStateFlow()

    private val _events = Channel<GoogleReachabilityEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var _hasAutoRun = false
    val hasAutoRun: Boolean get() = _hasAutoRun

    /**
     * Default list of SNIs to test.
     */
    val snisToTest = DEFAULT_SNI_POOL

    /**
     * Run the reachability check. 
     * @param force If true, ignore the [hasAutoRun] flag.
     */
    fun checkAll(
        cfg: MhrvConfig,
        scope: CoroutineScope,
        force: Boolean = false,
        onUpdate: (MhrvConfig) -> Unit = {}
    ) {
        if (_isChecking.value) return
        if (!force && _hasAutoRun) return

        _hasAutoRun = true
        _isChecking.value = true
        _overallResult.value = GoogleCheckResult.Checking
        
        val initialSniResults = snisToTest.associateWith { GoogleCheckResult.Checking }
        _sniResults.value = initialSniResults

        scope.launch {
            var currentCfg = cfg
            
            // 1. Initial Test with existing IP (with retries)
            var result = if (currentCfg.googleIp.isNotBlank()) {
                performTestWithRetries(currentCfg.googleIp, currentCfg.frontDomain)
            } else GoogleCheckResult.NotConnected

            // 2. Auto-detect/Resolve if failed or no IP
            if (result !is GoogleCheckResult.Connected) {
                val fresh = withContext(Dispatchers.IO) {
                    runCatching { NetworkDetect.resolveGoogleIp() }.getOrNull()
                }
                if (!fresh.isNullOrBlank() && fresh != currentCfg.googleIp) {
                    currentCfg = currentCfg.copy(googleIp = fresh)
                    onUpdate(currentCfg)
                    _events.send(GoogleReachabilityEvent.IpUpdated(fresh))

                    // 3. Retest with new IP (with retries)
                    result = performTestWithRetries(currentCfg.googleIp, currentCfg.frontDomain)
                }
            }

            _overallResult.value = result

            // 4. Test SNI pool in parallel with staggers and retries
            val sniResultsMap = withContext(Dispatchers.IO) {
                snisToTest.mapIndexed { index, sni ->
                    async {
                        // Stagger the start of each check
                        delay(index * STAGGER_DELAY_MS)
                        
                        val sniResult = performTestWithRetries(currentCfg.googleIp, sni)
                        
                        // Update incrementally for UI feedback
                        withContext(Dispatchers.Main) {
                            val currentResults = _sniResults.value.toMutableMap()
                            currentResults[sni] = sniResult
                            _sniResults.value = currentResults
                        }
                        
                        sni to sniResult
                    }
                }.awaitAll().toMap()
            }

            // 5. Auto-adjust SNI pool based on results
            val updatedCfg = adjustSniHosts(currentCfg, sniResultsMap)
            if (updatedCfg !== currentCfg) {
                onUpdate(updatedCfg)
            }
            
            _isChecking.value = false
        }
    }

    /**
     * Performs an SNI test with retries.
     */
    private suspend fun performTestWithRetries(ip: String, sni: String): GoogleCheckResult {
        var lastResult: GoogleCheckResult = GoogleCheckResult.NotConnected
        
        for (attempt in 1..MAX_RETRIES) {
            val json = withContext(Dispatchers.IO) {
                runCatching { Native.testSni(ip, sni) }.getOrNull()
            }
            
            if (json != null) {
                val obj = try { JSONObject(json) } catch (_: Exception) { null }
                if (obj?.optBoolean("ok") == true) {
                    return GoogleCheckResult.Connected(obj.optInt("latencyMs", -1))
                }
            }
            
            // If we are here, it failed. 
            lastResult = GoogleCheckResult.NotConnected
            
            // Small delay between retries if not the last attempt
            if (attempt < MAX_RETRIES) {
                delay(500)
            }
        }
        
        return lastResult
    }

    /**
     * Adjusts the SNI hosts in the config based on reachability results.
     * Ensures failing SNIs are removed and passing ones are restored.
     */
    private fun adjustSniHosts(cfg: MhrvConfig, results: Map<String, GoogleCheckResult>): MhrvConfig {
        val toDisable = results.filter { it.value is GoogleCheckResult.NotConnected }.keys
        val toEnable = results.filter { it.value is GoogleCheckResult.Connected }.keys

        val nextSniHosts = if (cfg.sniHosts.isEmpty()) {
            DEFAULT_SNI_POOL.toMutableList()
        } else {
            cfg.sniHosts.toMutableList()
        }

        var changed = false
        toDisable.forEach { if (nextSniHosts.remove(it)) changed = true }
        toEnable.forEach { if (!nextSniHosts.contains(it)) { nextSniHosts.add(it); changed = true } }

        if (!changed) return cfg

        // If it now matches the full default pool, we can set it back to empty 
        // to let the Rust side handle auto-expansion (cleaner config).
        val isDefaultPool = nextSniHosts.size == DEFAULT_SNI_POOL.size && 
                           nextSniHosts.containsAll(DEFAULT_SNI_POOL)
        val finalHosts = if (isDefaultPool) emptyList() else nextSniHosts.distinct().sorted()
        
        return cfg.copy(sniHosts = finalHosts)
    }
}

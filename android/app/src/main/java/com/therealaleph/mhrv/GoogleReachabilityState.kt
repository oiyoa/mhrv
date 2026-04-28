package com.therealaleph.mhrv

import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * Singleton state for Google reachability. Following modern Android patterns
 * with StateFlow to ensure the check only happens once automatically and
 * persists across screen transitions.
 */
object GoogleReachabilityState {
    private val _overallResult = MutableStateFlow<GoogleCheckResult>(GoogleCheckResult.Idle)
    val overallResult: StateFlow<GoogleCheckResult> = _overallResult.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private val _sniResults = MutableStateFlow<Map<String, GoogleCheckResult>>(emptyMap())
    val sniResults: StateFlow<Map<String, GoogleCheckResult>> = _sniResults.asStateFlow()

    private var _hasAutoRun = false
    val hasAutoRun: Boolean get() = _hasAutoRun

    /**
     * Default list of SNIs to test.
     */
    val snisToTest = DEFAULT_SNI_POOL.take(5)

    /**
     * Run the reachability check. 
     * @param force If true, ignore the [hasAutoRun] flag.
     */
    fun checkAll(
        ctx: Context,
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
            var connected = false
            var latency = -1

            // 1. Initial Test with existing IP
            if (currentCfg.googleIp.isNotBlank()) {
                val json = withContext(Dispatchers.IO) {
                    runCatching { Native.testSni(currentCfg.googleIp, currentCfg.frontDomain) }.getOrNull()
                }
                if (json != null) {
                    val obj = try { JSONObject(json) } catch (_: Exception) { null }
                    if (obj?.optBoolean("ok") == true) {
                        connected = true
                        latency = obj.optInt("latencyMs", -1)
                    }
                }
            }

            // 2. Auto-detect/Resolve if failed or no IP
            if (!connected) {
                val fresh = withContext(Dispatchers.IO) {
                    NetworkDetect.resolveGoogleIp()
                }
                if (!fresh.isNullOrBlank() && fresh != currentCfg.googleIp) {
                    currentCfg = currentCfg.copy(googleIp = fresh)
                    onUpdate(currentCfg)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(ctx, "IP updated to $fresh", Toast.LENGTH_SHORT).show()
                    }

                    // 3. Retest with new IP
                    val json2 = withContext(Dispatchers.IO) {
                        runCatching { Native.testSni(currentCfg.googleIp, currentCfg.frontDomain) }.getOrNull()
                    }
                    if (json2 != null) {
                        val obj = try { JSONObject(json2) } catch (_: Exception) { null }
                        if (obj?.optBoolean("ok") == true) {
                            connected = true
                            latency = obj.optInt("latencyMs", -1)
                        }
                    }
                }
            }

            _overallResult.value = if (connected) GoogleCheckResult.Connected(latency) else GoogleCheckResult.NotConnected

            // 4. Test SNI pool in parallel
            snisToTest.forEach { sni ->
                scope.launch {
                    val resJson = withContext(Dispatchers.IO) {
                        runCatching { Native.testSni(currentCfg.googleIp, sni) }.getOrNull()
                    }
                    var sniConnected = false
                    var sniLatency = -1
                    if (resJson != null) {
                        val obj = try { JSONObject(resJson) } catch (_: Exception) { null }
                        if (obj?.optBoolean("ok") == true) {
                            sniConnected = true
                            sniLatency = obj.optInt("latencyMs", -1)
                        }
                    }
                    
                    // Update the specific SNI result in the map
                    synchronized(this@GoogleReachabilityState) {
                        val currentResults = _sniResults.value.toMutableMap()
                        currentResults[sni] = if (sniConnected) GoogleCheckResult.Connected(sniLatency) else GoogleCheckResult.NotConnected
                        _sniResults.value = currentResults
                    }
                }
            }
            
            _isChecking.value = false
        }
    }
}

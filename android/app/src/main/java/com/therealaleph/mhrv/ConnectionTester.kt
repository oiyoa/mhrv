package com.therealaleph.mhrv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Probes whether the VPN connection actually provides internet access.
 * We test via two endpoints:
 * 1. `http://gstatic.com/generate_204`
 * 2. `https://api.ipify.org`
 * 
 * Since the VPN takes over the device routing (via the TUN interface),
 * standard HTTP requests from Kotlin will flow through the tunnel. This
 * provides a true end-to-end check. We use a retry mechanism to tolerate
 * the brief blackout window during VPN establishment.
 */
object ConnectionTester {
    suspend fun verifyConnection(
        mode: Mode = Mode.FULL,
        proxyPort: Int? = null,
        timeoutMs: Int = 20000,
        retries: Int = 5,
        onProgress: ((Int, Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        // For DIRECT mode, we don't perform a connection test
        if (mode == Mode.DIRECT) {
            return@withContext true
        }

        val endpoints = listOf(
            "https://api.ipify.org",
            "http://gstatic.com/generate_204"
        )
        
        for (i in 0 until retries) {
            if (i > 0) {
                onProgress?.invoke(i + 1, retries)
            }
            var anySuccess = false
            for (endpoint in endpoints) {
                try {
                    val url = URL(endpoint)
                    val conn = if (proxyPort != null) {
                        url.openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort)))
                    } else {
                        url.openConnection()
                    } as HttpURLConnection
                    conn.connectTimeout = timeoutMs
                    conn.readTimeout = timeoutMs
                    conn.instanceFollowRedirects = false
                    
                    val code = conn.responseCode
                    if (code in 200..399) {
                        anySuccess = true
                        break
                    }
                } catch (e: Exception) {
                    // Ignore and try the next endpoint
                }
            }
            if (anySuccess) {
                return@withContext true
            }
            if (i < retries - 1) {
                delay(2000L * (i + 1)) // Exponential-ish backoff
            }
        }
        false
    }
}

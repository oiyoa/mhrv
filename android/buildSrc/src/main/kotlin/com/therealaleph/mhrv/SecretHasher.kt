package com.therealaleph.mhrv

import java.security.MessageDigest

object SecretHasher {
    /**
     * Calculates a stable SHA-256 hash of the core secret information.
     * This logic is shared between the build process and the app.
     */
    fun calculateHash(scriptIds: List<String>, authKey: String): String {
        val scriptIdsJson = scriptIds.joinToString(",") { "\"$it\"" }
        val secretsJson = "{\"script_ids\":[$scriptIdsJson],\"auth_key\":\"$authKey\"}"
        
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(secretsJson.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}

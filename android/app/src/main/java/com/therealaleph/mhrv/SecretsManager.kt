package com.therealaleph.mhrv

import android.content.Context
import android.util.Base64
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val PREFS_NAME = "secrets"

object SecretsManager {
    private const val KEY_SECRETS = "decrypted_secrets"
    private const val KEY_HASH = "secrets_hash"
    private const val KEY_SKIPPED_HASH = "skipped_secrets_hash"

    data class DecryptedSecrets(val scriptIds: List<String>, val authKey: String)

    fun hasEmbeddedSecrets(): Boolean = BuildConfig.ENCRYPTED_SECRETS.isNotEmpty()

    /**
     * Decrypts the build-time secrets using the provided password.
     * Uses PBKDF2-HMAC-SHA256 (600k iterations) + AES-256-GCM.
     */
    fun decryptBuildSecrets(password: String): DecryptedSecrets? {
        val blob = try {
            Base64.decode(BuildConfig.ENCRYPTED_SECRETS, Base64.DEFAULT)
        } catch (e: Exception) {
            return null
        }

        if (blob.size < 16 + 12 + 16) return null // salt(16) + iv(12) + min_ciphertext(16)

        val salt = blob.sliceArray(0 until 16)
        val iv = blob.sliceArray(16 until 28)
        val ciphertext = blob.sliceArray(28 until blob.size)

        return try {
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            val spec = PBEKeySpec(password.toCharArray(), salt, 600000, 256)
            val tmp = factory.generateSecret(spec)
            val secret = SecretKeySpec(tmp.encoded, "AES")

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext)
            
            val json = JSONObject(String(plaintext, Charsets.UTF_8))
            val ids = json.getJSONArray("script_ids")
            val scriptIds = mutableListOf<String>()
            for (i in 0 until ids.length()) {
                scriptIds.add(ids.getString(i))
            }
            DecryptedSecrets(scriptIds, json.getString("auth_key"))
        } catch (e: Exception) {
            null
        }
    }

    // --- Runtime Secure Storage (DataStore + Tink) ---

    private var aead: Aead? = null

    private fun getAead(context: Context): Aead {
        if (aead == null) {
            AeadConfig.register()
            aead = AndroidKeysetManager.Builder()
                .withSharedPref(context, "tink_keyset", "master_key_pref")
                .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri("android-keystore://master_key")
                .build()
                .keysetHandle
                .getPrimitive(Aead::class.java)
        }
        return aead!!
    }

    fun getUnlockedSecrets(context: Context): DecryptedSecrets? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encrypted = prefs.getString(KEY_SECRETS, null) ?: return null
        return try {
            val decrypted = getAead(context).decrypt(Base64.decode(encrypted, Base64.DEFAULT), null)
            val json = JSONObject(String(decrypted, Charsets.UTF_8))
            val ids = json.getJSONArray("script_ids")
            val scriptIds = mutableListOf<String>()
            for (i in 0 until ids.length()) {
                scriptIds.add(ids.getString(i))
            }
            DecryptedSecrets(scriptIds, json.getString("auth_key"))
        } catch (e: Exception) {
            null
        }
    }

    fun storeUnlockedSecrets(context: Context, secrets: DecryptedSecrets) {
        val json = JSONObject().apply {
            put("script_ids", org.json.JSONArray(secrets.scriptIds))
            put("auth_key", secrets.authKey)
        }
        val encrypted = getAead(context).encrypt(json.toString().toByteArray(Charsets.UTF_8), null)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SECRETS, Base64.encodeToString(encrypted, Base64.DEFAULT))
            .putString(KEY_HASH, BuildConfig.SECRETS_HASH)
            .remove(KEY_SKIPPED_HASH) // Clear skip state if we successfully updated
            .apply()
    }

    fun skipUpdate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SKIPPED_HASH, BuildConfig.SECRETS_HASH).apply()
    }

    fun isUpdateAvailable(context: Context): Boolean {
        if (!hasEmbeddedSecrets()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedHash = prefs.getString(KEY_HASH, null)
        val skippedHash = prefs.getString(KEY_SKIPPED_HASH, null)
        
        // If we haven't stored anything yet, it's not exactly an "update" but initial setup.
        // If the current build hash matches what we already have, no update.
        if (storedHash == BuildConfig.SECRETS_HASH) return false
        
        // If the user already skipped this specific version, don't nag them again.
        if (skippedHash == BuildConfig.SECRETS_HASH) return false
        
        return true
    }

    fun clearUnlockedSecrets(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_SECRETS).apply()
    }

    /**
     * Applies unlocked embedded secrets to the config if available and if the 
     * config is currently empty (not manually overridden).
     */
    fun hasUnlockedSecrets(context: Context): Boolean {
        return getUnlockedSecrets(context) != null
    }

    /**
     * Applies unlocked embedded secrets to the config if available and if the 
     * config is currently empty (not manually overridden).
     */
    fun applySecrets(context: Context, cfg: MhrvConfig): MhrvConfig {
        val unlocked = getUnlockedSecrets(context)
        if (unlocked != null && cfg.appsScriptUrls.isEmpty() && cfg.authKey.isEmpty()) {
            return forceApplySecrets(context, cfg)
        }
        return cfg
    }

    /**
     * Overwrites the provided config with the current unlocked secrets.
     */
    fun forceApplySecrets(context: Context, cfg: MhrvConfig): MhrvConfig {
        val unlocked = getUnlockedSecrets(context) ?: return cfg
        return cfg.copy(
            appsScriptUrls = unlocked.scriptIds.map { "https://script.google.com/macros/s/$it/exec" },
            authKey = unlocked.authKey
        )
    }
}

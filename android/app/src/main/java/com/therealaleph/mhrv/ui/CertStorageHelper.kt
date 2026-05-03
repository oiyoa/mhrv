package com.therealaleph.mhrv.ui

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.security.MessageDigest
import android.util.Base64
import android.os.Environment

/**
 * Low-level utilities for interacting with certificate files in external storage.
 * Separated from UI logic to keep the components clean.
 */
object CertStorageHelper {

    private const val CERT_NAME = "mhrv-ca.crt"

    /**
     * Checks if the certificate file exists in the Downloads folder and returns its filename if verified.
     */
    fun findExistingCertInDownloads(ctx: Context, expectedFp: ByteArray): String? {
        return try {
            // Check Public MediaStore (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val found = checkMediaStore(ctx, expectedFp)
                if (found != null) return found
            }

            // Fallback to App-Private Downloads (Legacy & API 29+)
            checkFileSystem(ctx, expectedFp)
        } catch (_: Throwable) {
            null
        }
    }

    private fun checkMediaStore(ctx: Context, expectedFp: ByteArray): String? {
        val resolver = ctx.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME)
        // Fuzzy match to handle "mhrv-ca (1).crt" etc.
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("mhrv-ca%")
        
        return resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME))
                if (!name.endsWith(".crt")) continue
                
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                val fileUri = android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    id
                )
                try {
                    val bytes = resolver.openInputStream(fileUri)?.use { it.readBytes() }
                    if (bytes != null && stripPem(bytes).let { MessageDigest.getInstance("SHA-256").digest(it).contentEquals(expectedFp) }) {
                        return name
                    }
                } catch (_: Throwable) {}
            }
            null
        } ?: null
    }

    private fun checkFileSystem(ctx: Context, expectedFp: ByteArray): String? {
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val files = dir.listFiles { _, name -> name.startsWith("mhrv-ca") && name.endsWith(".crt") } ?: return null
        
        for (file in files) {
            try {
                val bytes = file.readBytes()
                if (stripPem(bytes).let { MessageDigest.getInstance("SHA-256").digest(it).contentEquals(expectedFp) }) {
                    return file.name
                }
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun stripPem(bytes: ByteArray): ByteArray {
        val s = bytes.toString(Charsets.US_ASCII)
        if (!s.contains("BEGIN CERTIFICATE")) return bytes
        val body = s
            .substringAfter("-----BEGIN CERTIFICATE-----", "")
            .substringBefore("-----END CERTIFICATE-----", "")
            .replace(Regex("\\s+"), "")
        return Base64.decode(body, Base64.DEFAULT)
    }
}

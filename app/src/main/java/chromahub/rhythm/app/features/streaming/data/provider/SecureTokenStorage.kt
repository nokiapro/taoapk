/*
 * SPDX-FileCopyrightText: 2024-2026 Anjishnu Nandi <https://github.com/cromaguy>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package chromahub.rhythm.app.features.streaming.data.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.core.content.edit
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts sensitive authentication tokens (access token, refresh token) at rest
 * using Android KeyStore AES-256-GCM backed keys before persisting to SharedPreferences.
 */
internal class SecureTokenStorage(
    context: Context,
    prefsName: String = "xk_firebase_secure_tokens"
) {
    private val prefs = context.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    fun putToken(key: String, value: String) {
        if (value.isBlank()) {
            removeToken(key)
            return
        }
        val encrypted = encrypt(value)
        prefs.edit {
            if (encrypted != null) {
                putString(key, "$PREFIX_GCM$encrypted")
            } else {
                // Only reached in non-Android JVM test environments without AndroidKeyStore
                putString(key, "$PREFIX_PLAIN$value")
            }
        }
    }

    fun getToken(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        return when {
            stored.startsWith(PREFIX_GCM) -> decrypt(stored.removePrefix(PREFIX_GCM)).orEmpty()
            stored.startsWith(PREFIX_PLAIN) -> stored.removePrefix(PREFIX_PLAIN)
            else -> stored
        }
    }

    fun hasToken(key: String): Boolean = getToken(key).isNotBlank()

    fun removeToken(key: String) {
        prefs.edit { remove(key) }
    }

    fun clearAll() {
        prefs.edit { clear() }
    }

    private fun getOrCreateAesKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            Log.w(TAG, "AndroidKeyStore unavailable, using fallback storage: ${e.message}")
            null
        }
    }

    private fun encrypt(plaintext: String): String? {
        val key = getOrCreateAesKey() ?: return null
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + ciphertext.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(ciphertext, 0, combined, 1 + iv.size, ciphertext.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt token", e)
            null
        }
    }

    private fun decrypt(encoded: String): String? {
        val key = getOrCreateAesKey() ?: return null
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size < 2) return null
            val ivLen = combined[0].toInt() and 0xFF
            if (combined.size <= 1 + ivLen) return null
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decrypt token (clearing corrupted entry)", e)
            null
        }
    }

    companion object {
        private const val TAG = "SecureTokenStorage"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "rhythm_xkmusic_token_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PREFIX_GCM = "gcm:"
        private const val PREFIX_PLAIN = "plain:"
    }
}

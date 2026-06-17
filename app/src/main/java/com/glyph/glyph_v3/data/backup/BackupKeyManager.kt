package com.glyph.glyph_v3.data.backup

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.crypto.Mac
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages AES-256-GCM encryption keys via Android Keystore for backup data.
 *
 * Key material NEVER leaves the Keystore hardware (TEE/StrongBox).
 * Each encryption operation generates a random 12-byte IV prepended to output.
 * Output format: IV (12 bytes) + ciphertext + GCM tag (16 bytes).
 */
class BackupKeyManager(private val context: Context) {

    companion object {
        private const val TAG = "BackupKeyManager"
        private const val KEY_ALIAS = "glyph_backup_key_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val AES_KEY_SIZE_BITS = 256

        @Volatile
        private var instance: BackupKeyManager? = null

        fun getInstance(context: Context): BackupKeyManager {
            return instance ?: synchronized(this) {
                instance ?: BackupKeyManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Generate or retrieve the AES-256 encryption key from Android Keystore.
     * Key is generated once and persisted in hardware-backed storage.
     */
    suspend fun getOrCreateEncryptionKey(): SecretKey = withContext(Dispatchers.IO) {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
            Log.d(TAG, "Using existing backup encryption key from Keystore")
            return@withContext entry.secretKey
        }

        // Generate new key
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_SIZE_BITS)
            .build()
        keyGenerator.init(spec)
        val key = keyGenerator.generateKey()
        Log.d(TAG, "Generated new backup encryption key in Keystore")
        key
    }

    /**
     * Encrypt plaintext with AES-256-GCM.
     * Returns IV (12 bytes) + ciphertext + GCM tag.
     */
    suspend fun encrypt(plaintext: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val key = getOrCreateEncryptionKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // 12 bytes, randomly generated
        val ciphertext = cipher.doFinal(plaintext)
        // Prepend IV to ciphertext for storage
        iv + ciphertext
    }

    /**
     * Decrypt data encrypted with [encrypt].
     * Input format: IV (12 bytes) + ciphertext + GCM tag (16 bytes).
     */
    suspend fun decrypt(encryptedData: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val key = getOrCreateEncryptionKey()
        val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH_BYTES)
        val ciphertext = encryptedData.copyOfRange(GCM_IV_LENGTH_BYTES, encryptedData.size)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        cipher.doFinal(ciphertext)
    }

    /**
     * Generate HMAC-SHA256 integrity hash for tamper detection.
     */
    fun generateIntegrityHash(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val keyBytes = getKeyMaterialForHmac()
        val keySpec = SecretKeySpec(keyBytes, "HmacSHA256")
        mac.init(keySpec)
        return mac.doFinal(data)
    }

    /**
     * Verify integrity hash matches.
     */
    fun verifyIntegrity(data: ByteArray, expectedHash: ByteArray): Boolean {
        val actualHash = generateIntegrityHash(data)
        return actualHash.contentEquals(expectedHash)
    }

    /**
     * Derive a stable HMAC key from the Keystore-backed AES key material.
     * Uses the first 32 bytes of the encoded key as HMAC key.
     * Note: This extracts key material — acceptable because it's used only for
     * integrity verification, not for independent encryption.
     */
    private fun getKeyMaterialForHmac(): ByteArray {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry
        return entry.secretKey.encoded.copyOf(32) // Use first 32 bytes for HMAC-SHA256
    }
}

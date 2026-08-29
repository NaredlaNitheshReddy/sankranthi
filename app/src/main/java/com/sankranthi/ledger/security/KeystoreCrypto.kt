package com.sankranthi.ledger.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM encryption with a key held in the Android Keystore.
 *
 * The key material never enters the app's address space — it lives in the
 * platform keystore (hardware-backed on most devices) and only the *operation*
 * crosses the boundary. So the ciphertext we persist cannot be decrypted by
 * copying the file to another device, which is the threat that matters for a
 * bearer token sitting in app storage.
 *
 * ### What this does and does not protect against
 *
 * Protects: someone pulling the file off the device — a cloud backup, an `adb`
 * dump, filesystem access from another OS, or a stolen unlocked-bootloader phone.
 * The ciphertext is inert without the device's keystore.
 *
 * Does **not** protect: a compromised copy of this app, or a rooted device where
 * an attacker can ask the keystore to decrypt on their behalf. That is inherent —
 * the app must be able to decrypt unattended for background sync to work at all,
 * so any protection requiring the user to be present would break the feature it
 * exists to support.
 *
 * `setUserAuthenticationRequired` is therefore deliberately **not** set: with it,
 * a `SyncWorker` firing while the phone was locked could not read the token, and
 * a record created offline would sit unsynced until the user next unlocked —
 * exactly the failure §20 forbids. `setUnlockedDeviceRequired` is likewise left
 * off for the same reason.
 */
object KeystoreCrypto {

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    /**
     * Raised when stored bytes cannot be decrypted: a corrupted blob, a key that
     * no longer exists, or a GCM tag that fails to verify. Callers should treat it
     * as "there is no credential" and re-authenticate, never as fatal.
     */
    class UndecryptableException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /** Encrypts [plaintext], returning `iv || ciphertext+tag`. */
    fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // No IV is supplied on purpose. AES-GCM keys are generated with
        // randomized encryption required, so the provider must choose the IV;
        // reusing one under the same key would be catastrophic for GCM.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(alias))
        val iv = cipher.iv
        require(iv.size == GCM_IV_BYTES) { "Unexpected GCM IV length: ${iv.size}" }
        return iv + cipher.doFinal(plaintext)
    }

    /** Reverses [encrypt]. Throws [UndecryptableException] rather than leaking crypto types. */
    fun decrypt(alias: String, blob: ByteArray): ByteArray {
        if (blob.size <= GCM_IV_BYTES) {
            throw UndecryptableException("Stored value is too short to be valid")
        }
        return try {
            val key = existingKey(alias)
                ?: throw UndecryptableException("Encryption key is gone; credential unrecoverable")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES),
            )
            cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
        } catch (e: UndecryptableException) {
            throw e
        } catch (e: Exception) {
            // Covers a failed GCM tag (tampering or truncation), a key the
            // platform invalidated, and provider-specific failures alike. The
            // distinction does not change what the caller can do about it.
            throw UndecryptableException("Stored credential could not be decrypted", e)
        }
    }

    /** Drops the key, making every existing ciphertext permanently unreadable. */
    fun deleteKey(alias: String) {
        runCatching { keyStore().deleteEntry(alias) }
    }

    fun hasKey(alias: String): Boolean = existingKey(alias) != null

    private fun keyStore(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun existingKey(alias: String): SecretKey? =
        runCatching { keyStore().getKey(alias, null) as? SecretKey }.getOrNull()

    private fun getOrCreateKey(alias: String): SecretKey {
        existingKey(alias)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

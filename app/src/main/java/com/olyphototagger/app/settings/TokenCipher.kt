package com.olyphototagger.app.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class EncryptedValue(val ciphertext: String, val iv: String)

/**
 * Encrypts small secrets (the Dawarich API token) at rest with an AES-256/GCM key held in
 * the Android Keystore — the key material never leaves secure hardware/TEE where the device
 * supports it, and this class never sees the raw key bytes. Uses the platform Keystore APIs
 * directly rather than androidx.security:security-crypto, which has stayed in beta for
 * years; this is a well-trodden ~50 line pattern rather than an extra unstable dependency.
 */
class TokenCipher {

    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private val secretKey: SecretKey
        get() = (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: generateKey()

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plaintext: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, secretKey)
        }
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return EncryptedValue(
            ciphertext = Base64.encodeToString(ciphertext, Base64.NO_WRAP),
            // A fresh random IV is generated per encrypt() call (Cipher's default GCM
            // behavior when init'd without an explicit IvParameterSpec) — reusing an IV
            // with the same key breaks GCM's confidentiality guarantee, so this must never
            // be hardcoded or cached across calls.
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        )
    }

    /** Returns null if decryption fails — e.g. the Keystore key was invalidated by an OS
     *  event or the app's data was partially cleared. The caller re-prompts for the token
     *  rather than crashing; it's a credential the user can re-enter, not lost data. */
    fun decrypt(value: EncryptedValue): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(
                Cipher.DECRYPT_MODE,
                secretKey,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(value.iv, Base64.NO_WRAP))
            )
        }
        String(cipher.doFinal(Base64.decode(value.ciphertext, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: GeneralSecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "oly_photo_tagger_settings_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
    }
}

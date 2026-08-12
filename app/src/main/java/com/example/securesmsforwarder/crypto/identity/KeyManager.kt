package com.example.securesmsforwarder.crypto.identity

import android.content.Context
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import java.security.GeneralSecurityException

/**
 * Manages the long-term cryptographic identity of the device.
 * Uses Google Tink to generate and manage X25519 keys, backed by Android Keystore.
 */
class KeyManager(private val context: Context) {

    companion object {
        private const val KEYSET_NAME = "device_identity_keyset"
        private const val PREF_FILE_NAME = "secure_sms_crypto_prefs"
        private const val MASTER_KEY_URI = "android-keystore://secure_sms_master_key"
        private const val TAG = "KeyManager"
        
        /**
         * Computes the 12 hex char fingerprint from a serialized public key.
         */
        fun computeFingerprint(serializedKey: String): String {
            val hashBytes = java.security.MessageDigest.getInstance("SHA-256").digest(serializedKey.trim().toByteArray(Charsets.UTF_8))
            return hashBytes.take(6).joinToString("") { "%02X".format(it) }
        }
    }

    init {
        try {
            // Initialize Tink configurations for Hybrid Encryption (X25519) and AEAD
            HybridConfig.register()
            AeadConfig.register()
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Failed to initialize Tink", e)
        }
    }

    /**
     * Retrieves the existing device identity keyset, or generates a new one if it doesn't exist.
     * The private key material is encrypted at rest using a master key from the Android Keystore.
     */
    fun getOrGenerateIdentityKeyset(): KeysetHandle {
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
    }

    /**
     * Returns the public keyset handle, which can be safely shared with the other device
     * (e.g., via QR code) during the pairing process.
     */
    fun getPublicIdentityKeyset(): KeysetHandle {
        val privateKeyset = getOrGenerateIdentityKeyset()
        return privateKeyset.publicKeysetHandle
    }

    /**
     * Serializes the public keyset to a JSON string for QR code generation.
     */
    fun getSerializedPublicKey(): String {
        val publicKeyset = getPublicIdentityKeyset()
        val stream = java.io.ByteArrayOutputStream()
        CleartextKeysetHandle.write(publicKeyset, com.google.crypto.tink.JsonKeysetWriter.withOutputStream(stream))
        return stream.toString("UTF-8")
    }

    /**
     * Generates a short visual fingerprint (hash) of the public key for manual verification (12 hex chars).
     */
    fun getPublicKeyFingerprint(): String {
        return computeFingerprint(getSerializedPublicKey())
    }

    /**
     * Formats the 12-char fingerprint into a human-readable format: A1B7 • 92C4 • 816E
     */
    fun getHumanFriendlyFingerprint(): String {
        val raw = getPublicKeyFingerprint()
        return raw.chunked(4).joinToString(" • ")
    }
}

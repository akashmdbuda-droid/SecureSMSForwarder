package com.example.securesmsforwarder.crypto.storage

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * Abstraction for encrypting and decrypting local Room message payloads at rest.
 */
interface StorageCipher {
    fun encrypt(plaintext: String): ByteArray
    fun decrypt(payload: ByteArray?): String
}

/**
 * Tink AEAD-backed [StorageCipher] with backward-compatible decoding for pre-v1.1.0 plaintext rows.
 */
class AeadStorageCipher(private val aead: Aead) : StorageCipher {

    companion object {
        val HEADER_PREFIX: ByteArray = "AEADv1:".toByteArray(Charsets.UTF_8)
        val ASSOCIATED_DATA: ByteArray = "RoomMessage_V1".toByteArray(Charsets.UTF_8)
    }

    override fun encrypt(plaintext: String): ByteArray {
        val cipherBytes = aead.encrypt(plaintext.toByteArray(Charsets.UTF_8), ASSOCIATED_DATA)
        return HEADER_PREFIX + cipherBytes
    }

    override fun decrypt(payload: ByteArray?): String {
        if (payload == null || payload.isEmpty()) {
            return ""
        }
        return if (payload.size > HEADER_PREFIX.size &&
            payload.copyOfRange(0, HEADER_PREFIX.size).contentEquals(HEADER_PREFIX)
        ) {
            val cipherBytes = payload.copyOfRange(HEADER_PREFIX.size, payload.size)
            String(aead.decrypt(cipherBytes, ASSOCIATED_DATA), Charsets.UTF_8)
        } else {
            // Legacy pre-v1.1.0 plaintext row
            String(payload, Charsets.UTF_8)
        }
    }
}

/**
 * Production Android Keystore-backed [StorageCipher] using Tink AES256-GCM.
 * Uses a distinct keyset name within the existing crypto preferences file so
 * the existing device identity keyset (`device_identity_keyset`) remains untouched.
 */
class LocalStorageCipher(context: Context) : StorageCipher {

    private val appContext: Context = context.applicationContext ?: context

    private val delegate: AeadStorageCipher by lazy {
        AeadConfig.register()
        val aead = AndroidKeysetManager.Builder()
            .withSharedPref(appContext, KEYSET_NAME, PREF_FILE_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
        AeadStorageCipher(aead)
    }

    override fun encrypt(plaintext: String): ByteArray = delegate.encrypt(plaintext)

    override fun decrypt(payload: ByteArray?): String = delegate.decrypt(payload)

    companion object {
        const val KEYSET_NAME = "local_storage_aead_keyset"
        const val PREF_FILE_NAME = "secure_sms_crypto_prefs"
        const val MASTER_KEY_URI = "android-keystore://secure_sms_master_key"
    }
}

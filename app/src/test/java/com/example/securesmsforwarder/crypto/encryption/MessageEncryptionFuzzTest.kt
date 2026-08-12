package com.example.securesmsforwarder.crypto.encryption

import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HybridConfig
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.security.GeneralSecurityException
import kotlin.random.Random

class MessageEncryptionFuzzTest {

    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() {
            HybridConfig.register()
            AeadConfig.register()
        }
    }

    @Test
    fun testEncryption_fuzzingPayloads() {
        val encryption = MessageEncryption()

        // Generate a test identity keyset
        val recipientPrivateKey = KeysetHandle.generateNew(
            KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305")
        )
        val recipientPublicKey = recipientPrivateKey.publicKeysetHandle

        // Fuzz test 100 times with random payloads
        for (i in 0..100) {
            val randomPayloadSize = Random.nextInt(1, 10000)
            val randomPayload = ByteArray(randomPayloadSize)
            Random.nextBytes(randomPayload)

            val ciphertext = encryption.encrypt(randomPayload, recipientPublicKey)
            assertNotNull(ciphertext)
            assertNotEquals(randomPayload, ciphertext) // Should be encrypted

            val decrypted = encryption.decrypt(ciphertext, recipientPrivateKey)
            assertArrayEquals(randomPayload, decrypted) // Must match exactly
        }
    }

    @Test
    fun testEncryption_corruptedCiphertext_throwsException() {
        val encryption = MessageEncryption()
        val recipientPrivateKey = KeysetHandle.generateNew(
            KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305")
        )
        val recipientPublicKey = recipientPrivateKey.publicKeysetHandle

        val payload = "Test Message".toByteArray()
        val ciphertext = encryption.encrypt(payload, recipientPublicKey)

        // Corrupt the ciphertext by changing a byte
        ciphertext[ciphertext.size / 2] = (ciphertext[ciphertext.size / 2] + 1).toByte()

        try {
            encryption.decrypt(ciphertext, recipientPrivateKey)
            fail("Expected GeneralSecurityException for corrupted ciphertext")
        } catch (e: GeneralSecurityException) {
            // Expected
        }
    }
}

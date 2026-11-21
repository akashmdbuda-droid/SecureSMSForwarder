package com.example.securesmsforwarder.crypto.storage

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.security.GeneralSecurityException

class LocalStorageCipherTest {

    private lateinit var cipher: AeadStorageCipher

    companion object {
        @JvmStatic
        @BeforeClass
        fun registerTink() {
            AeadConfig.register()
        }
    }

    @Before
    fun setUp() {
        val aead = KeysetHandle
            .generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(Aead::class.java)
        cipher = AeadStorageCipher(aead)
    }

    @Test
    fun testRoundTrip_normalEmptyAndUnicodePayloads() {
        val samples = listOf(
            "Your OTP is 482910. Do not share this code.",
            "",
            "Hello 🌍🔐 — नमस्ते — こんにちは — café"
        )

        for (plaintext in samples) {
            val encrypted = cipher.encrypt(plaintext)
            assertTrue(
                "Encrypted payload should be longer than header prefix",
                encrypted.size > AeadStorageCipher.HEADER_PREFIX.size
            )
            assertTrue(
                "Encrypted payload should start with AEADv1: header prefix",
                encrypted.copyOfRange(0, AeadStorageCipher.HEADER_PREFIX.size)
                    .contentEquals(AeadStorageCipher.HEADER_PREFIX)
            )
            if (plaintext.isNotEmpty()) {
                val plainBytes = plaintext.toByteArray(Charsets.UTF_8)
                assertFalse(
                    "Encrypted payload must not contain raw plaintext bytes",
                    encrypted.containsSubarray(plainBytes)
                )
            }

            assertEquals(plaintext, cipher.decrypt(encrypted))
        }
    }

    @Test
    fun testEncrypt_usesRandomizedNonces() {
        val plaintext = "Bank alert: 123456 is your verification code"
        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertFalse(
            "Encrypting identical plaintext twice must produce different ciphertexts",
            first.contentEquals(second)
        )
        assertEquals(plaintext, cipher.decrypt(first))
        assertEquals(plaintext, cipher.decrypt(second))
    }

    @Test
    fun testDecrypt_legacyPlaintextFallback() {
        val legacyText = "Legacy pre-v1.1.0 plaintext SMS body ✓"
        val legacyBytes = legacyText.toByteArray(Charsets.UTF_8)

        assertEquals(legacyText, cipher.decrypt(legacyBytes))
        assertEquals("Hi", cipher.decrypt("Hi".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun testDecrypt_nullAndEmptyReturnEmptyString() {
        assertEquals("", cipher.decrypt(null))
        assertEquals("", cipher.decrypt(ByteArray(0)))
    }

    @Test
    fun testDecrypt_corruptedAeadCiphertextThrowsGeneralSecurityException() {
        val encrypted = cipher.encrypt("Sensitive OTP 999888")
        val corrupted = encrypted.copyOf()
        corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()

        try {
            cipher.decrypt(corrupted)
            fail("Expected GeneralSecurityException for corrupted AEAD ciphertext")
        } catch (_: GeneralSecurityException) {
            // Expected
        }
    }

    @Test
    fun testDecrypt_wrongKeyThrowsGeneralSecurityException() {
        val encrypted = cipher.encrypt("Top secret message")
        val otherAead = KeysetHandle
            .generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(Aead::class.java)
        val otherCipher = AeadStorageCipher(otherAead)

        try {
            otherCipher.decrypt(encrypted)
            fail("Expected GeneralSecurityException when decrypting with a different key")
        } catch (_: GeneralSecurityException) {
            // Expected
        }
    }

    private fun ByteArray.containsSubarray(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > this.size) return false
        for (i in 0..(this.size - needle.size)) {
            var matched = true
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) {
                    matched = false
                    break
                }
            }
            if (matched) return true
        }
        return false
    }
}

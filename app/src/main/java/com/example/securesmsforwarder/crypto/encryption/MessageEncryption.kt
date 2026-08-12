package com.example.securesmsforwarder.crypto.encryption

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle

/**
 * Handles application-level E2E encryption of the SMS payloads using Google Tink's Hybrid Encryption.
 * 
 * In Hybrid Encryption (e.g., HPKE):
 * - The sender generates an ephemeral symmetric key, encrypts the payload with it, and 
 *   encrypts the symmetric key using the recipient's public identity key.
 * - This provides Forward Secrecy per message without needing an interactive handshake,
 *   making it perfect for asynchronous SMS forwarding where connection state might flap.
 */
class MessageEncryption {

    /**
     * Encrypts the raw SMS bytes using the trusted recipient's public key.
     * The `contextInfo` is used to bind the encryption to this specific protocol/version.
     */
    fun encrypt(
        plaintext: ByteArray,
        recipientPublicKey: KeysetHandle,
        contextInfo: ByteArray = "SecureSmsProtocol_V1".toByteArray()
    ): ByteArray {
        val hybridEncrypt = recipientPublicKey.getPrimitive(HybridEncrypt::class.java)
        return hybridEncrypt.encrypt(plaintext, contextInfo)
    }

    /**
     * Decrypts the ciphertext using the local device's private identity key.
     */
    fun decrypt(
        ciphertext: ByteArray,
        localPrivateKey: KeysetHandle,
        contextInfo: ByteArray = "SecureSmsProtocol_V1".toByteArray()
    ): ByteArray {
        val hybridDecrypt = localPrivateKey.getPrimitive(HybridDecrypt::class.java)
        return hybridDecrypt.decrypt(ciphertext, contextInfo)
    }
}

package com.example.securesmsforwarder.p2p.relay

/**
 * Pure End-to-End Encrypted relay transport abstraction.
 * Implementations transport opaque HPKE ciphertexts, peer presence, and wakeup signals.
 */
interface RelayTransport {
    fun sendEncryptedRelayMessage(messageId: String, ciphertextBase64: String): Boolean
    fun setEncryptedRelayMessageListener(
        listener: (messageId: String, ciphertextBase64: String, onProcessed: (Boolean) -> Unit) -> Unit
    )
    fun requestWakeup()
    fun setWakeupListener(listener: () -> Unit)
    fun isRelayAvailable(): Boolean
    fun setRelayAvailabilityListener(listener: (Boolean) -> Unit)
    fun isPeerOnline(): Boolean
    fun getPeerLastSeen(): Long
    fun setPeerPresenceListener(listener: (isOnline: Boolean, lastSeen: Long) -> Unit)
    fun setErrorListener(listener: (String?) -> Unit)
    fun goOnline()
    fun goOffline()
    fun close()
}

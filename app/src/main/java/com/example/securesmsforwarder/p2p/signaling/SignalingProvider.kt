package com.example.securesmsforwarder.p2p.signaling

import org.webrtc.SessionDescription
import org.webrtc.IceCandidate

/**
 * Interface to abstract WebRTC signaling and fallback End-to-End Encrypted relay messaging.
 */
interface SignalingProvider {
    /**
     * Whether this provider supports sending ICE candidates individually (Trickle ICE).
     */
    fun supportsTrickleIce(): Boolean = false

    /**
     * Called when the local WebRTC transport generates an SDP (Offer or Answer) 
     * that is ready to be sent to the remote peer.
     */
    fun onLocalSdpReady(sdp: SessionDescription)

    /**
     * Called when the local WebRTC transport generates an ICE candidate.
     */
    fun onLocalIceCandidateReady(candidate: IceCandidate) {}

    /**
     * Sets a callback to be invoked when a remote SDP is received from the signaling channel.
     */
    fun setRemoteSdpListener(listener: (SessionDescription) -> Unit)

    /**
     * Sets a callback to be invoked when a remote ICE candidate is received.
     */
    fun setRemoteIceCandidateListener(listener: (IceCandidate) -> Unit) {}

    /**
     * Sends a ping or wakeup request to the remote peer to establish a connection.
     */
    fun requestConnection() {}

    /**
     * Sets a callback to be invoked when a connection request (ping) is received.
     */
    fun setConnectionRequestListener(listener: () -> Unit) {}

    /**
     * Dispatches an E2EE encrypted message via fallback relay (e.g. Firebase Realtime DB).
     */
    fun sendEncryptedRelayMessage(messageId: String, ciphertextBase64: String): Boolean = false

    /**
     * Listens for incoming E2EE encrypted messages received via the fallback relay.
     * The callback provides an onProcessed(success) handler so the provider only deletes the message
     * after it has been safely decrypted and saved.
     */
    fun setEncryptedRelayMessageListener(listener: (messageId: String, ciphertextBase64: String, onProcessed: (Boolean) -> Unit) -> Unit) {}

    /**
     * Checks if the fallback relay is connected and available locally.
     */
    fun isRelayAvailable(): Boolean = false

    /**
     * Sets a callback to be notified when relay connectivity state changes.
     */
    fun setRelayAvailabilityListener(listener: (Boolean) -> Unit) {}

    /**
     * Checks if the remote paired peer is confirmed online via presence.
     */
    fun isPeerOnline(): Boolean = false

    /**
     * Gets the timestamp when the remote peer was last seen online.
     */
    fun getPeerLastSeen(): Long = 0L

    /**
     * Sets a callback to be notified when the remote peer's presence changes.
     */
    fun setPeerPresenceListener(listener: (isOnline: Boolean, lastSeen: Long) -> Unit) {}
}

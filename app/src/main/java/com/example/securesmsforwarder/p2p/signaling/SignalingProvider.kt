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
     */
    fun setEncryptedRelayMessageListener(listener: (messageId: String, ciphertextBase64: String) -> Unit) {}

    /**
     * Checks if the fallback relay is connected and available.
     */
    fun isRelayAvailable(): Boolean = false

    /**
     * Sets a callback to be notified when relay connectivity state changes.
     */
    fun setRelayAvailabilityListener(listener: (Boolean) -> Unit) {}
}

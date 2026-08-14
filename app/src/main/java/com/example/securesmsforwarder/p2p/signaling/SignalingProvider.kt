package com.example.securesmsforwarder.p2p.signaling

import org.webrtc.SessionDescription
import org.webrtc.IceCandidate

/**
 * Interface to abstract the WebRTC signaling mechanism (e.g., Manual Copy/Paste, Rendezvous Server, etc.)
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
}

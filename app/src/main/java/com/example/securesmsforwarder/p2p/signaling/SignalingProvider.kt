package com.example.securesmsforwarder.p2p.signaling

import org.webrtc.SessionDescription

/**
 * Interface to abstract the WebRTC signaling mechanism (e.g., Manual Copy/Paste, Rendezvous Server, etc.)
 */
interface SignalingProvider {
    /**
     * Called when the local WebRTC transport generates an SDP (Offer or Answer) 
     * that is ready to be sent to the remote peer.
     */
    fun onLocalSdpReady(sdp: SessionDescription)

    /**
     * Sets a callback to be invoked when a remote SDP is received from the signaling channel.
     */
    fun setRemoteSdpListener(listener: (SessionDescription) -> Unit)

    /**
     * Sends a ping or wakeup request to the remote peer to establish a connection.
     */
    fun requestConnection() {}

    /**
     * Sets a callback to be invoked when a connection request (ping) is received.
     */
    fun setConnectionRequestListener(listener: () -> Unit) {}
}

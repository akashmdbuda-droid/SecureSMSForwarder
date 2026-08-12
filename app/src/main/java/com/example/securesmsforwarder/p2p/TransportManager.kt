package com.example.securesmsforwarder.p2p

import android.util.Log
import com.example.securesmsforwarder.core.domain.MessageQueueManager
import com.example.securesmsforwarder.crypto.encryption.MessageEncryption
import com.example.securesmsforwarder.crypto.identity.KeyManager
import com.example.securesmsforwarder.p2p.webrtc.WebRtcDirectTransport
import com.example.securesmsforwarder.pairing.TrustStore
import com.example.securesmsforwarder.p2p.signaling.ManualSignalingProvider
import com.example.securesmsforwarder.storage.MessageState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

import com.example.securesmsforwarder.p2p.signaling.SignalingProvider

/**
 * Coordinates WebRTC, Encryption, and the Message Queue.
 */
class TransportManager(
    val webRtcTransport: WebRtcDirectTransport,
    private val encryption: MessageEncryption,
    private val keyManager: KeyManager,
    private val trustStore: TrustStore,
    private val queueManager: MessageQueueManager,
    val signalingProvider: SignalingProvider,
    private val isInitiator: Boolean,
    var onMessageReceived: ((String, String) -> Unit)? = null
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isConnected = false
    private var reconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    var isPaused = false
        private set

    init {
        // Observe connection state
        scope.launch {
            webRtcTransport.connectionStateFlow.collectLatest { state ->
                isConnected = state == PeerConnection.PeerConnectionState.CONNECTED
                Log.d("TransportManager", "WebRTC State changed: $state")
                
                reconnectJob?.cancel()
                if (isConnected) {
                    connectionTimeoutJob?.cancel()
                    Log.d("TransportManager", "Connection established. Exchanging keys & draining queue.")
                    
                    // Auto-Exchange Keys
                    val serializedLocalKey = keyManager.getSerializedPublicKey()
                    if (serializedLocalKey != null) {
                        val keyMsg = "KEY:$serializedLocalKey".toByteArray(Charsets.UTF_8)
                        webRtcTransport.sendMessage(keyMsg)
                    }

                    drainQueue()
                } else if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                    connectionTimeoutJob?.cancel()
                    if (!isPaused) {
                        Log.d("TransportManager", "Connection dropped. Scheduling reconnect...")
                        reconnectJob = launch {
                            delay(5000)
                            Log.d("TransportManager", "Executing auto-reconnect...")
                            webRtcTransport.startConnection(isInitiator)
                        }
                    }
                } else if (state == PeerConnection.PeerConnectionState.NEW || state == PeerConnection.PeerConnectionState.CONNECTING) {
                    connectionTimeoutJob?.cancel()
                    if (!isPaused) {
                        connectionTimeoutJob = launch {
                            delay(15000) // 15s timeout
                            Log.w("TransportManager", "Connection stuck in $state. Forcing restart.")
                            webRtcTransport.startConnection(isInitiator)
                        }
                    }
                }
            }
        }

        // Observe local SDP generation and pass to Signaling Provider
        scope.launch {
            webRtcTransport.localSdpFlow.collectLatest { sdp ->
                signalingProvider.onLocalSdpReady(sdp)
            }
        }

        // Wire up Signaling Provider to WebRTC Transport
        signalingProvider.setRemoteSdpListener { remoteSdp: SessionDescription ->
            if (remoteSdp.type == SessionDescription.Type.OFFER) {
                Log.d("TransportManager", "Received remote offer, cancelling any pending reconnect job")
                reconnectJob?.cancel()
                webRtcTransport.setRemoteDescription(remoteSdp)
            } else {
                webRtcTransport.setRemoteAnswer(remoteSdp)
            }
        }

        signalingProvider.setConnectionRequestListener {
            if (!isConnected && !isPaused) {
                Log.d("TransportManager", "Received connection request (wakeup) from remote peer. Restarting connection...")
                reconnectJob?.cancel()
                connectionTimeoutJob?.cancel()
                webRtcTransport.startConnection(isInitiator)
            }
        }

        observeIncomingMessages()
        startRetryLoop()
    }

    private fun startRetryLoop() {
        scope.launch {
            while (isActive) {
                if (isConnected) {
                    queueManager.checkRetries()
                }
                delay(5000) // Check every 5 seconds
            }
        }
    }

    private fun observeIncomingMessages() {
        scope.launch {
            webRtcTransport.incomingMessagesFlow.collect { ciphertext ->
                try {
                    val rawStr = String(ciphertext, Charsets.UTF_8)
                    if (rawStr.startsWith("KEY:")) {
                        val peerKey = rawStr.removePrefix("KEY:")
                        val fingerprint = com.example.securesmsforwarder.crypto.identity.KeyManager.computeFingerprint(peerKey)
                        trustStore.addTrustedPeer(fingerprint, peerKey)
                        return@collect
                    }

                    val localKeyset = keyManager.getOrGenerateIdentityKeyset()
                    val plaintextBytes = encryption.decrypt(ciphertext, localKeyset)
                    val plaintext = String(plaintextBytes, Charsets.UTF_8)
                    
                    try {
                        val json = org.json.JSONObject(plaintext)
                        val type = json.optString("type")
                        
                        when (type) {
                            "WIPE" -> {
                                if (isInitiator) {
                                    Log.w("TransportManager", "Sender ignoring unauthorized WIPE command from Viewer.")
                                } else {
                                    Log.d("TransportManager", "Received WIPE command. Clearing database.")
                                    queueManager.deleteAll()
                                }
                            }
                            "ACK" -> {
                                if (!isInitiator) {
                                    Log.w("TransportManager", "Viewer ignoring ACK command.")
                                } else {
                                    val msgId = json.optString("id")
                                    Log.d("TransportManager", "Received ACK for $msgId")
                                    queueManager.markAcknowledged(msgId)
                                }
                            }
                            "SMS" -> {
                                if (isInitiator) {
                                    Log.w("TransportManager", "Sender ignoring incoming SMS payload.")
                                } else {
                                    val msgId = json.getString("id")
                                    val body = json.getString("body")
                                    val sender = json.optString("sender", "Unknown Sender")
                                    val forwarder = json.optString("forwarder", "Unknown Device")
                                    val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                                    
                                    Log.d("TransportManager", "Received JSON SMS $msgId from $sender via $forwarder")
                                    queueManager.insertViewerMessage(msgId, body, sender, forwarder, timestamp)
                                    onMessageReceived?.invoke(sender, body)
                                    
                                    // Send ACK back
                                    val ackPayload = org.json.JSONObject().apply {
                                        put("type", "ACK")
                                        put("id", msgId)
                                    }.toString()
                                    
                                    val trustedPeer = trustStore.getTrustedPeerPublicKeyset()
                                    if (trustedPeer != null) {
                                        val encAck = encryption.encrypt(ackPayload.toByteArray(Charsets.UTF_8), trustedPeer)
                                        webRtcTransport.sendMessage(encAck)
                                    }
                                }
                            }
                            else -> {
                                Log.e("TransportManager", "Unknown JSON payload type: $type")
                            }
                        }
                    } catch (e: org.json.JSONException) {
                        // Fallback for legacy string payload (e.g. msgId|body or WIPE:ALL or ACK:msgId)
                        if (plaintext == "WIPE:ALL") {
                            if (!isInitiator) {
                                Log.d("TransportManager", "Received WIPE command. Clearing database.")
                                queueManager.deleteAll()
                            }
                        } else if (plaintext.startsWith("ACK:")) {
                            if (isInitiator) {
                                val msgId = plaintext.removePrefix("ACK:")
                                Log.d("TransportManager", "Received ACK for $msgId")
                                queueManager.markAcknowledged(msgId)
                            }
                        } else if (plaintext.contains("|")) {
                            if (!isInitiator) {
                                val parts = plaintext.split("|", limit = 2)
                                if (parts.size == 2) {
                                    val msgId = parts[0]
                                    val body = parts[1]
                                    Log.d("TransportManager", "Received Viewer Message $msgId")
                                    queueManager.insertViewerMessage(msgId, body, "Unknown Sender", "Unknown Device", System.currentTimeMillis())
                                    onMessageReceived?.invoke("Unknown Sender", body)
                                        
                                    // Send ACK
                                    val ackPayload = "ACK:$msgId"
                                    val trustedPeer = trustStore.getTrustedPeerPublicKeyset()
                                    if (trustedPeer != null) {
                                        val encAck = encryption.encrypt(ackPayload.toByteArray(Charsets.UTF_8), trustedPeer)
                                        webRtcTransport.sendMessage(encAck)
                                    }
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("TransportManager", "Failed to decrypt or process message", e)
                }
            }
        }
    }

    private fun drainQueue() {
        scope.launch {
            val trustedPeerKeyset = trustStore.getTrustedPeerPublicKeyset() ?: return@launch
            
            queueManager.getPendingMessagesFlow().collectLatest { messages ->
                if (!isConnected) return@collectLatest
                
                messages.forEach { msg ->
                    if (msg.state == MessageState.TRANSMITTING && msg.nextRetryTime > System.currentTimeMillis()) {
                        return@forEach // Skip if it's not time to retry yet
                    }
                    if (msg.retryCount > 5) {
                        queueManager.markFailed(msg.messageId) // Add this to QueueManager later
                        return@forEach
                    }

                    val jsonPayload = org.json.JSONObject().apply {
                        put("type", "SMS")
                        put("id", msg.messageId)
                        put("body", String(msg.encryptedPayload ?: ByteArray(0), Charsets.UTF_8))
                        put("timestamp", msg.timestamp)
                        put("sender", msg.senderDeviceId)
                        put("forwarder", keyManager.getPublicKeyFingerprint())
                    }.toString()
                    
                    val ciphertext = encryption.encrypt(jsonPayload.toByteArray(Charsets.UTF_8), trustedPeerKeyset)
                    
                    val success = webRtcTransport.sendMessage(ciphertext)
                    if (success) {
                        queueManager.markTransmitting(msg.messageId)
                    }
                }
            }
        }
    }
    
    fun sendWipeCommand() {
        scope.launch {
            if (!isConnected) return@launch
            val trustedPeer = trustStore.getTrustedPeerPublicKeyset() ?: return@launch
            try {
                val wipeMsg = org.json.JSONObject().apply {
                    put("type", "WIPE")
                    put("id", "all")
                    put("timestamp", System.currentTimeMillis())
                }.toString().toByteArray(Charsets.UTF_8)
                
                val ciphertext = encryption.encrypt(wipeMsg, trustedPeer)
                webRtcTransport.sendMessage(ciphertext)
                Log.d("TransportManager", "Sent WIPE command via JSON")
            } catch (e: Exception) {
                Log.e("TransportManager", "Failed to send wipe command", e)
            }
        }
    }

    fun setConnectionPaused(paused: Boolean) {
        if (isPaused == paused) return
        isPaused = paused
        if (paused) {
            Log.d("TransportManager", "Connection paused manually. Tearing down connection.")
            reconnectJob?.cancel()
            webRtcTransport.suspendConnection()
        } else {
            Log.d("TransportManager", "Connection resumed manually. Attempting start.")
            webRtcTransport.startConnection(isInitiator)
        }
    }

    fun requestConnection() {
        Log.d("TransportManager", "Manual connection request initiated.")
        reconnectJob?.cancel()
        connectionTimeoutJob?.cancel()
        webRtcTransport.startConnection(isInitiator)
        signalingProvider.requestConnection()
    }

    fun close() {
        scope.cancel()
    }
}

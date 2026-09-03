package com.example.securesmsforwarder.p2p

import android.util.Base64
import android.util.Log
import com.example.securesmsforwarder.core.domain.MessageQueueManager
import com.example.securesmsforwarder.crypto.encryption.MessageEncryption
import com.example.securesmsforwarder.crypto.identity.KeyManager
import com.example.securesmsforwarder.p2p.webrtc.WebRtcDirectTransport
import com.example.securesmsforwarder.pairing.TrustStore
import com.example.securesmsforwarder.p2p.signaling.ManualSignalingProvider
import com.example.securesmsforwarder.storage.MessageState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

import com.example.securesmsforwarder.p2p.signaling.SignalingProvider

/**
 * Coordinates WebRTC, Encrypted Fallback Relay, Encryption, and the Message Queue.
 * Implements a 100% fail-proof dual-tier transport architecture:
 * - Tier 1: Direct WebRTC DataChannel (high-throughput local/STUN/TURN)
 * - Tier 2: E2EE Encrypted Fallback Relay via Firebase Realtime Database
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
    companion object {
        private const val TAG = "TransportManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isWebRtcConnected = false
    private var reconnectJob: Job? = null
    private var connectionTimeoutJob: Job? = null
    var isPaused = false
        private set

    // Unified connection state flow for UI and background service
    private val _connectionStatusFlow = MutableStateFlow(
        if (trustStore.getTrustedDeviceId() != null) "CONNECTING" else "DISCONNECTED"
    )
    val connectionStatusFlow: StateFlow<String> = _connectionStatusFlow.asStateFlow()

    init {
        // Observe WebRTC connection state
        scope.launch {
            webRtcTransport.connectionStateFlow.collectLatest { state ->
                isWebRtcConnected = state == PeerConnection.PeerConnectionState.CONNECTED
                Log.d(TAG, "WebRTC State changed: $state")
                updateConnectionStatus()
                
                reconnectJob?.cancel()
                if (isWebRtcConnected) {
                    connectionTimeoutJob?.cancel()
                    Log.d(TAG, "WebRTC P2P direct connection established. Exchanging keys & draining queue.")
                    
                    // Auto-Exchange Keys
                    val serializedLocalKey = keyManager.getSerializedPublicKey()
                    val keyMsg = "KEY:$serializedLocalKey".toByteArray(Charsets.UTF_8)
                    webRtcTransport.sendMessage(keyMsg)

                    drainQueue()
                } else if (state == PeerConnection.PeerConnectionState.FAILED || state == PeerConnection.PeerConnectionState.DISCONNECTED) {
                    connectionTimeoutJob?.cancel()
                    if (!isPaused) {
                        Log.d(TAG, "WebRTC dropped. Fallback relay remains active. Scheduling WebRTC reconnect...")
                        reconnectJob = launch {
                            delay(5000)
                            Log.d(TAG, "Executing auto-reconnect...")
                            webRtcTransport.startConnection(isInitiator, signalingProvider.supportsTrickleIce())
                        }
                    }
                } else if (state == PeerConnection.PeerConnectionState.NEW || state == PeerConnection.PeerConnectionState.CONNECTING) {
                    connectionTimeoutJob?.cancel()
                    if (!isPaused) {
                        connectionTimeoutJob = launch {
                            delay(15000) // 15s timeout
                            Log.w(TAG, "WebRTC connection stuck in $state. Refreshing.")
                            webRtcTransport.startConnection(isInitiator, signalingProvider.supportsTrickleIce())
                        }
                    }
                }
            }
        }

        // Observe relay connectivity
        signalingProvider.setRelayAvailabilityListener { isAvailable ->
            Log.d(TAG, "Relay availability changed: $isAvailable")
            updateConnectionStatus()
            if (isAvailable && !isPaused) {
                drainQueue()
            }
        }

        // Observe local SDP generation and pass to Signaling Provider
        scope.launch {
            webRtcTransport.localSdpFlow.collectLatest { sdp ->
                signalingProvider.onLocalSdpReady(sdp)
            }
        }

        // Observe local ICE candidates
        scope.launch {
            webRtcTransport.localIceCandidatesFlow.collect { candidate ->
                if (signalingProvider.supportsTrickleIce()) {
                    signalingProvider.onLocalIceCandidateReady(candidate)
                }
            }
        }

        // Wire up remote ICE candidates
        signalingProvider.setRemoteIceCandidateListener { candidate ->
            webRtcTransport.addRemoteIceCandidate(candidate)
        }

        // Wire up Signaling Provider to WebRTC Transport
        signalingProvider.setRemoteSdpListener { remoteSdp: SessionDescription ->
            if (remoteSdp.type == SessionDescription.Type.OFFER) {
                Log.d(TAG, "Received remote offer, cancelling any pending reconnect job")
                reconnectJob?.cancel()
                webRtcTransport.setRemoteDescription(remoteSdp)
            } else {
                webRtcTransport.setRemoteAnswer(remoteSdp)
            }
        }

        signalingProvider.setConnectionRequestListener {
            if (!isWebRtcConnected && !isPaused) {
                Log.d(TAG, "Received connection request (wakeup) from remote peer. Restarting WebRTC connection...")
                reconnectJob?.cancel()
                connectionTimeoutJob?.cancel()
                webRtcTransport.startConnection(isInitiator, signalingProvider.supportsTrickleIce())
            }
            // Also immediately drain queue upon remote wakeup
            drainQueue()
        }

        // Wire up Tier 2: E2EE Encrypted Fallback Relay messages
        signalingProvider.setEncryptedRelayMessageListener { messageId, ciphertextBase64 ->
            scope.launch {
                try {
                    val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
                    Log.d(TAG, "Processing message $messageId received via E2EE Fallback Relay (${ciphertext.size} bytes)")
                    processIncomingCiphertext(ciphertext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode relay message $messageId", e)
                }
            }
        }

        // Wire up Tier 1: Direct WebRTC DataChannel incoming messages
        observeIncomingMessages()
        
        // Start continuous background retry and queue draining loop
        startRetryLoop()
    }

    private fun updateConnectionStatus() {
        val status = when {
            isPaused -> "PAUSED"
            trustStore.getTrustedDeviceId() == null -> "DISCONNECTED"
            isWebRtcConnected -> "CONNECTED (Direct P2P)"
            signalingProvider.isRelayAvailable() -> "CONNECTED (Encrypted Relay)"
            else -> "CONNECTING"
        }
        _connectionStatusFlow.value = status
    }

    private fun startRetryLoop() {
        scope.launch {
            while (isActive) {
                if (!isPaused && trustStore.getTrustedDeviceId() != null) {
                    drainQueue()
                    queueManager.checkRetries()
                }
                delay(3500) // Check and sync every 3.5 seconds
            }
        }
    }

    private fun observeIncomingMessages() {
        scope.launch {
            webRtcTransport.incomingMessagesFlow.collect { ciphertext ->
                processIncomingCiphertext(ciphertext)
            }
        }
    }

    private suspend fun processIncomingCiphertext(ciphertext: ByteArray) {
        try {
            val rawStr = String(ciphertext, Charsets.UTF_8)
            if (rawStr.startsWith("KEY:")) {
                val peerKey = rawStr.removePrefix("KEY:")
                val fingerprint = com.example.securesmsforwarder.crypto.identity.KeyManager.computeFingerprint(peerKey)
                trustStore.addTrustedPeer(fingerprint, peerKey)
                return
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
                            Log.w(TAG, "Sender ignoring unauthorized WIPE command from Viewer.")
                        } else {
                            Log.d(TAG, "Received WIPE command. Clearing database.")
                            queueManager.deleteAll()
                        }
                    }
                    "ACK" -> {
                        if (!isInitiator) {
                            Log.w(TAG, "Viewer ignoring ACK command.")
                        } else {
                            val msgId = json.optString("id")
                            Log.d(TAG, "Received ACK for $msgId. Marking DELIVERED.")
                            queueManager.markAcknowledged(msgId)
                        }
                    }
                    "SMS" -> {
                        if (isInitiator) {
                            Log.w(TAG, "Sender ignoring incoming SMS payload.")
                        } else {
                            val msgId = json.getString("id")
                            val body = json.getString("body")
                            val sender = json.optString("sender", "Unknown Sender")
                            val forwarder = json.optString("forwarder", "Unknown Device")
                            val timestamp = json.optLong("timestamp", System.currentTimeMillis())

                            Log.d(TAG, "Received SMS $msgId from $sender via $forwarder")
                            val isNew = queueManager.insertViewerMessage(msgId, body, sender, forwarder, timestamp)
                            if (isNew) {
                                onMessageReceived?.invoke(sender, body)
                            }

                            // Send ACK back via WebRTC or Relay
                            sendAck(msgId)
                        }
                    }
                    else -> {
                        Log.e(TAG, "Unknown JSON payload type: $type")
                    }
                }
            } catch (e: org.json.JSONException) {
                // Fallback for legacy plain-text payload
                if (plaintext == "WIPE:ALL") {
                    if (!isInitiator) {
                        Log.d(TAG, "Received WIPE command. Clearing database.")
                        queueManager.deleteAll()
                    }
                } else if (plaintext.startsWith("ACK:")) {
                    if (isInitiator) {
                        val msgId = plaintext.removePrefix("ACK:")
                        Log.d(TAG, "Received legacy ACK for $msgId")
                        queueManager.markAcknowledged(msgId)
                    }
                } else if (plaintext.contains("|")) {
                    if (!isInitiator) {
                        val parts = plaintext.split("|", limit = 2)
                        if (parts.size == 2) {
                            val msgId = parts[0]
                            val body = parts[1]
                            Log.d(TAG, "Received legacy Viewer Message $msgId")
                            val isNew = queueManager.insertViewerMessage(msgId, body, "Unknown Sender", "Unknown Device", System.currentTimeMillis())
                            if (isNew) {
                                onMessageReceived?.invoke("Unknown Sender", body)
                            }
                            sendAck(msgId)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt or process message", e)
        }
    }

    private fun sendAck(msgId: String) {
        val trustedPeer = trustStore.getTrustedPeerPublicKeyset() ?: return
        val ackPayload = org.json.JSONObject().apply {
            put("type", "ACK")
            put("id", msgId)
        }.toString()

        val encAck = encryption.encrypt(ackPayload.toByteArray(Charsets.UTF_8), trustedPeer)
        
        // Try direct WebRTC first
        var sent = webRtcTransport.sendMessage(encAck)
        if (!sent) {
            // Fallback to Encrypted Relay
            val base64 = Base64.encodeToString(encAck, Base64.NO_WRAP)
            sent = signalingProvider.sendEncryptedRelayMessage("ACK_$msgId", base64)
        }
        Log.d(TAG, "Sent ACK for $msgId (via WebRTC=$sent)")
    }

    private fun drainQueue() {
        scope.launch {
            if (isPaused) return@launch
            val trustedPeerKeyset = trustStore.getTrustedPeerPublicKeyset() ?: return@launch
            val now = System.currentTimeMillis()

            val messages = queueManager.getPendingMessagesFlow().first()
            for (msg in messages) {
                if (msg.state == MessageState.TRANSMITTING && msg.nextRetryTime > now) {
                    continue // Not yet time for next retry attempt
                }
                if (msg.retryCount > 8) {
                    queueManager.markFailed(msg.messageId)
                    continue
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

                // Dual-tier dispatch: Try WebRTC DataChannel first, fallback to Encrypted Relay
                var delivered = webRtcTransport.sendMessage(ciphertext)
                if (!delivered) {
                    val base64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
                    delivered = signalingProvider.sendEncryptedRelayMessage(msg.messageId, base64)
                }

                if (delivered) {
                    queueManager.markTransmitting(msg.messageId)
                    Log.d(TAG, "Dispatched SMS ${msg.messageId} (attempt: ${msg.retryCount})")
                }
            }
        }
    }

    fun sendWipeCommand() {
        scope.launch {
            val trustedPeer = trustStore.getTrustedPeerPublicKeyset() ?: return@launch
            try {
                val wipeMsg = org.json.JSONObject().apply {
                    put("type", "WIPE")
                    put("id", "all")
                    put("timestamp", System.currentTimeMillis())
                }.toString().toByteArray(Charsets.UTF_8)

                val ciphertext = encryption.encrypt(wipeMsg, trustedPeer)
                var sent = webRtcTransport.sendMessage(ciphertext)
                if (!sent) {
                    val base64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
                    sent = signalingProvider.sendEncryptedRelayMessage("WIPE_ALL", base64)
                }
                Log.d(TAG, "Sent WIPE command (delivered=$sent)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send wipe command", e)
            }
        }
    }

    fun setConnectionPaused(paused: Boolean) {
        if (isPaused == paused) return
        isPaused = paused
        updateConnectionStatus()
        if (paused) {
            Log.d(TAG, "Connection paused manually. Tearing down connection.")
            reconnectJob?.cancel()
            connectionTimeoutJob?.cancel()
            webRtcTransport.suspendConnection()
        } else {
            Log.d(TAG, "Connection resumed manually. Attempting start.")
            webRtcTransport.startConnection(isInitiator, signalingProvider.supportsTrickleIce())
            drainQueue()
        }
    }

    fun requestConnection() {
        Log.d(TAG, "Connection request/sync initiated.")
        reconnectJob?.cancel()
        connectionTimeoutJob?.cancel()
        webRtcTransport.startConnection(isInitiator, signalingProvider.supportsTrickleIce())
        signalingProvider.requestConnection()
        drainQueue()
    }

    fun close() {
        reconnectJob?.cancel()
        connectionTimeoutJob?.cancel()
        scope.cancel()
    }
}

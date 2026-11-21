package com.example.securesmsforwarder.p2p

import android.util.Base64
import android.util.Log
import com.example.securesmsforwarder.core.domain.MessageQueueManager
import com.example.securesmsforwarder.crypto.encryption.MessageEncryption
import com.example.securesmsforwarder.crypto.identity.KeyManager
import com.example.securesmsforwarder.crypto.storage.StorageCipher
import com.example.securesmsforwarder.p2p.relay.RelayTransport
import com.example.securesmsforwarder.pairing.TrustStore
import com.example.securesmsforwarder.storage.MessageState
import com.google.crypto.tink.KeysetHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONException
import org.json.JSONObject

/**
 * Coordinates the pure End-to-End Encrypted Firebase Relay transport,
 * Tink HPKE wire encryption, Room at-rest decryption, and serialized queue delivery.
 */
class TransportManager(
    private val relayTransport: RelayTransport,
    private val encryption: MessageEncryption,
    private val keyManager: KeyManager? = null,
    private val trustStore: TrustStore,
    private val queueManager: MessageQueueManager,
    private val storageCipher: StorageCipher,
    private val isInitiator: Boolean,
    private val localPrivateKeyProvider: () -> KeysetHandle = {
        requireNotNull(keyManager) { "Either keyManager or localPrivateKeyProvider must be provided" }
            .getOrGenerateIdentityKeyset()
    },
    private val localFingerprintProvider: () -> String = {
        requireNotNull(keyManager) { "Either keyManager or localFingerprintProvider must be provided" }
            .getPublicKeyFingerprint()
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryIntervalMs: Long = 3500L,
    var onMessageReceived: ((String, String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "TransportManager"
        private const val MAX_RETRY_COUNT = 25
    }

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val drainMutex = Mutex()

    @Volatile
    var isPaused: Boolean = false
        private set

    @Volatile
    private var lastError: String? = null

    private val _connectionUiState = MutableStateFlow(computeUiState())
    val connectionUiState: StateFlow<ConnectionUiState> = _connectionUiState.asStateFlow()

    init {
        relayTransport.setErrorListener { error ->
            lastError = error
            updateConnectionUiState()
        }

        relayTransport.setRelayAvailabilityListener { isAvailable ->
            Log.d(TAG, "Relay availability changed: $isAvailable")
            updateConnectionUiState()
            if (isAvailable && !isPaused) {
                drainQueue()
            }
        }

        relayTransport.setPeerPresenceListener { isOnline, lastSeen ->
            Log.d(TAG, "Peer presence changed: online=$isOnline, lastSeen=$lastSeen")
            updateConnectionUiState()
            if (isOnline && !isPaused) {
                scope.launch {
                    if (isPaused) return@launch
                    val requeued = queueManager.requeueFailedMessages()
                    if (requeued > 0) {
                        Log.d(TAG, "Peer is back online! Requeued $requeued failed messages for delivery.")
                    }
                    drainQueueInternal()
                }
            }
        }

        relayTransport.setWakeupListener {
            Log.d(TAG, "Received wakeup ping from remote peer.")
            if (!isPaused) {
                scope.launch {
                    if (isPaused) return@launch
                    queueManager.requeueFailedMessages()
                    drainQueueInternal()
                }
            }
        }

        relayTransport.setEncryptedRelayMessageListener { messageId, ciphertextBase64, onProcessed ->
            scope.launch {
                try {
                    val ciphertext = Base64.decode(ciphertextBase64, Base64.NO_WRAP)
                    Log.d(TAG, "Processing message $messageId received via E2EE Relay (${ciphertext.size} bytes)")
                    val success = processIncomingCiphertext(ciphertext)
                    onProcessed(success)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode relay message $messageId", e)
                    onProcessed(false)
                }
            }
        }

        startRetryLoop()
    }

    private fun computeUiState(): ConnectionUiState {
        val paused = isPaused
        val relayAvailable = !paused && relayTransport.isRelayAvailable()
        val peerOnline = !paused && relayTransport.isPeerOnline()
        val status = when {
            paused -> "PAUSED"
            trustStore.getTrustedDeviceId() == null -> "DISCONNECTED"
            relayAvailable && peerOnline -> "CONNECTED (Encrypted Relay)"
            relayAvailable && !peerOnline -> "RELAY STANDBY (Peer Offline)"
            else -> "CONNECTING"
        }
        return ConnectionUiState(
            status = status,
            isRelayConnected = relayAvailable,
            isPeerOnline = peerOnline,
            peerLastSeen = relayTransport.getPeerLastSeen(),
            isPaused = paused,
            lastError = lastError
        )
    }

    private fun updateConnectionUiState() {
        _connectionUiState.value = computeUiState()
    }

    private fun startRetryLoop() {
        scope.launch {
            while (isActive) {
                if (!isPaused && trustStore.getTrustedDeviceId() != null) {
                    drainQueueInternal()
                    queueManager.checkRetries()
                }
                delay(retryIntervalMs)
            }
        }
    }

    internal suspend fun processIncomingCiphertext(ciphertext: ByteArray): Boolean {
        return try {
            val localKeyset = localPrivateKeyProvider()
            val plaintextBytes = encryption.decrypt(ciphertext, localKeyset)
            val plaintext = String(plaintextBytes, Charsets.UTF_8)

            val json = try {
                JSONObject(plaintext)
            } catch (e: JSONException) {
                Log.w(TAG, "Rejecting non-JSON decrypted payload", e)
                return false
            }

            when (val type = json.optString("type")) {
                "WIPE" -> {
                    if (isInitiator) {
                        Log.w(TAG, "Sender ignoring unauthorized WIPE command from Viewer.")
                    } else {
                        Log.d(TAG, "Received WIPE command. Clearing database.")
                        queueManager.deleteAll()
                    }
                    true
                }
                "ACK" -> {
                    if (!isInitiator) {
                        Log.w(TAG, "Viewer ignoring ACK command.")
                    } else {
                        val msgId = json.optString("id")
                        if (msgId.isNotBlank()) {
                            Log.d(TAG, "Received ACK for $msgId. Marking DELIVERED.")
                            queueManager.markAcknowledged(msgId)
                        }
                    }
                    true
                }
                "SMS" -> {
                    if (isInitiator) {
                        Log.w(TAG, "Sender ignoring incoming SMS payload.")
                        true
                    } else {
                        val msgId = json.getString("id")
                        val body = json.getString("body")
                        if (msgId.isBlank()) {
                            Log.w(TAG, "Rejecting SMS payload with blank id.")
                            return false
                        }
                        val sender = json.optString("sender", "Unknown Sender")
                        val forwarder = json.optString("forwarder", "Unknown Device")
                        val timestamp = json.optLong("timestamp", System.currentTimeMillis())

                        Log.d(TAG, "Received SMS $msgId from $sender via $forwarder")
                        val isNew = queueManager.insertViewerMessage(msgId, body, sender, forwarder, timestamp)
                        if (isNew) {
                            onMessageReceived?.invoke(sender, body)
                        }
                        sendAck(msgId)
                        true
                    }
                }
                else -> {
                    Log.w(TAG, "Rejecting payload with unknown JSON type: '$type'")
                    false
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Rejecting malformed JSON payload", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt or process incoming relay message", e)
            false
        }
    }

    private fun sendAck(msgId: String) {
        val trustedPeer = trustStore.getTrustedPeerPublicKeyset() ?: return
        try {
            val ackPayload = JSONObject().apply {
                put("type", "ACK")
                put("id", msgId)
            }.toString()

            val encAck = encryption.encrypt(ackPayload.toByteArray(Charsets.UTF_8), trustedPeer)
            val base64 = Base64.encodeToString(encAck, Base64.NO_WRAP)
            val sent = relayTransport.sendEncryptedRelayMessage("ACK_$msgId", base64)
            Log.d(TAG, "Sent ACK for $msgId via E2EE Relay (delivered=$sent)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ACK for $msgId", e)
        }
    }

    fun triggerQueueDrain(requeueFailed: Boolean = false) {
        scope.launch {
            if (!isPaused && trustStore.getTrustedDeviceId() != null) {
                if (requeueFailed) {
                    queueManager.requeueFailedMessages()
                }
                drainQueueInternal()
            }
        }
    }

    private fun drainQueue() {
        scope.launch {
            drainQueueInternal()
        }
    }

    internal suspend fun drainQueueInternal() {
        drainMutex.withLock {
            if (isPaused) return
            val trustedPeerKeyset = trustStore.getTrustedPeerPublicKeyset() ?: return
            val now = System.currentTimeMillis()

            val messages = queueManager.getPendingMessagesFlow().first()
            for (msg in messages) {
                if (msg.state == MessageState.FAILED) {
                    continue
                }
                if (msg.state == MessageState.TRANSMITTING && msg.nextRetryTime > now) {
                    continue
                }
                if (msg.retryCount > MAX_RETRY_COUNT) {
                    queueManager.markFailed(msg.messageId)
                    continue
                }

                try {
                    val plainBody = storageCipher.decrypt(msg.encryptedPayload)
                    val jsonPayload = JSONObject().apply {
                        put("type", "SMS")
                        put("id", msg.messageId)
                        put("body", plainBody)
                        put("timestamp", msg.timestamp)
                        put("sender", msg.senderDeviceId)
                        put("forwarder", localFingerprintProvider())
                    }.toString()

                    val ciphertext = encryption.encrypt(
                        jsonPayload.toByteArray(Charsets.UTF_8),
                        trustedPeerKeyset
                    )
                    val base64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
                    val delivered = relayTransport.sendEncryptedRelayMessage(msg.messageId, base64)

                    if (delivered) {
                        queueManager.markTransmitting(msg.messageId)
                        Log.d(TAG, "Dispatched SMS ${msg.messageId} (attempt: ${msg.retryCount + 1})")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to encrypt or dispatch SMS ${msg.messageId}", e)
                }
            }
        }
    }

    fun sendWipeCommand() {
        scope.launch {
            val trustedPeer = trustStore.getTrustedPeerPublicKeyset() ?: return@launch
            try {
                val wipeMsg = JSONObject().apply {
                    put("type", "WIPE")
                    put("id", "all")
                    put("timestamp", System.currentTimeMillis())
                }.toString().toByteArray(Charsets.UTF_8)

                val ciphertext = encryption.encrypt(wipeMsg, trustedPeer)
                val base64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
                val sent = relayTransport.sendEncryptedRelayMessage("WIPE_ALL", base64)
                Log.d(TAG, "Sent WIPE command via E2EE Relay (delivered=$sent)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send wipe command", e)
            }
        }
    }

    fun setConnectionPaused(paused: Boolean) {
        if (isPaused == paused) {
            updateConnectionUiState()
            return
        }
        isPaused = paused
        if (paused) {
            Log.d(TAG, "Connection paused manually. Detaching relay transport.")
            relayTransport.goOffline()
            updateConnectionUiState()
        } else {
            Log.d(TAG, "Connection resumed manually. Bringing relay transport online.")
            lastError = null
            relayTransport.goOnline()
            updateConnectionUiState()
            drainQueue()
        }
    }

    fun requestConnection() {
        Log.d(TAG, "Connection request/sync initiated.")
        isPaused = false
        lastError = null
        updateConnectionUiState()
        relayTransport.goOnline()
        updateConnectionUiState()
        relayTransport.requestWakeup()
        scope.launch {
            queueManager.requeueFailedMessages()
            drainQueueInternal()
        }
    }

    fun close() {
        relayTransport.close()
        scope.cancel()
    }
}

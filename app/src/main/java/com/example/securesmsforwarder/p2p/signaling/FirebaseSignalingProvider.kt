package com.example.securesmsforwarder.p2p.signaling

import android.util.Log
import com.example.securesmsforwarder.core.domain.DeviceRole
import com.google.firebase.database.*
import org.webrtc.SessionDescription

class FirebaseSignalingProvider(
    private val localDeviceId: String,
    private val remoteDeviceId: String, // The trusted peer we are talking to
    private val role: DeviceRole
) : SignalingProvider {

    companion object {
        private const val TAG = "FirebaseSignaling"
    }

    private val database = FirebaseDatabase.getInstance()
    private var remoteSdpListener: ((SessionDescription) -> Unit)? = null
    private var connectionRequestListener: (() -> Unit)? = null
    private var encryptedRelayListener: ((String, String, (Boolean) -> Unit) -> Unit)? = null
    private var relayAvailabilityListener: ((Boolean) -> Unit)? = null
    private var peerPresenceListener: ((Boolean, Long) -> Unit)? = null

    private var isFirebaseConnected: Boolean = false
    private var isPeerOnlineCached: Boolean = false
    private var peerLastSeenCached: Long = 0L

    private val pendingRelayMessages = mutableListOf<Pair<DataSnapshot, String>>()

    init {
        listenForFirebaseConnection()
        listenForPeerPresence()
        listenForRemoteSdp()
        listenForConnectionRequests()
        listenForRelayMessages()
    }

    private fun listenForFirebaseConnection() {
        val connectedRef = database.getReference(".info/connected")
        connectedRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                isFirebaseConnected = connected
                Log.d(TAG, "Firebase connection state: $connected")
                relayAvailabilityListener?.invoke(connected)

                if (connected) {
                    updateLocalPresence()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase .info/connected cancelled", error.toException())
            }
        })
    }

    private fun updateLocalPresence() {
        if (localDeviceId.isBlank()) return
        val presenceRef = database.getReference("presence/$localDeviceId")
        // On disconnect, mark offline and record lastSeen timestamp
        presenceRef.onDisconnect().setValue(mapOf(
            "online" to false,
            "lastSeen" to ServerValue.TIMESTAMP,
            "role" to role.name
        ))
        // Set online status now
        presenceRef.setValue(mapOf(
            "online" to true,
            "lastSeen" to ServerValue.TIMESTAMP,
            "role" to role.name
        )).addOnFailureListener {
            Log.e(TAG, "Failed to publish local presence", it)
        }
    }

    private fun listenForPeerPresence() {
        if (remoteDeviceId.isBlank()) return
        val peerPresenceRef = database.getReference("presence/$remoteDeviceId")
        peerPresenceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.child("online").getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                isPeerOnlineCached = isOnline
                peerLastSeenCached = lastSeen
                Log.d(TAG, "Peer presence updated: peer=$remoteDeviceId, online=$isOnline, lastSeen=$lastSeen")
                peerPresenceListener?.invoke(isOnline, lastSeen)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Peer presence listener cancelled", error.toException())
            }
        })
    }

    override fun isRelayAvailable(): Boolean = isFirebaseConnected

    override fun setRelayAvailabilityListener(listener: (Boolean) -> Unit) {
        relayAvailabilityListener = listener
        listener.invoke(isFirebaseConnected)
    }

    override fun isPeerOnline(): Boolean = isPeerOnlineCached

    override fun getPeerLastSeen(): Long = peerLastSeenCached

    override fun setPeerPresenceListener(listener: (isOnline: Boolean, lastSeen: Long) -> Unit) {
        peerPresenceListener = listener
        listener.invoke(isPeerOnlineCached, peerLastSeenCached)
    }

    override fun onLocalSdpReady(sdp: SessionDescription) {
        if (localDeviceId.isBlank()) return
        val envelope = SignalingEnvelope.fromSessionDescription(sdp, localDeviceId)
        val base64 = envelope.toBase64String()

        // Clear stale ICE candidates when starting a new session
        if (sdp.type == SessionDescription.Type.OFFER || sdp.type == SessionDescription.Type.ANSWER) {
            database.getReference("signaling/$localDeviceId/candidates").removeValue()
        }

        val type = if (sdp.type == SessionDescription.Type.OFFER) "offer" else "answer"
        val ref = database.getReference("signaling/$localDeviceId/$type")
        ref.setValue(base64).addOnSuccessListener {
            Log.d(TAG, "Local $type SDP pushed to Firebase")
        }.addOnFailureListener {
            Log.e(TAG, "Failed to push local SDP to Firebase", it)
        }
    }

    override fun setRemoteSdpListener(listener: (SessionDescription) -> Unit) {
        remoteSdpListener = listener
    }

    override fun requestConnection() {
        if (remoteDeviceId.isBlank()) return
        val ref = database.getReference("signaling/$remoteDeviceId/wakeup")
        ref.setValue(System.currentTimeMillis()).addOnSuccessListener {
            Log.d(TAG, "Sent wakeup ping to remote peer: $remoteDeviceId")
        }.addOnFailureListener {
            Log.e(TAG, "Failed to send wakeup ping", it)
        }
    }

    override fun setConnectionRequestListener(listener: () -> Unit) {
        connectionRequestListener = listener
    }

    override fun supportsTrickleIce(): Boolean = true

    override fun onLocalIceCandidateReady(candidate: org.webrtc.IceCandidate) {
        if (localDeviceId.isBlank()) return
        val candidateMap = mapOf(
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "sdp" to candidate.sdp
        )
        val ref = database.getReference("signaling/$localDeviceId/candidates").push()
        ref.setValue(candidateMap).addOnFailureListener {
            Log.e(TAG, "Failed to push ICE candidate", it)
        }
    }

    override fun setRemoteIceCandidateListener(listener: (org.webrtc.IceCandidate) -> Unit) {
        if (remoteDeviceId.isBlank()) return
        val ref = database.getReference("signaling/$remoteDeviceId/candidates")
        ref.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                try {
                    val sdpMid = snapshot.child("sdpMid").getValue(String::class.java) ?: return
                    val sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Int::class.java) ?: return
                    val sdp = snapshot.child("sdp").getValue(String::class.java) ?: return
                    val candidate = org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, sdp)
                    listener.invoke(candidate)
                    snapshot.ref.removeValue() // Consume it immediately
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse remote ICE candidate", e)
                }
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase ICE candidates listen cancelled", error.toException())
            }
        })
    }

    private fun listenForRemoteSdp() {
        if (remoteDeviceId.isBlank()) return
        val expectedType = if (role == DeviceRole.SENDER) "answer" else "offer"
        val ref = database.getReference("signaling/$remoteDeviceId/$expectedType")

        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val base64 = snapshot.getValue(String::class.java)
                if (base64 != null) {
                    try {
                        val envelope = SignalingEnvelope.parseFromBase64String(base64)
                        if (envelope.deviceId == remoteDeviceId) {
                            Log.d(TAG, "Received remote $expectedType from Firebase")
                            remoteSdpListener?.invoke(envelope.toSessionDescription())
                            
                            // Once processed, clear it to avoid reprocessing on reconnect
                            ref.removeValue() 
                        } else {
                            Log.e(TAG, "Device ID mismatch in received SDP")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse remote SDP from Firebase", e)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase listen cancelled", error.toException())
            }
        })
    }

    private fun listenForConnectionRequests() {
        if (localDeviceId.isBlank()) return
        val ref = database.getReference("signaling/$localDeviceId/wakeup")
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    Log.d(TAG, "Received connection request ping from remote peer")
                    connectionRequestListener?.invoke()
                    
                    // Consume the ping
                    ref.removeValue()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase wakeup listen cancelled", error.toException())
            }
        })
    }

    // --- E2EE Fallback Relay Messaging ---

    override fun sendEncryptedRelayMessage(messageId: String, ciphertextBase64: String): Boolean {
        if (remoteDeviceId.isBlank() || messageId.isBlank()) {
            Log.e(TAG, "Cannot send relay message: invalid remoteDeviceId or messageId")
            return false
        }
        val safeMessageId = messageId.replace("/", "_").replace(".", "_")
        try {
            val ref = database.getReference("messages/$remoteDeviceId/inbox/$safeMessageId")
            ref.setValue(ciphertextBase64).addOnSuccessListener {
                Log.d(TAG, "Relay message $safeMessageId sent to $remoteDeviceId")
            }.addOnFailureListener {
                Log.e(TAG, "Failed to send relay message $safeMessageId to $remoteDeviceId", it)
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending relay message $safeMessageId", e)
            return false
        }
    }

    override fun setEncryptedRelayMessageListener(listener: (messageId: String, ciphertextBase64: String, onProcessed: (Boolean) -> Unit) -> Unit) {
        encryptedRelayListener = listener

        // Drain any buffered messages that arrived before listener was attached
        val pending = synchronized(pendingRelayMessages) {
            val list = pendingRelayMessages.toList()
            pendingRelayMessages.clear()
            list
        }

        for ((snapshot, messageId) in pending) {
            val ciphertextBase64 = snapshot.getValue(String::class.java)
            if (ciphertextBase64 != null) {
                deliverRelayMessage(snapshot, messageId, ciphertextBase64, listener)
            }
        }
    }

    private fun listenForRelayMessages() {
        if (localDeviceId.isBlank()) return
        val ref = database.getReference("messages/$localDeviceId/inbox")
        ref.addChildEventListener(object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val messageId = snapshot.key ?: return
                val ciphertextBase64 = snapshot.getValue(String::class.java) ?: return
                Log.d(TAG, "Received incoming encrypted relay message: $messageId")
                
                val listener = encryptedRelayListener
                if (listener != null) {
                    deliverRelayMessage(snapshot, messageId, ciphertextBase64, listener)
                } else {
                    synchronized(pendingRelayMessages) {
                        Log.d(TAG, "Buffering relay message $messageId until listener is registered")
                        pendingRelayMessages.add(snapshot to messageId)
                    }
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildRemoved(snapshot: DataSnapshot) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase relay inbox listener cancelled", error.toException())
            }
        })
    }

    private fun deliverRelayMessage(
        snapshot: DataSnapshot,
        messageId: String,
        ciphertextBase64: String,
        listener: (String, String, (Boolean) -> Unit) -> Unit
    ) {
        try {
            listener.invoke(messageId, ciphertextBase64) { success ->
                if (success) {
                    Log.d(TAG, "Relay message $messageId successfully processed. Consuming from Firebase.")
                    snapshot.ref.removeValue().addOnFailureListener {
                        Log.w(TAG, "Failed to remove consumed relay message $messageId from Firebase", it)
                    }
                } else {
                    Log.w(TAG, "Relay message $messageId processing did not succeed. Preserving in Firebase.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error delivering relay message $messageId", e)
        }
    }
}

package com.example.securesmsforwarder.p2p.relay

import android.util.Log
import com.example.securesmsforwarder.core.domain.DeviceRole
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * Firebase Realtime Database implementation of [RelayTransport].
 *
 * Preserves existing RTDB paths for zero-re-pairing compatibility:
 * - `.info/connected`
 * - `presence/<localDeviceId>` & `presence/<remoteDeviceId>`
 * - `signaling/<localDeviceId>/wakeup` & `signaling/<remoteDeviceId>/wakeup`
 * - `messages/<localDeviceId>/inbox` & `messages/<remoteDeviceId>/inbox/<safeMessageId>`
 *
 * Tracks every registered [ValueEventListener] and [ChildEventListener] so [goOffline]
 * and [close] detach all listeners cleanly without leaks, and surfaces all Firebase
 * read/write errors via [setErrorListener].
 */
class FirebaseRelayTransport(
    private val localDeviceId: String,
    private val remoteDeviceId: String,
    private val role: DeviceRole,
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance()
) : RelayTransport {

    companion object {
        private const val TAG = "FirebaseRelayTransport"
    }

    private val listenerLock = Any()
    private val valueListeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private val childListeners = mutableListOf<Pair<DatabaseReference, ChildEventListener>>()

    @Volatile
    private var listenersAttached = false

    @Volatile
    private var isClosed = false

    @Volatile
    private var isFirebaseConnected = false

    @Volatile
    private var isPeerOnlineCached = false

    @Volatile
    private var peerLastSeenCached = 0L

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var wakeupListener: (() -> Unit)? = null

    @Volatile
    private var encryptedRelayListener: ((String, String, (Boolean) -> Unit) -> Unit)? = null

    @Volatile
    private var relayAvailabilityListener: ((Boolean) -> Unit)? = null

    @Volatile
    private var peerPresenceListener: ((Boolean, Long) -> Unit)? = null

    @Volatile
    private var errorListener: ((String?) -> Unit)? = null

    private val pendingRelayMessages = mutableListOf<Pair<DataSnapshot, String>>()

    init {
        attachListeners()
    }

    private fun attachListeners() {
        synchronized(listenerLock) {
            if (isClosed || listenersAttached) return
            listenersAttached = true
            listenForFirebaseConnectionLocked()
            listenForPeerPresenceLocked()
            listenForWakeupRequestsLocked()
            listenForRelayMessagesLocked()
        }
    }

    private fun detachListeners() {
        synchronized(listenerLock) {
            if (!listenersAttached && valueListeners.isEmpty() && childListeners.isEmpty()) return

            for ((ref, listener) in valueListeners) {
                try {
                    ref.removeEventListener(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove ValueEventListener from ${ref.key}", e)
                }
            }
            valueListeners.clear()

            for ((ref, listener) in childListeners) {
                try {
                    ref.removeEventListener(listener)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove ChildEventListener from ${ref.key}", e)
                }
            }
            childListeners.clear()
            listenersAttached = false
        }
    }

    private fun reportError(message: String?) {
        if (isClosed) return
        if (lastError != message || message != null) {
            lastError = message
            errorListener?.invoke(message)
        }
    }

    private fun listenForFirebaseConnectionLocked() {
        val connectedRef = database.getReference(".info/connected")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || !listenersAttached) return
                val connected = snapshot.getValue(Boolean::class.java) ?: false
                isFirebaseConnected = connected
                Log.d(TAG, "Firebase connection state: $connected")
                if (connected) {
                    reportError(null)
                    updateLocalPresence(online = true)
                }
                relayAvailabilityListener?.invoke(connected)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase .info/connected cancelled: ${error.message}", error.toException())
                reportError("Firebase error: ${error.message}")
            }
        }
        connectedRef.addValueEventListener(listener)
        valueListeners.add(connectedRef to listener)
    }

    private fun updateLocalPresence(online: Boolean) {
        if (localDeviceId.isBlank()) return
        val presenceRef = database.getReference("presence/$localDeviceId")
        val offlinePayload = mapOf(
            "online" to false,
            "lastSeen" to ServerValue.TIMESTAMP,
            "role" to role.name
        )
        if (online) {
            presenceRef.onDisconnect().setValue(offlinePayload).addOnFailureListener { e ->
                Log.e(TAG, "Failed to register onDisconnect presence", e)
                reportError("Firebase error: ${e.message ?: "Write failed"}")
            }
            val onlinePayload = mapOf(
                "online" to true,
                "lastSeen" to ServerValue.TIMESTAMP,
                "role" to role.name
            )
            presenceRef.setValue(onlinePayload).addOnFailureListener { e ->
                Log.e(TAG, "Failed to publish local online presence", e)
                reportError("Firebase error: ${e.message ?: "Write failed"}")
            }
        } else {
            presenceRef.setValue(offlinePayload).addOnFailureListener { e ->
                Log.e(TAG, "Failed to publish local offline presence", e)
                reportError("Firebase error: ${e.message ?: "Write failed"}")
            }
        }
    }

    private fun listenForPeerPresenceLocked() {
        if (remoteDeviceId.isBlank()) return
        val peerPresenceRef = database.getReference("presence/$remoteDeviceId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || !listenersAttached) return
                val isOnline = snapshot.child("online").getValue(Boolean::class.java) ?: false
                val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
                isPeerOnlineCached = isOnline
                peerLastSeenCached = lastSeen
                Log.d(TAG, "Peer presence updated: peer=$remoteDeviceId, online=$isOnline, lastSeen=$lastSeen")
                reportError(null)
                peerPresenceListener?.invoke(isOnline, lastSeen)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Peer presence listener cancelled: ${error.message}", error.toException())
                reportError("Firebase error: ${error.message}")
            }
        }
        peerPresenceRef.addValueEventListener(listener)
        valueListeners.add(peerPresenceRef to listener)
    }

    private fun listenForWakeupRequestsLocked() {
        if (localDeviceId.isBlank()) return
        val ref = database.getReference("signaling/$localDeviceId/wakeup")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (isClosed || !listenersAttached) return
                if (snapshot.exists()) {
                    Log.d(TAG, "Received wakeup ping from remote peer")
                    wakeupListener?.invoke()
                    ref.removeValue().addOnFailureListener { e ->
                        Log.w(TAG, "Failed to consume wakeup ping", e)
                        reportError("Firebase error: ${e.message ?: "Write failed"}")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Firebase wakeup listener cancelled: ${error.message}", error.toException())
                reportError("Firebase error: ${error.message}")
            }
        }
        ref.addValueEventListener(listener)
        valueListeners.add(ref to listener)
    }

    private fun listenForRelayMessagesLocked() {
        if (localDeviceId.isBlank()) return
        val ref = database.getReference("messages/$localDeviceId/inbox")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                if (isClosed || !listenersAttached) return
                val messageId = snapshot.key ?: return
                val ciphertextBase64 = snapshot.getValue(String::class.java) ?: return
                Log.d(TAG, "Received incoming encrypted relay message: $messageId")

                val currentListener = encryptedRelayListener
                if (currentListener != null) {
                    deliverRelayMessage(snapshot, messageId, ciphertextBase64, currentListener)
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
                Log.e(TAG, "Firebase relay inbox listener cancelled: ${error.message}", error.toException())
                reportError("Firebase error: ${error.message}")
            }
        }
        ref.addChildEventListener(listener)
        childListeners.add(ref to listener)
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
                    Log.d(TAG, "Relay message $messageId processed. Consuming from Firebase.")
                    snapshot.ref.removeValue().addOnFailureListener { e ->
                        Log.w(TAG, "Failed to remove consumed relay message $messageId from Firebase", e)
                        reportError("Firebase error: ${e.message ?: "Write failed"}")
                    }
                } else {
                    Log.w(TAG, "Relay message $messageId processing did not succeed. Preserving in Firebase.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error delivering relay message $messageId", e)
        }
    }

    override fun sendEncryptedRelayMessage(messageId: String, ciphertextBase64: String): Boolean {
        if (isClosed || remoteDeviceId.isBlank() || messageId.isBlank()) {
            Log.e(TAG, "Cannot send relay message: invalid state or identifiers")
            return false
        }
        val safeMessageId = messageId.replace("/", "_").replace(".", "_")
        return try {
            val ref = database.getReference("messages/$remoteDeviceId/inbox/$safeMessageId")
            ref.setValue(ciphertextBase64)
                .addOnSuccessListener {
                    Log.d(TAG, "Relay message $safeMessageId sent to $remoteDeviceId")
                    reportError(null)
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "Failed to send relay message $safeMessageId to $remoteDeviceId", e)
                    reportError("Firebase error: ${e.message ?: "Write failed"}")
                }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Exception sending relay message $safeMessageId", e)
            reportError("Firebase error: ${e.message ?: "Write failed"}")
            false
        }
    }

    override fun setEncryptedRelayMessageListener(
        listener: (messageId: String, ciphertextBase64: String, onProcessed: (Boolean) -> Unit) -> Unit
    ) {
        encryptedRelayListener = listener
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

    override fun requestWakeup() {
        if (isClosed || remoteDeviceId.isBlank()) return
        val ref = database.getReference("signaling/$remoteDeviceId/wakeup")
        ref.setValue(System.currentTimeMillis())
            .addOnSuccessListener {
                Log.d(TAG, "Sent wakeup ping to remote peer: $remoteDeviceId")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to send wakeup ping", e)
                reportError("Firebase error: ${e.message ?: "Write failed"}")
            }
    }

    override fun setWakeupListener(listener: () -> Unit) {
        wakeupListener = listener
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

    override fun setErrorListener(listener: (String?) -> Unit) {
        errorListener = listener
        lastError?.let { listener.invoke(it) }
    }

    override fun goOnline() {
        if (isClosed) return
        database.goOnline()
        attachListeners()
        if (isFirebaseConnected) {
            updateLocalPresence(online = true)
        }
    }

    override fun goOffline() {
        if (isClosed) return
        if (localDeviceId.isNotBlank()) {
            updateLocalPresence(online = false)
        }
        detachListeners()
        isFirebaseConnected = false
        isPeerOnlineCached = false
        relayAvailabilityListener?.invoke(false)
        peerPresenceListener?.invoke(false, peerLastSeenCached)
    }

    override fun close() {
        if (isClosed) return
        isClosed = true
        if (localDeviceId.isNotBlank()) {
            updateLocalPresence(online = false)
        }
        detachListeners()
        isFirebaseConnected = false
        isPeerOnlineCached = false
        wakeupListener = null
        encryptedRelayListener = null
        relayAvailabilityListener = null
        peerPresenceListener = null
        errorListener = null
        synchronized(pendingRelayMessages) {
            pendingRelayMessages.clear()
        }
    }
}

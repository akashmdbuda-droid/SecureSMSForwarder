package com.example.securesmsforwarder.p2p.signaling

import android.util.Log
import com.example.securesmsforwarder.core.domain.DeviceRole
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.webrtc.SessionDescription

class FirebaseSignalingProvider(
    private val localDeviceId: String,
    private val remoteDeviceId: String, // The trusted peer we are talking to
    private val role: DeviceRole
) : SignalingProvider {

    private val database = FirebaseDatabase.getInstance()
    private var remoteSdpListener: ((SessionDescription) -> Unit)? = null
    private var connectionRequestListener: (() -> Unit)? = null

    // Sender writes to "signaling/$localDeviceId/offer"
    // Viewer reads from "signaling/$remoteDeviceId/offer" and writes to "signaling/$localDeviceId/answer"

    init {
        listenForRemoteSdp()
        listenForConnectionRequests()
    }

    override fun onLocalSdpReady(sdp: SessionDescription) {
        val envelope = SignalingEnvelope.fromSessionDescription(sdp, localDeviceId)
        val base64 = envelope.toBase64String()

        val type = if (sdp.type == SessionDescription.Type.OFFER) "offer" else "answer"
        val ref = database.getReference("signaling/$localDeviceId/$type")
        ref.setValue(base64).addOnSuccessListener {
            Log.d("FirebaseSignaling", "Local $type SDP pushed to Firebase")
        }.addOnFailureListener {
            Log.e("FirebaseSignaling", "Failed to push local SDP to Firebase", it)
        }
    }

    override fun setRemoteSdpListener(listener: (SessionDescription) -> Unit) {
        remoteSdpListener = listener
    }

    override fun requestConnection() {
        val ref = database.getReference("signaling/$remoteDeviceId/wakeup")
        ref.setValue(System.currentTimeMillis()).addOnSuccessListener {
            Log.d("FirebaseSignaling", "Sent wakeup ping to remote peer")
        }.addOnFailureListener {
            Log.e("FirebaseSignaling", "Failed to send wakeup ping", it)
        }
    }

    override fun setConnectionRequestListener(listener: () -> Unit) {
        connectionRequestListener = listener
    }

    private fun listenForRemoteSdp() {
        val expectedType = if (role == DeviceRole.SENDER) "answer" else "offer"
        val ref = database.getReference("signaling/$remoteDeviceId/$expectedType")

        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val base64 = snapshot.getValue(String::class.java)
                if (base64 != null) {
                    try {
                        val envelope = SignalingEnvelope.parseFromBase64String(base64)
                        if (envelope.deviceId == remoteDeviceId) {
                            Log.d("FirebaseSignaling", "Received remote $expectedType from Firebase")
                            remoteSdpListener?.invoke(envelope.toSessionDescription())
                            
                            // Once processed, we can clear it to avoid reprocessing on reconnect
                            ref.removeValue() 
                        } else {
                            Log.e("FirebaseSignaling", "Device ID mismatch in received SDP")
                        }
                    } catch (e: Exception) {
                        Log.e("FirebaseSignaling", "Failed to parse remote SDP from Firebase", e)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseSignaling", "Firebase listen cancelled", error.toException())
            }
        })
    }

    private fun listenForConnectionRequests() {
        val ref = database.getReference("signaling/$localDeviceId/wakeup")
        ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    Log.d("FirebaseSignaling", "Received connection request ping from remote peer")
                    connectionRequestListener?.invoke()
                    
                    // Consume the ping
                    ref.removeValue()
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseSignaling", "Firebase wakeup listen cancelled", error.toException())
            }
        })
    }
}

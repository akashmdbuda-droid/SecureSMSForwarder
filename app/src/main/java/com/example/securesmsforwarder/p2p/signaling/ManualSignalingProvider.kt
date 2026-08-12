package com.example.securesmsforwarder.p2p.signaling

import android.util.Log
import com.example.securesmsforwarder.core.domain.DeviceRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.SessionDescription

enum class SignalingState {
    IDLE,
    OFFER_GENERATING,
    OFFER_READY,
    ANSWER_PROCESSING,
    ANSWER_GENERATING,
    ANSWER_READY
}

class ManualSignalingProvider(
    private val deviceId: String,
    private val role: DeviceRole
) : SignalingProvider {

    private val _signalingState = MutableStateFlow(
        if (role == DeviceRole.SENDER) SignalingState.OFFER_GENERATING else SignalingState.IDLE
    )
    val signalingState: StateFlow<SignalingState> = _signalingState.asStateFlow()

    private val _localEnvelopeBase64 = MutableStateFlow<String?>(null)
    val localEnvelopeBase64: StateFlow<String?> = _localEnvelopeBase64.asStateFlow()

    private var remoteSdpListener: ((SessionDescription) -> Unit)? = null

    override fun onLocalSdpReady(sdp: SessionDescription) {
        val envelope = SignalingEnvelope.fromSessionDescription(sdp, deviceId)
        _localEnvelopeBase64.tryEmit(envelope.toBase64String())
        
        if (sdp.type == SessionDescription.Type.OFFER) {
            _signalingState.tryEmit(SignalingState.OFFER_READY)
        } else if (sdp.type == SessionDescription.Type.ANSWER) {
            _signalingState.tryEmit(SignalingState.ANSWER_READY)
        }
    }

    override fun setRemoteSdpListener(listener: (SessionDescription) -> Unit) {
        remoteSdpListener = listener
    }

    fun ingestRemoteEnvelope(base64String: String, expectedTrustedDeviceId: String) {
        try {
            val envelope = SignalingEnvelope.parseFromBase64String(base64String)
            
            // Security Check: Verify this SDP actually came from the trusted device!
            if (envelope.deviceId != expectedTrustedDeviceId) {
                Log.e("SignalingProvider", "Mismatch! envelope.deviceId=${envelope.deviceId}, expected=${expectedTrustedDeviceId}")
                throw SecurityException("Envelope device ID [${envelope.deviceId}] does not match trusted peer [${expectedTrustedDeviceId}]")
            }

            // Role Validation Check
            if (role == DeviceRole.SENDER) {
                if (envelope.role != "ANSWER") {
                    throw IllegalArgumentException("Invalid connection request. Sender expects an ANSWER, but received: ${envelope.role}")
                }
                _signalingState.tryEmit(SignalingState.ANSWER_PROCESSING)
            } else if (role == DeviceRole.VIEWER) {
                if (envelope.role != "OFFER") {
                    throw IllegalArgumentException("Invalid connection request. Viewer expects an OFFER, but received: ${envelope.role}")
                }
                _signalingState.tryEmit(SignalingState.ANSWER_GENERATING)
            }

            remoteSdpListener?.invoke(envelope.toSessionDescription())
        } catch (e: Exception) {
            Log.e("ManualSignalingProvider", "Failed to ingest remote envelope: ${e.message}")
            if (role == DeviceRole.SENDER) {
                _signalingState.tryEmit(SignalingState.OFFER_READY)
            } else {
                _signalingState.tryEmit(SignalingState.IDLE)
            }
            throw e
        }
    }
}

package com.example.securesmsforwarder.p2p.webrtc

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.launch
import org.webrtc.*
import org.webrtc.PeerConnection.IceServer
import java.nio.ByteBuffer

/**
 * Handles the direct P2P transport using WebRTC DataChannels.
 * STUN is used for discovery (NAT traversal), but TURN is explicitly disabled 
 * to guarantee no middleman relays our encrypted traffic.
 */
class WebRtcDirectTransport(private val context: Context) {

    companion object {
        private const val TAG = "WebRtcTransport"
        private const val DATA_CHANNEL_LABEL = "secure_sms_channel"
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var dataChannel: DataChannel? = null

    // Flows for signaling
    private val _localSdpFlow = MutableSharedFlow<SessionDescription>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val localSdpFlow: SharedFlow<SessionDescription> = _localSdpFlow

    private val _localIceCandidatesFlow = MutableSharedFlow<IceCandidate>(replay = 50, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val localIceCandidatesFlow: SharedFlow<IceCandidate> = _localIceCandidatesFlow

    // Flow for received raw (encrypted) messages
    private val _incomingMessagesFlow = MutableSharedFlow<ByteArray>(replay = 100, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val incomingMessagesFlow: SharedFlow<ByteArray> = _incomingMessagesFlow

    // Connection state
    private val _connectionStateFlow = MutableSharedFlow<PeerConnection.PeerConnectionState>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val connectionStateFlow: SharedFlow<PeerConnection.PeerConnectionState> = _connectionStateFlow

    init {
        initializeWebRtc()
    }

    private fun initializeWebRtc() {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(true)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun startConnection(isInitiator: Boolean) {
        Log.d(TAG, "startConnection called. isInitiator=$isInitiator")
        dataChannel?.close()
        peerConnection?.close()
        
        // We strictly only allow STUN, never TURN, to comply with the serverless requirement.
        val stunServer = IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        
        val rtcConfig = PeerConnection.RTCConfiguration(listOf(stunServer)).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, createPeerConnectionObserver())

        if (isInitiator) {
            val dcInit = DataChannel.Init().apply {
                ordered = true
                negotiated = false
            }
            dataChannel = peerConnection?.createDataChannel(DATA_CHANNEL_LABEL, dcInit)
            dataChannel?.registerObserver(createDataChannelObserver())

            peerConnection?.createOffer(createSdpObserver { sdp ->
                peerConnection?.setLocalDescription(createSdpObserver(), sdp)
                // Timeout fallback for ICE gathering
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    kotlinx.coroutines.delay(3000)
                    if (peerConnection?.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
                        peerConnection?.localDescription?.let { finalSdp -> _localSdpFlow.tryEmit(finalSdp) }
                    }
                }
            }, MediaConstraints())
        }
    }

    fun setRemoteDescription(sdp: SessionDescription, isRetry: Boolean = false) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                if (sdp.type == SessionDescription.Type.OFFER) {
                    peerConnection?.createAnswer(createSdpObserver { answer ->
                        peerConnection?.setLocalDescription(createSdpObserver(), answer)
                        // Timeout fallback for ICE gathering
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            kotlinx.coroutines.delay(3000)
                            if (peerConnection?.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) {
                                peerConnection?.localDescription?.let { finalSdp -> _localSdpFlow.tryEmit(finalSdp) }
                            }
                        }
                    }, MediaConstraints())
                }
            }
            override fun onCreateFailure(error: String?) { Log.e(TAG, "SDP Create Failure: $error") }
            override fun onSetFailure(error: String?) { 
                Log.e(TAG, "SDP Set Failure: $error. Restarting connection.")
                if (!isRetry) {
                    startConnection(isInitiator = false)
                    setRemoteDescription(sdp, isRetry = true)
                }
            }
        }, sdp)
    }

    fun setRemoteAnswer(sdp: SessionDescription) {
        if (peerConnection?.signalingState() == PeerConnection.SignalingState.STABLE) {
            Log.w(TAG, "Already in stable state, ignoring remote answer to prevent error")
            return
        }
        
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Successfully set remote answer")
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote answer: $error. Restarting connection to generate new offer!")
                // Since we only receive answers when we are the initiator, it's safe to assume true here.
                startConnection(isInitiator = true)
            }
        }, sdp)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun sendMessage(data: ByteArray): Boolean {
        val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
        return dataChannel?.send(buffer) ?: false
    }

    fun suspendConnection() {
        dataChannel?.close()
        peerConnection?.close()
        peerConnection = null
        dataChannel = null
        _connectionStateFlow.tryEmit(PeerConnection.PeerConnectionState.DISCONNECTED)
    }

    fun close() {
        dataChannel?.close()
        peerConnection?.close()
        peerConnectionFactory?.dispose()
        peerConnection = null
        dataChannel = null
        peerConnectionFactory = null
    }

    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(TAG, "Connection State changed: $newState")
                if (newState == PeerConnection.PeerConnectionState.CONNECTED) {
                    if (dataChannel?.state() == DataChannel.State.OPEN) {
                        _connectionStateFlow.tryEmit(newState)
                    } else {
                        Log.d(TAG, "Waiting for DataChannel to open before emitting CONNECTED")
                    }
                } else {
                    _connectionStateFlow.tryEmit(newState)
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "ICE Gathering State: $newState")
                if (newState == PeerConnection.IceGatheringState.COMPLETE) {
                    peerConnection?.localDescription?.let { sdp ->
                        _localSdpFlow.tryEmit(sdp)
                    }
                }
            }
            override fun onIceCandidate(candidate: IceCandidate) {
                _localIceCandidatesFlow.tryEmit(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
            override fun onAddStream(stream: MediaStream) {}
            override fun onRemoveStream(stream: MediaStream) {}
            override fun onDataChannel(channel: DataChannel) {
                dataChannel = channel
                dataChannel?.registerObserver(createDataChannelObserver())
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {}
        }
    }

    private fun createDataChannelObserver(): DataChannel.Observer {
        return object : DataChannel.Observer {
            override fun onBufferedAmountChange(amount: Long) {}

            override fun onStateChange() {
                val state = dataChannel?.state()
                Log.d(TAG, "DataChannel State changed: $state")
                if (state == DataChannel.State.OPEN) {
                    _connectionStateFlow.tryEmit(PeerConnection.PeerConnectionState.CONNECTED)
                } else if (state == DataChannel.State.CLOSED || state == DataChannel.State.CLOSING) {
                    _connectionStateFlow.tryEmit(PeerConnection.PeerConnectionState.DISCONNECTED)
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                Log.d(TAG, "DataChannel Received Message: ${data.size} bytes")
                _incomingMessagesFlow.tryEmit(data)
            }
        }
    }

    private fun createSdpObserver(onSuccess: ((SessionDescription) -> Unit)? = null): SdpObserver {
        return object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                onSuccess?.invoke(sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {
                Log.e(TAG, "SDP Create Failure: $error")
            }
            override fun onSetFailure(error: String) {
                Log.e(TAG, "SDP Set Failure: $error")
            }
        }
    }
}

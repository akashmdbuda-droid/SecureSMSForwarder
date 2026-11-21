package com.example.securesmsforwarder.p2p

/**
 * Reactive UI state representing the E2EE Firebase Relay connection,
 * remote peer presence, manual pause status, and actionable transport errors.
 */
data class ConnectionUiState(
    val status: String = "DISCONNECTED",
    val isRelayConnected: Boolean = false,
    val isPeerOnline: Boolean = false,
    val peerLastSeen: Long = 0L,
    val isPaused: Boolean = false,
    val lastError: String? = null
)

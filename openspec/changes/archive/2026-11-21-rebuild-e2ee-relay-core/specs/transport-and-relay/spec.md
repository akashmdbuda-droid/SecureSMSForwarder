# Spec Delta

## REMOVED Requirements

### Requirement: Dual-tier encrypted message transport
**Reason:** WebRTC, Google STUN, and public Metered.ca TURN servers caused 5 consecutive regressions (`v1.0.19`–`v1.0.23`), drained battery, leaked IPs to third parties, and are unnecessary for ~160-byte SMS payloads.
**Migration:** Replaced by `Pure E2EE Firebase Relay transport` over HTTPS/WSS port 443 using identical Tink HPKE encryption (`SecureSmsProtocol_V1`) and Firebase RTDB inbox paths.

## MODIFIED Requirements

### Requirement: Real-time peer presence tracking
The system SHALL publish local online presence and observe the paired peer's presence to report accurate connection status and trigger catch-up sync.

#### Scenario: Peer comes online while messages are pending or failed
- **WHEN** `presence/<remoteDeviceId>/online` transitions to `true` and connection is not paused
- **THEN** `TransportManager` updates connection status to `CONNECTED (Encrypted Relay)`, requeues failed messages, and drains the pending queue

#### Scenario: Relay connected while peer is offline
- **WHEN** Firebase `.info/connected` is `true` and peer presence `online` is `false`
- **THEN** `TransportManager` reports `RELAY STANDBY (Peer Offline)` rather than `CONNECTED`

### Requirement: Manual pause and wakeup sync
The system SHALL allow users to pause connectivity to conserve battery and guarantee that tapping Connect or receiving a wakeup ping unpauses, ensures the foreground service is active, and triggers immediate synchronization.

#### Scenario: User pauses connection
- **WHEN** `isConnectionPaused` is set to `true`
- **THEN** `TransportManager` pauses periodic queue draining, closes active relay listeners or marks local presence offline, and reports `PAUSED`

#### Scenario: User taps Connect while paused or after service death
- **WHEN** the user taps Connect on a paired device
- **THEN** the app clears `isConnectionPaused = false`, starts `ForwardingService` with `ACTION_CONNECT` if not running, unpauses `TransportManager` directly, brings Firebase online, sends a wakeup ping, and drains pending messages

#### Scenario: Remote peer sends wakeup ping
- **WHEN** a device receives a write on `signaling/<localDeviceId>/wakeup` while unpaused
- **THEN** it requeues failed messages, drains the pending queue, and deletes the wakeup node

## ADDED Requirements

### Requirement: Pure E2EE Firebase Relay transport
The system SHALL transmit all encrypted SMS, ACK, and WIPE payloads exclusively through the End-to-End Encrypted Firebase Realtime Database Relay (HTTPS/WSS port 443) without WebRTC, third-party STUN, or public TURN servers.

#### Scenario: Encrypted SMS dispatch via Firebase Relay
- **WHEN** a queued message is dispatched to a paired peer
- **THEN** `TransportManager` encrypts the JSON payload using Tink HPKE (`SecureSmsProtocol_V1`), Base64-encodes the ciphertext, and writes it to `messages/<remoteDeviceId>/inbox/<safeMessageId>`

#### Scenario: No third-party STUN/TURN or WebRTC traffic
- **WHEN** the application starts, pairs, connects, or forwards messages
- **THEN** the system initializes zero WebRTC PeerConnections and makes zero connections to STUN or public TURN servers

### Requirement: Clean relay listener teardown on service restart
The system SHALL detach all Firebase Realtime Database listeners and cancel transport-scoped coroutines whenever `ForwardingService` restarts, pauses, or is destroyed.

#### Scenario: Service restarts after pairing or unpairing
- **WHEN** `ForwardingService` handles `ACTION_RESTART` or `onDestroy`
- **THEN** `RelayTransport.close()` removes every registered `ValueEventListener` and `ChildEventListener` and cancels prior collectors before creating a new transport instance

### Requirement: Multi-network-safe Firebase offline detection
The system SHALL only call `FirebaseDatabase.getInstance().goOffline()` when no active network retains validated internet capability.

#### Scenario: Device switches from Wi-Fi to cellular data
- **WHEN** `ConnectivityManager.NetworkCallback.onLost(wifiNetwork)` fires while cellular remains the active internet-capable network
- **THEN** `ForwardingService` keeps Firebase online instead of forcing `goOffline()`

### Requirement: Serialized queue draining
The system SHALL serialize `TransportManager.drainQueue()` executions using a mutual-exclusion lock so concurrent triggers cannot dispatch duplicate copies of the same message or race `retryCount`.

#### Scenario: Concurrent retry loop and presence wakeup triggers
- **WHEN** `drainQueue()` is invoked simultaneously by the retry loop and a peer-online event
- **THEN** only one coroutine reads and dispatches pending messages at a time while overlapping triggers coalesce safely

### Requirement: Visible Firebase and transport error diagnostics
The system SHALL capture Firebase `DatabaseError` and write failures in reactive connection state so users see actionable errors (such as `Permission denied` from expired database rules) in the UI instead of silent log-only failures.

#### Scenario: Firebase denies read or write access
- **WHEN** any Firebase RTDB listener invokes `onCancelled(error)` or a relay write fails
- **THEN** `RelayTransport` reports the error message into `ConnectionUiState.lastError` so the dashboard displays the failure clearly

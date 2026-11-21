# transport-and-relay Specification

## Purpose
Defines the dual-tier P2P and encrypted fallback relay transport layer that connects a paired Sender and Viewer across arbitrary NATs, firewalls, and mobile networks.

## Requirements

### Requirement: Dual-tier encrypted message transport
The system SHALL transmit encrypted payloads using Tier 1 (Direct WebRTC DataChannel) when open, and automatically fall back to Tier 2 (Firebase Realtime Database E2EE Relay) when Tier 1 is unavailable.

#### Scenario: Direct WebRTC delivery succeeds
- **WHEN** the WebRTC DataChannel state is `OPEN` and a queued message is dispatched
- **THEN** `TransportManager` sends the HPKE ciphertext directly over the DataChannel without writing to Firebase Relay

#### Scenario: Automatic fallback to E2EE Firebase Relay
- **WHEN** the WebRTC DataChannel is not `OPEN` and a paired peer exists
- **THEN** `TransportManager` Base64-encodes the HPKE ciphertext and writes it to `messages/<remoteDeviceId>/inbox/<safeMessageId>`

### Requirement: Safe relay delivery acknowledgment
The system SHALL preserve incoming relay messages in Firebase Realtime Database until local decryption and Room database persistence succeed.

#### Scenario: Successful relay message consumption
- **WHEN** a Viewer receives a relay inbox item and `processIncomingCiphertext` returns `true`
- **THEN** `FirebaseSignalingProvider` deletes that item from `messages/<localDeviceId>/inbox/<messageId>` and sends an encrypted `ACK` back to the Sender

#### Scenario: Failed decryption or persistence preserves relay message
- **WHEN** `processIncomingCiphertext` returns `false` or throws an exception
- **THEN** `FirebaseSignalingProvider` leaves the relay inbox item intact in Firebase Realtime Database for later retry

### Requirement: Real-time peer presence tracking
The system SHALL publish local online presence and observe the paired peer's presence to report accurate connection status and trigger catch-up sync.

#### Scenario: Peer comes online while messages are pending or failed
- **WHEN** `presence/<remoteDeviceId>/online` transitions to `true` and connection is not paused
- **THEN** `TransportManager` updates connection status to `CONNECTED (Encrypted Relay)` (or `CONNECTED (Direct P2P)` if WebRTC is open), requeues failed messages, and drains the pending queue

#### Scenario: Relay connected while peer is offline
- **WHEN** Firebase `.info/connected` is `true`, WebRTC is not connected, and peer presence `online` is `false`
- **THEN** `TransportManager` reports `RELAY STANDBY (Peer Offline)` rather than `CONNECTED`

### Requirement: Manual pause and wakeup sync
The system SHALL allow users to pause connectivity to conserve battery and resume or trigger wakeup synchronization on demand.

#### Scenario: User pauses connection
- **WHEN** `isConnectionPaused` is set to `true`
- **THEN** `TransportManager` cancels pending reconnect/timeout jobs, suspends WebRTC, stops periodic queue draining, and reports `PAUSED`

#### Scenario: Remote peer sends wakeup ping
- **WHEN** a device receives a write on `signaling/<localDeviceId>/wakeup` while unpaused
- **THEN** it restarts WebRTC negotiation if disconnected, requeues failed messages, drains the queue, and deletes the wakeup node

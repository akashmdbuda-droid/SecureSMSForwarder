# crypto-and-pairing Specification

## Purpose
Defines device cryptographic identity, end-to-end payload encryption, QR-based mutual device pairing, biometric gating, and trust revocation.

## Requirements

### Requirement: Hardware-backed device identity keyset
The system SHALL generate and persist a long-term X25519 HPKE identity keyset backed by Android Keystore.

#### Scenario: First launch identity creation
- **WHEN** `KeyManager.getOrGenerateIdentityKeyset()` is invoked for the first time
- **THEN** Tink generates a `DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305` keyset encrypted with `android-keystore://secure_sms_master_key` and computes a 12-hex-character SHA-256 fingerprint of its serialized public key

### Requirement: Application-level E2EE payload encryption
The system SHALL encrypt every SMS, ACK, and WIPE JSON payload using Tink Hybrid Encryption bound to `SecureSmsProtocol_V1`.

#### Scenario: Sender encrypts SMS payload for paired Viewer
- **WHEN** `MessageEncryption.encrypt` is called with the serialized JSON payload and the Viewer's public `KeysetHandle`
- **THEN** the output ciphertext can only be decrypted by the Viewer's private identity keyset with matching `contextInfo = "SecureSmsProtocol_V1"`

### Requirement: Mutual QR pairing and biometric protection
The system SHALL gate pairing, unpairing, message wiping, and role reset behind biometric/device-credential authentication and complete bidirectional key exchange via QR + Firebase pairing request.

#### Scenario: User scans peer QR code
- **WHEN** Device A scans Device B's public key QR code
- **THEN** Device A stores Device B in `TrustStore`, restarts `ForwardingService`, and writes Device A's public key to `pairing_requests/<peerFingerprint>/<localDeviceId>` so Device B automatically trusts Device A and deletes the pairing request

#### Scenario: Unauthorized WIPE command rejected by Sender
- **WHEN** a Sender device receives a `WIPE` payload from a peer
- **THEN** `TransportManager` ignores the `WIPE` command and preserves the Sender's local database

### Requirement: Backward-compatible existing pairing preservation
The system SHALL preserve existing `TrustStore` (`secure_sms_trust_store`) peer records and `KeyManager` (`device_identity_keyset`) X25519 identity keysets across upgrade to `v1.1.0` so already-paired devices remain paired without re-scanning QR codes.

#### Scenario: Paired user upgrades from v1.0.23 to v1.1.0
- **WHEN** an existing Sender or Viewer upgrades to `v1.1.0`
- **THEN** `KeyManager` loads the existing Android Keystore-backed identity keyset and `TrustStore` retains the paired `peer_device_id`, `peer_public_keyset`, and custom `peer_name`

### Requirement: Disable Android cloud backup
The system SHALL disable Android Auto Backup (`android:allowBackup="false"`) in `AndroidManifest.xml` so Room databases and SharedPreferences are never backed up to external cloud storage.

#### Scenario: Manifest backup configuration
- **WHEN** the application is built and installed
- **THEN** `AndroidManifest.xml` declares `android:allowBackup="false"`

### Requirement: Reject legacy non-JSON wire payloads
The system SHALL accept only Tink HPKE-encrypted structured JSON payloads (`SMS`, `ACK`, `WIPE`) and drop malformed or legacy plain-text control payloads.

#### Scenario: Legacy plain-text control string arrives
- **WHEN** a decrypted payload is not valid JSON with a recognized `type` (`SMS`, `ACK`, or `WIPE`)
- **THEN** `TransportManager` rejects the payload without modifying local state

### Requirement: Teardown transport on peer revocation and role reset
The system SHALL immediately reconfigure or stop `ForwardingService` when the user unpairs a device or resets the device role.

#### Scenario: User unpairs trusted device
- **WHEN** the user confirms biometric authentication to remove trust
- **THEN** `TrustStore.revokePeer()` clears trust state and `ForwardingService` restarts with `ACTION_RESTART` so relay listeners for the revoked peer are closed

#### Scenario: User resets device role to UNASSIGNED
- **WHEN** the user confirms biometric authentication to reset role
- **THEN** `MainActivity` stops `ForwardingService` and returns to `SetupScreen`

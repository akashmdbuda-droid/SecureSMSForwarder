# Spec Delta

## ADDED Requirements

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

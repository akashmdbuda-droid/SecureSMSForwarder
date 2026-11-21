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

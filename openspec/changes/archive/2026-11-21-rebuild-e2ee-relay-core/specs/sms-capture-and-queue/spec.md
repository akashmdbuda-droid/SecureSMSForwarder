# Spec Delta

## ADDED Requirements

### Requirement: At-rest encryption for locally stored messages
The system SHALL encrypt SMS message bodies at rest using Tink AEAD backed by Android Keystore before persisting them in Room, while supporting backward-compatible decryption of any pre-existing plaintext rows from earlier versions.

#### Scenario: New SMS persisted locally
- **WHEN** `MessageQueueManager.enqueueMessage` or `insertViewerMessage` stores an SMS body in `MessageEntity.encryptedPayload`
- **THEN** `LocalStorageCipher.encrypt()` encrypts the UTF-8 bytes with a versioned prefix header using Android Keystore Tink AEAD so raw SQLite storage contains no plaintext SMS text

#### Scenario: Reading legacy plaintext rows after upgrading from v1.0.23
- **WHEN** `LocalStorageCipher.decrypt()` reads a `MessageEntity.encryptedPayload` written before `v1.1.0` without the AEAD header
- **THEN** it falls back safely to UTF-8 decoding so existing queued and inbox messages remain readable without data loss

### Requirement: Outbound queue displays failed messages for retry
The system SHALL expose `FAILED` outbound messages alongside pending messages in the Sender Queue view without attempting automatic background transmission on `FAILED` items until requeued.

#### Scenario: Message exhausts 25 retries
- **WHEN** a Sender message transitions to `MessageState.FAILED`
- **THEN** `MessageDao.getSenderQueueMessages()` includes the item so `SenderQueueTab` displays its `FAILED` badge and enables the `Retry (<count>)` button, while `TransportManager.drainQueue()` skips `FAILED` items until requeued

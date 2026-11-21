# sms-capture-and-queue Specification

## Purpose
Defines reliable SMS interception, deduplication, persistent Room queuing, exponential-backoff retries, and historical inbox synchronization on the Sender device.

## Requirements

### Requirement: Dual SMS capture with deduplication
The system SHALL capture incoming SMS messages via both `SmsReceiver` (BroadcastReceiver) and `SmsObserver` (ContentObserver) without forwarding duplicate entries.

#### Scenario: SMS arrives while device is in Doze mode
- **WHEN** Android delivers `SMS_RECEIVED` to `SmsReceiver`
- **THEN** `SmsReceiver` acquires a bounded `PARTIAL_WAKE_LOCK`, uses `goAsync()`, parses multipart PDUs via `SmsParser`, enqueues each SMS in Room, and triggers an immediate queue drain via `ForwardingService`

#### Scenario: Duplicate capture from receiver and content observer
- **WHEN** `SmsObserver` observes an inbox row with matching sender and payload within ±30 seconds of an already-enqueued message
- **THEN** `MessageQueueManager.enqueueMessage` skips insertion to prevent duplicate forwarding

### Requirement: Crash-safe Room queue with exponential backoff
The system SHALL persist queued SMS messages in Room (`AppDatabase`) and retry unacknowledged transmissions with capped exponential backoff until `ACK` receipt or retry exhaustion.

#### Scenario: Unacknowledged message retries with capped backoff
- **WHEN** a message is dispatched without receiving an `ACK`
- **THEN** `MessageQueueManager.markTransmitting` increments `retryCount` and schedules `nextRetryTime` using `min(60_000ms, 2^min(retryCount, 5) * 2000ms)`

#### Scenario: Retry limit exhaustion and manual requeue
- **WHEN** a message exceeds 25 transmission attempts
- **THEN** `MessageQueueManager` transitions its state to `FAILED`, and calling `requeueFailedMessages()` resets all `FAILED` messages back to `QUEUED` with `retryCount = 0`

#### Scenario: Viewer acknowledges receipt
- **WHEN** the Sender receives an encrypted `ACK` containing `messageId`
- **THEN** `MessageQueueManager.markAcknowledged` transitions the message to `DELIVERED` so it is no longer retried

### Requirement: On-demand historical SMS sync
The system SHALL allow the Sender user to enqueue historical SMS messages from a chosen date range.

#### Scenario: User syncs historical date range
- **WHEN** the Sender user selects a start and end timestamp with `READ_SMS` permission granted
- **THEN** `SmsHistoryScanner.syncHistory` queries `content://sms/inbox` in ascending date order, enqueues non-duplicate messages, and triggers a queue drain

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

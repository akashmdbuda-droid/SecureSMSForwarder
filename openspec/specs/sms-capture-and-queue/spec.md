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

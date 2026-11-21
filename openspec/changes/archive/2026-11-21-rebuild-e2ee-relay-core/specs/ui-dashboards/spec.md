# Spec Delta

## ADDED Requirements

### Requirement: Reactive connection state propagation across service restarts
The system SHALL expose a stable singleton `StateFlow<ConnectionUiState>` from `ForwardingService` so Compose UI always observes live connection status, peer online state, and diagnostic errors across cold starts and `ACTION_RESTART` cycles.

#### Scenario: Cold start or pairing restart updates dashboard immediately
- **WHEN** `ForwardingService` initializes or restarts its transport asynchronously after `MainActivity` renders
- **THEN** the dashboard immediately updates from `DISCONNECTED` to the live relay status without requiring an app restart

### Requirement: Singleton preferences without listener garbage collection
The system SHALL host `SettingsManager`, `TrustStore`, `RoleManager`, and `MessageQueueManager` in an `Application`-scoped `AppContainer` so `SharedPreferences` change listeners remain strongly referenced for the process lifetime.

#### Scenario: Pause toggle updates service reliably
- **WHEN** the user toggles Disconnect, Connect, or Pause Connection from any screen
- **THEN** the singleton `SettingsManager.connectionPausedFlow` reliably notifies `ForwardingService` without listener eviction

### Requirement: Sender standby controls parity and side-effect-safe read tracking
The system SHALL display Disconnect/Reconnect controls on `SenderMainTab` during `RELAY STANDBY (Peer Offline)`, display any active transport/Firebase error banner clearly, and mark Viewer messages as read via Compose side effects rather than during composition.

#### Scenario: Sender is in relay standby while Viewer is offline
- **WHEN** `connectionStatus` contains `Peer Offline` on `SenderMainTab`
- **THEN** the dashboard shows `Disconnect` and `Reconnect` controls matching `ViewerMainTab`

#### Scenario: Viewer views unread messages in a thread
- **WHEN** `ViewerInboxTab` displays an unread message card
- **THEN** `onMessageClick(msg.id)` runs inside `LaunchedEffect(msg.id)` so Room is updated once per unread message

#### Scenario: Firebase error banner shown on dashboard
- **WHEN** `ConnectionUiState.lastError` is non-null
- **THEN** the dashboard displays the diagnostic error message so the user can troubleshoot connectivity or Firebase rules

### Requirement: Incoming SMS notification preference enforcement
The system SHALL honor `SettingsManager.isNotificationsEnabled` (defaulting to `true`) before posting incoming SMS notifications on the Viewer device.

#### Scenario: User disables Notifications in Settings
- **WHEN** `SettingsManager.isNotificationsEnabled` is `false` and a new SMS arrives on the Viewer
- **THEN** `ForwardingService` persists the message to Room without posting a status-bar notification

# ui-dashboards Specification

## Purpose
Defines the Jetpack Compose user interfaces for initial role setup, Sender dashboard, Viewer threaded inbox, connection metrics, security controls, and preferences.

## Requirements

### Requirement: Role-based dashboard routing
The system SHALL route the user to `SetupScreen` when `DeviceRole.UNASSIGNED`, `SenderDashboardScreen` when `DeviceRole.SENDER`, and `ViewerDashboardScreen` when `DeviceRole.VIEWER`.

#### Scenario: Initial role assignment starts background service
- **WHEN** the user selects `SENDER` or `VIEWER` on `SetupScreen`
- **THEN** `MainActivity` persists the role in `RoleManager`, switches to the corresponding dashboard, and starts `ForwardingService` as a foreground service

### Requirement: Sender dashboard controls and queue visibility
The system SHALL display live connection status, Connect/Disconnect/Reconnect controls, a Test SMS trigger, and the outbound message queue with retry and wipe actions.

#### Scenario: User disconnects manually from dashboard
- **WHEN** the user taps `Disconnect`
- **THEN** `SettingsManager.isConnectionPaused` is set to `true`, transport pauses, and dashboard status displays `PAUSED`

### Requirement: Viewer threaded inbox and unread tracking
The system SHALL display forwarded messages grouped by forwarding device and sender phone number, showing unread counts and marking messages read when opened.

#### Scenario: Viewer opens a sender thread
- **WHEN** the Viewer user taps a sender conversation card in `ViewerInboxTab`
- **THEN** the app displays messages chronologically grouped by date and marks unread messages in that thread as read in Room

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

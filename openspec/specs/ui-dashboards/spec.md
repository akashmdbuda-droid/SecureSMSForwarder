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

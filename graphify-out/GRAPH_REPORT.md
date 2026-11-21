# Graph Report - SecureSMSForwarder  (2026-11-21)

## Corpus Check
- 72 files · ~30,904 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 23 file(s) not represented in the graph (top: .xml 11, (none) 5, .properties 2)
- WARNING: 23 of 106 files seen (22%) have no supported extractor and are missing from this graph entirely. It may look complete while representing only part of the codebase.

## Summary
- 914 nodes · 1849 edges · 62 communities (32 shown, 30 thin omitted)
- Extraction: 84% EXTRACTED · 16% INFERRED · 0% AMBIGUOUS · INFERRED: 299 edges (avg confidence: 0.95)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `cf891232`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- FirebaseRelayTransport
- AppUpdater.kt
- Requirements
- MessageEntity
- steps
- RelayTransport
- MessageDao
- steps
- ForwardingService
- 2. Application Singleton Container (`SecureSmsApplication` + `AppContainer`)
- TransportManager
- QrPairingScreen.kt
- SharedComponents.kt
- LocalStorageCipherTest
- FakeMessageDao
- Requirements
- MainActivity
- MessageState
- DeviceRole
- Requirement: Manual pause and wakeup sync
- SenderDashboardScreen.kt
- MessageQueueManagerTest
- KeyManager
- TrustStoreTest.kt
- Scope (In-Scope)
- ADDED Requirements
- MessageQueueManager
- Requirements
- ADDED Requirements
- MessageEncryptionFuzzTest
- BiometricHelper.kt
- TrustStore
- rules
- Theme.kt
- ADDED Requirements
- Project Rules & Engineering Workflow
- MainActivity.kt
- .onCreate
- Core Design Goals
- ADDED Requirements
- ADDED Requirements
- SmsObserver.kt
- AppContainer
- SmsParserTest.kt
- ExampleInstrumentedTest.kt
- NetworkCallback
- ViewerDashboardScreen.kt
- Requirements
- ExampleUnitTest.kt
- superpowers-workflow.md
- feature-lifecycle.md

## God Nodes (most connected - your core abstractions)
1. `ForwardingService` - 59 edges
2. `MessageEntity` - 51 edges
3. `TransportManager` - 48 edges
4. `MessageQueueManager` - 38 edges
5. `FirebaseRelayTransport` - 36 edges
6. `TrustStore` - 36 edges
7. `AppContainer` - 29 edges
8. `FakeRelayTransport` - 29 edges
9. `RelayTransport` - 27 edges
10. `2. Completed Milestones (`v1.1.0`)` - 26 edges

## Surprising Connections (you probably didn't know these)
- `Scenario: Device switches from Wi-Fi to cellular data` --references--> `ForwardingService`  [INFERRED]
  openspec/changes/archive/2026-11-21-rebuild-e2ee-relay-core/specs/transport-and-relay/spec.md → app/src/main/java/com/example/securesmsforwarder/background/ForwardingService.kt
- `Scenario: Pause toggle updates service reliably` --references--> `ForwardingService`  [INFERRED]
  openspec/changes/archive/2026-11-21-rebuild-e2ee-relay-core/specs/ui-dashboards/spec.md → app/src/main/java/com/example/securesmsforwarder/background/ForwardingService.kt
- `Scenario: User disables Notifications in Settings` --references--> `ForwardingService`  [INFERRED]
  openspec/changes/archive/2026-11-21-rebuild-e2ee-relay-core/specs/ui-dashboards/spec.md → app/src/main/java/com/example/securesmsforwarder/background/ForwardingService.kt
- `Scenario: Device switches from Wi-Fi to cellular data` --references--> `ForwardingService`  [INFERRED]
  openspec/specs/transport-and-relay/spec.md → app/src/main/java/com/example/securesmsforwarder/background/ForwardingService.kt
- `Scenario: Pause toggle updates service reliably` --references--> `ForwardingService`  [INFERRED]
  openspec/specs/ui-dashboards/spec.md → app/src/main/java/com/example/securesmsforwarder/background/ForwardingService.kt

## Import Cycles
- None detected.

## Communities (62 total, 30 thin omitted)

### Community 0 - "FirebaseRelayTransport"
Cohesion: 0.08
Nodes (5): FirebaseRelayTransport, ValueEventListener, ValueEventListener, ChildEventListener, ValueEventListener

### Community 1 - "AppUpdater.kt"
Cohesion: 0.06
Nodes (19): BootReceiver, ReleaseNote, AppUpdater, BroadcastReceiver, UpdateInfo, 1. Current Version Snapshot, 3. Version Synchronization Checklist, Version Synchronization Notes (+11 more)

### Community 2 - "Requirements"
Cohesion: 0.15
Nodes (12): crypto-and-pairing Specification, Purpose, Requirement: Disable Android cloud backup, Requirement: Hardware-backed device identity keyset, Requirement: Mutual QR pairing and biometric protection, Requirement: Reject legacy non-JSON wire payloads, Requirements, Scenario: First launch identity creation (+4 more)

### Community 5 - "steps"
Cohesion: 0.11
Nodes (17): runs-on, steps, jobs, build-and-test, name, on, pull_request, push (+9 more)

### Community 6 - "RelayTransport"
Cohesion: 0.12
Nodes (3): RelayTransport, Requirement: Visible Firebase and transport error diagnostics, Scenario: Firebase denies read or write access

### Community 8 - "steps"
Cohesion: 0.08
Nodes (23): permissions, runs-on, steps, GITHUB_TOKEN, KEYSTORE_B64, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD, SIGNING_STORE_PASSWORD (+15 more)

### Community 9 - "ForwardingService"
Cohesion: 0.12
Nodes (15): ForwardingService, LocalBinder, Requirement: Teardown transport on peer revocation and role reset, Scenario: User resets device role to UNASSIGNED, Scenario: User unpairs trusted device, Scenario: Service restarts after pairing or unpairing, Requirement: Reactive connection state propagation across service restarts, Scenario: Cold start or pairing restart updates dashboard immediately (+7 more)

### Community 12 - "2. Application Singleton Container (`SecureSmsApplication` + `AppContainer`)"
Cohesion: 0.17
Nodes (6): SecureSmsApplication, SmsHistoryScanner, SmsReceiver, 2. Application Singleton Container (`SecureSmsApplication` + `AppContainer`), Requirement: Dual SMS capture with deduplication, Scenario: SMS arrives while device is in Doze mode

### Community 13 - "TransportManager"
Cohesion: 0.16
Nodes (9): TransportManager, 2. Completed Milestones (`v1.1.0`), 4. Known Issues & Audited Bug Status, Open / Deferred Enhancements, 6. `TransportManager` Concurrency & Strict Framing, Context, Lane B — Pure E2EE Firebase Relay Transport & Coordinator (Parallel Wave 1), Requirement: Serialized queue draining (+1 more)

### Community 18 - "Requirements"
Cohesion: 0.11
Nodes (18): Purpose, Requirement: Manual pause and wakeup sync, Requirement: Multi-network-safe Firebase offline detection, Requirement: Pure E2EE Firebase Relay transport, Requirement: Real-time peer presence tracking, Requirement: Safe relay delivery acknowledgment, Requirements, Scenario: Device switches from Wi-Fi to cellular data (+10 more)

### Community 19 - "MainActivity"
Cohesion: 0.32
Nodes (7): MainActivity, 3. Pure E2EE Firebase Relay (`RelayTransport` + `FirebaseRelayTransport`), 4. Reactive `ConnectionUiState` & Guaranteed Connect Flow, 7. Queue Visibility, UI Polish & CI Verification, Detailed Design Decisions, Requirement: Role-based dashboard routing, Scenario: Initial role assignment starts background service

### Community 20 - "MessageState"
Cohesion: 0.14
Nodes (9): MessageState, ACKNOWLEDGED, DELIVERED, ENCRYPTED, FAILED, PERSISTED_BY_B, QUEUED, RECEIVED (+1 more)

### Community 21 - "DeviceRole"
Cohesion: 0.20
Nodes (5): DeviceRole, SENDER, UNASSIGNED, VIEWER, SetupScreen()

### Community 22 - "Requirement: Manual pause and wakeup sync"
Cohesion: 0.17
Nodes (11): MODIFIED Requirements, REMOVED Requirements, Requirement: Dual-tier encrypted message transport, Requirement: Manual pause and wakeup sync, Requirement: Real-time peer presence tracking, Scenario: Peer comes online while messages are pending or failed, Scenario: Relay connected while peer is offline, Scenario: Remote peer sends wakeup ping (+3 more)

### Community 23 - "SenderDashboardScreen.kt"
Cohesion: 0.33
Nodes (11): SettingsManager, ConnectionTab(), ErrorDiagnosticBanner(), MetricRow(), SecurityTab(), SenderDashboardScreen(), SenderMainTab(), SenderQueueTab() (+3 more)

### Community 24 - "MessageQueueManagerTest"
Cohesion: 0.14
Nodes (3): ParsedSms, SmsParser, MessageQueueManagerTest

### Community 25 - "KeyManager"
Cohesion: 0.31
Nodes (3): KeyManager, Requirement: Backward-compatible existing pairing preservation, Scenario: Paired user upgrades from v1.0.23 to v1.1.0

### Community 27 - "Scope (In-Scope)"
Cohesion: 0.18
Nodes (10): Capabilities, Impact, Modified Capabilities, New Capabilities, Non-Goals, Proposal: Rebuild Core Transport as Pure E2EE Firebase Relay & Harden Security, Scope (In-Scope), Target Release Impact (+2 more)

### Community 28 - "ADDED Requirements"
Cohesion: 0.18
Nodes (11): ADDED Requirements, Requirement: Clean relay listener teardown on service restart, Requirement: Multi-network-safe Firebase offline detection, Requirement: Pure E2EE Firebase Relay transport, Requirement: Serialized queue draining, Requirement: Visible Firebase and transport error diagnostics, Scenario: Concurrent retry loop and presence wakeup triggers, Scenario: Device switches from Wi-Fi to cellular data (+3 more)

### Community 29 - "MessageQueueManager"
Cohesion: 0.07
Nodes (12): MessageQueueManager, MessageEncryption, TransportManagerTest, FakeRelayTransport, FakeStorageCipher, PrefixedPrefsContext, Requirement: Application-level E2EE payload encryption, Scenario: Sender encrypts SMS payload for paired Viewer (+4 more)

### Community 30 - "Requirements"
Cohesion: 0.14
Nodes (13): Purpose, Requirement: Incoming SMS notification preference enforcement, Requirement: Sender dashboard controls and queue visibility, Requirement: Sender standby controls parity and side-effect-safe read tracking, Requirement: Viewer threaded inbox and unread tracking, Requirements, Scenario: Firebase error banner shown on dashboard, Scenario: Sender is in relay standby while Viewer is offline (+5 more)

### Community 31 - "ADDED Requirements"
Cohesion: 0.22
Nodes (8): ADDED Requirements, Requirement: Incoming SMS notification preference enforcement, Requirement: Sender standby controls parity and side-effect-safe read tracking, Scenario: Firebase error banner shown on dashboard, Scenario: Sender is in relay standby while Viewer is offline, Scenario: User disables Notifications in Settings, Scenario: Viewer views unread messages in a thread, Spec Delta

### Community 35 - "TrustStore"
Cohesion: 0.20
Nodes (3): TrustStore, Requirement: Backward-compatible existing pairing preservation, Scenario: Paired user upgrades from v1.0.23 to v1.1.0

### Community 36 - "rules"
Cohesion: 0.15
Nodes (12): guidance, guidance, context, operations, apply, archive, rules, design (+4 more)

### Community 39 - "ADDED Requirements"
Cohesion: 0.25
Nodes (7): ADDED Requirements, Requirement: At-rest encryption for locally stored messages, Requirement: Outbound queue displays failed messages for retry, Scenario: Message exhausts 25 retries, Scenario: New SMS persisted locally, Scenario: Reading legacy plaintext rows after upgrading from v1.0.23, Spec Delta

### Community 40 - "Project Rules & Engineering Workflow"
Cohesion: 0.29
Nodes (6): 0. Session Continuity & Active Work (READ FIRST IN EVERY NEW CHAT!), 1. Project Context & Graphify (Orient First, Keep Fresh), 2. Superpowers + OpenSpec Engineering Workflow, 3. Release Process after Feature Additions, Project Rules & Engineering Workflow, Standard Lifecycle for Features & Bug Fixes

### Community 42 - ".onCreate"
Cohesion: 0.36
Nodes (5): ConnectionUiState, DisplayMessage, ViewerDashboardScreen(), ViewerInboxTab(), ViewerMainTab()

### Community 43 - "Core Design Goals"
Cohesion: 0.13
Nodes (11): SecureSMSForwarder — Agent Orientation, Start Here (Do Not Start Fresh), 1. Locked Architectural Decisions (Preserve Going Forward), 3. Environment & Tooling Bootstrap (For Fresh Ephemeral VMs), SecureSMSForwarder — Living Session Handoff & Swarm Brief, 1. Project Overview, 3. Architecture & Module Map, 5. Development Tooling & Workflow Context (+3 more)

### Community 44 - "ADDED Requirements"
Cohesion: 0.29
Nodes (6): ADDED Requirements, Requirement: Disable Android cloud backup, Requirement: Reject legacy non-JSON wire payloads, Scenario: Legacy plain-text control string arrives, Scenario: Manifest backup configuration, Spec Delta

### Community 45 - "ADDED Requirements"
Cohesion: 0.29
Nodes (6): ADDED Requirements, Requirement: Automated CI build and unit test verification, Requirement: Dynamic version display in welcome toast, Scenario: App launches on v1.1.0, Scenario: Code is pushed to main or a pull request, Spec Delta

### Community 64 - "AppContainer"
Cohesion: 0.07
Nodes (24): AppContainer, RoleManager, AeadStorageCipher, LocalStorageCipher, StorageCipher, AppDatabase, CustomScannerActivity, 2. Tech Stack & Build Configuration (+16 more)

### Community 91 - "Requirements"
Cohesion: 0.18
Nodes (10): Purpose, Requirement: At-rest encryption for locally stored messages, Requirement: On-demand historical SMS sync, Requirement: Outbound queue displays failed messages for retry, Requirements, Scenario: Message exhausts 25 retries, Scenario: New SMS persisted locally, Scenario: Reading legacy plaintext rows after upgrading from v1.0.23 (+2 more)

## Knowledge Gaps
- **11 isolated node(s):** `UNASSIGNED`, `SENDER`, `VIEWER`, `RECEIVED`, `QUEUED` (+6 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 251 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **30 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `ForwardingService` connect `ForwardingService` to `ForwardingService.kt`, `AppContainer`, `Requirements`, `MainActivity.kt`, `.onCreate`, `TransportManager.kt`, `2. Application Singleton Container (`SecureSmsApplication` + `AppContainer`)`, `TransportManager`, `Core Design Goals`, `SmsObserver.kt`, `Requirements`, `MainActivity`, `Requirement: Manual pause and wakeup sync`, `NetworkCallback`, `Scope (In-Scope)`, `ADDED Requirements`, `Requirements`, `ADDED Requirements`?**
  _High betweenness centrality (0.128) - this node is a cross-community bridge._
- **Are the 31 inferred relationships involving `ForwardingService` (e.g. with `2. Completed Milestones (`v1.1.0`)` and `Core Design Goals`) actually correct?**
  _`ForwardingService` has 31 INFERRED edges - model-reasoned connections that need verification._
- **What connects `UNASSIGNED`, `SENDER`, `VIEWER` to the rest of the system?**
  _11 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `FirebaseRelayTransport` be split into smaller, more focused modules?**
  _Cohesion score 0.08048103607770583 - nodes in this community are weakly interconnected._
- **Why does `2. Completed Milestones (`v1.1.0`)` connect `TransportManager` to `AppContainer`, `AppUpdater.kt`, `FirebaseRelayTransport`, `TrustStore`, `RelayTransport`, `MessageDao`, `ForwardingService`, `.onCreate`, `Core Design Goals`, `2. Application Singleton Container (`SecureSmsApplication` + `AppContainer`)`, `LocalStorageCipherTest`, `SmsObserver.kt`, `MainActivity`, `SenderDashboardScreen.kt`, `MessageQueueManagerTest`, `TrustStoreTest.kt`, `MessageQueueManager`?**
  _High betweenness centrality (0.097) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `MessageEntity` (e.g. with `2. Tech Stack & Build Configuration` and `Core Design Goals`) actually correct?**
  _`MessageEntity` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Should `AppUpdater.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.06097560975609756 - nodes in this community are weakly interconnected._
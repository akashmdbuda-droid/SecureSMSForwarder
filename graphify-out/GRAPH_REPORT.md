# Graph Report - SecureSMSForwarder  (2026-11-21)

## Corpus Check
- 68 files · ~27,937 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 23 file(s) not represented in the graph (top: .xml 11, (none) 5, .properties 2)
- WARNING: 23 of 102 files seen (23%) have no supported extractor and are missing from this graph entirely. It may look complete while representing only part of the codebase.

## Summary
- 789 nodes · 1392 edges · 61 communities (32 shown, 29 thin omitted)
- Extraction: 86% EXTRACTED · 14% INFERRED · 0% AMBIGUOUS · INFERRED: 191 edges (avg confidence: 0.94)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `7671cd47`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- SessionDescription
- AppUpdater.kt
- Requirements
- SignalingScreen.kt
- SignalingProvider
- Observer
- .initializeTransport
- MessageEntity
- steps
- ForwardingService
- SmsReceiver.kt
- .createDataChannelObserver
- TransportManager
- QrPairingScreen.kt
- SharedComponents.kt
- Requirement: Singleton preferences without listener garbage collection
- FirebaseSignalingProvider
- MainActivity
- RoleManager
- Why
- SenderDashboardScreen.kt
- SmsReceiver.kt
- KeyManager
- MessageEncryption
- ManualSignalingProvider
- ADDED Requirements
- MessageQueueManager
- Requirements
- SignalingState
- MessageEncryptionFuzzTest
- BiometricHelper.kt
- TrustStore
- rules
- Theme.kt
- ADDED Requirements
- ADDED Requirements
- ValueEventListener
- .onCreate
- SecureSMSForwarder — Living Session Handoff & Swarm Brief
- SignalingEnvelope
- SmsObserver.kt
- WebRtcDirectTransport
- AppDatabase
- SmsParserTest.kt
- ExampleInstrumentedTest.kt
- Context
- Changelog.kt
- Requirements
- ExampleUnitTest.kt
- superpowers-workflow.md
- feature-lifecycle.md

## God Nodes (most connected - your core abstractions)
1. `ForwardingService` - 43 edges
2. `TransportManager` - 42 edges
3. `MessageEntity` - 34 edges
4. `MessageQueueManager` - 33 edges
5. `FirebaseSignalingProvider` - 32 edges
6. `WebRtcDirectTransport` - 30 edges
7. `TrustStore` - 30 edges
8. `SettingsManager` - 22 edges
9. `KeyManager` - 22 edges
10. `MessageDao` - 21 edges

## Surprising Connections (you probably didn't know these)
- `Scenario: Device switches from Wi-Fi to cellular data` --references--> `ForwardingService`  [INFERRED]
  openspec/changes/rebuild-e2ee-relay-core/specs/transport-and-relay/spec.md → app/src/main/java/com/example/securesmsforwarder/background/ForwardingService.kt
- `Scenario: Pause toggle updates service reliably` --references--> `ForwardingService`  [INFERRED]
  openspec/changes/rebuild-e2ee-relay-core/specs/ui-dashboards/spec.md → app/src/main/java/com/example/securesmsforwarder/background/ForwardingService.kt
- `Scenario: User disables Notifications in Settings` --references--> `ForwardingService`  [INFERRED]
  openspec/changes/rebuild-e2ee-relay-core/specs/ui-dashboards/spec.md → app/src/main/java/com/example/securesmsforwarder/background/ForwardingService.kt
- `Scenario: Retry limit exhaustion and manual requeue` --references--> `MessageQueueManager`  [INFERRED]
  openspec/specs/sms-capture-and-queue/spec.md → app/src/main/java/com/example/securesmsforwarder/core/domain/MessageQueueManager.kt
- `Scenario: Legacy plain-text control string arrives` --references--> `TransportManager`  [INFERRED]
  openspec/changes/rebuild-e2ee-relay-core/specs/crypto-and-pairing/spec.md → app/src/main/java/com/example/securesmsforwarder/p2p/TransportManager.kt

## Import Cycles
- None detected.

## Communities (61 total, 29 thin omitted)

### Community 0 - "SessionDescription"
Cohesion: 0.15
Nodes (3): SdpObserver, SdpObserver, SdpObserver

### Community 1 - "AppUpdater.kt"
Cohesion: 0.06
Nodes (17): BootReceiver, ReleaseNote, AppUpdater, BroadcastReceiver, UpdateInfo, 1. Current Version Snapshot, 2. Versioning Policy, 3. Version Synchronization Checklist (+9 more)

### Community 2 - "Requirements"
Cohesion: 0.18
Nodes (10): crypto-and-pairing Specification, Purpose, Requirement: Application-level E2EE payload encryption, Requirement: Hardware-backed device identity keyset, Requirement: Mutual QR pairing and biometric protection, Requirements, Scenario: First launch identity creation, Scenario: Sender encrypts SMS payload for paired Viewer (+2 more)

### Community 7 - "MessageEntity"
Cohesion: 0.06
Nodes (13): MessageDao, MessageEntity, MessageState, ACKNOWLEDGED, DELIVERED, ENCRYPTED, FAILED, PERSISTED_BY_B (+5 more)

### Community 8 - "steps"
Cohesion: 0.08
Nodes (23): permissions, runs-on, steps, GITHUB_TOKEN, KEYSTORE_B64, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD, SIGNING_STORE_PASSWORD (+15 more)

### Community 9 - "ForwardingService"
Cohesion: 0.18
Nodes (7): ForwardingService, LocalBinder, Requirement: Teardown transport on peer revocation and role reset, Scenario: User resets device role to UNASSIGNED, Scenario: User unpairs trusted device, Requirement: Clean relay listener teardown on service restart, Scenario: Service restarts after pairing or unpairing

### Community 11 - "SmsReceiver.kt"
Cohesion: 0.17
Nodes (4): SmsHistoryScanner, SmsReceiver, 1. Locked User Decisions (Do Not Re-Ask), 4. Release History Ledger

### Community 13 - "TransportManager"
Cohesion: 0.05
Nodes (38): TransportManager, 6. `TransportManager` Concurrency & Strict Framing, ADDED Requirements, MODIFIED Requirements, REMOVED Requirements, Requirement: Dual-tier encrypted message transport, Requirement: Manual pause and wakeup sync, Requirement: Multi-network-safe Firebase offline detection (+30 more)

### Community 14 - "QrPairingScreen.kt"
Cohesion: 0.14
Nodes (3): generateQrCode(), QrPairingScreen(), SetupScreen()

### Community 16 - "Requirement: Singleton preferences without listener garbage collection"
Cohesion: 0.15
Nodes (12): ADDED Requirements, Requirement: Incoming SMS notification preference enforcement, Requirement: Reactive connection state propagation across service restarts, Requirement: Sender standby controls parity and side-effect-safe read tracking, Requirement: Singleton preferences without listener garbage collection, Scenario: Cold start or pairing restart updates dashboard immediately, Scenario: Firebase error banner shown on dashboard, Scenario: Pause toggle updates service reliably (+4 more)

### Community 18 - "FirebaseSignalingProvider"
Cohesion: 0.06
Nodes (10): FirebaseSignalingProvider, ValueEventListener, ValueEventListener, ValueEventListener, ChildEventListener, ValueEventListener, ChildEventListener, Requirement: Safe relay delivery acknowledgment (+2 more)

### Community 19 - "MainActivity"
Cohesion: 0.22
Nodes (10): MainActivity, 2. Application Singleton Container (`SecureSmsApplication` + `AppContainer`), 3. Pure E2EE Firebase Relay (`RelayTransport` + `FirebaseRelayTransport`), 7. Queue Visibility, UI Polish & CI Verification, Architecture Overview, Design: Rebuild Core Transport as Pure E2EE Firebase Relay & Harden Security, Detailed Design Decisions, Risks & Mitigations (+2 more)

### Community 21 - "RoleManager"
Cohesion: 0.24
Nodes (5): DeviceRole, SENDER, UNASSIGNED, VIEWER, RoleManager

### Community 22 - "Why"
Cohesion: 0.20
Nodes (9): Capabilities, Impact, Modified Capabilities, New Capabilities, Non-Goals, Proposal: Rebuild Core Transport as Pure E2EE Firebase Relay & Harden Security, Target Release Impact, What Changes (+1 more)

### Community 23 - "SenderDashboardScreen.kt"
Cohesion: 0.37
Nodes (10): SettingsManager, ConnectionTab(), MetricRow(), SecurityTab(), SenderDashboardScreen(), SenderMainTab(), SenderQueueTab(), SettingsTab() (+2 more)

### Community 24 - "SmsReceiver.kt"
Cohesion: 0.38
Nodes (3): ParsedSms, SmsParser, Scenario: SMS arrives while device is in Doze mode

### Community 28 - "ADDED Requirements"
Cohesion: 0.25
Nodes (7): ADDED Requirements, Requirement: At-rest encryption for locally stored messages, Requirement: Outbound queue displays failed messages for retry, Scenario: Message exhausts 25 retries, Scenario: New SMS persisted locally, Scenario: Reading legacy plaintext rows after upgrading from v1.0.23, Spec Delta

### Community 29 - "MessageQueueManager"
Cohesion: 0.20
Nodes (3): MessageQueueManager, 1. Backward-Compatible At-Rest Encryption (`LocalStorageCipher`), Lane A — Crypto At-Rest, Storage & Singleton AppContainer (Parallel Wave 1)

### Community 30 - "Requirements"
Cohesion: 0.25
Nodes (7): Purpose, Requirement: Sender dashboard controls and queue visibility, Requirement: Viewer threaded inbox and unread tracking, Requirements, Scenario: User disconnects manually from dashboard, Scenario: Viewer opens a sender thread, ui-dashboards Specification

### Community 31 - "SignalingState"
Cohesion: 0.29
Nodes (7): SignalingState, ANSWER_GENERATING, ANSWER_PROCESSING, ANSWER_READY, IDLE, OFFER_GENERATING, OFFER_READY

### Community 34 - "BiometricHelper.kt"
Cohesion: 0.07
Nodes (17): 0. Session Continuity & Active Work (READ FIRST IN EVERY NEW CHAT!), 1. Project Context & Graphify (Orient First, Keep Fresh), 2. Superpowers + OpenSpec Engineering Workflow, 3. Release Process after Feature Additions, Project Rules & Engineering Workflow, Standard Lifecycle for Features & Bug Fixes, SecureSMSForwarder — Agent Orientation, Start Here (Do Not Start Fresh) (+9 more)

### Community 35 - "TrustStore"
Cohesion: 0.18
Nodes (3): TrustStore, Requirement: Backward-compatible existing pairing preservation, Scenario: Paired user upgrades from v1.0.23 to v1.1.0

### Community 36 - "rules"
Cohesion: 0.15
Nodes (12): guidance, guidance, context, operations, apply, archive, rules, design (+4 more)

### Community 38 - "ADDED Requirements"
Cohesion: 0.29
Nodes (6): ADDED Requirements, Requirement: Disable Android cloud backup, Requirement: Reject legacy non-JSON wire payloads, Scenario: Legacy plain-text control string arrives, Scenario: Manifest backup configuration, Spec Delta

### Community 39 - "ADDED Requirements"
Cohesion: 0.29
Nodes (6): ADDED Requirements, Requirement: Automated CI build and unit test verification, Requirement: Dynamic version display in welcome toast, Scenario: App launches on v1.1.0, Scenario: Code is pushed to main or a pull request, Spec Delta

### Community 42 - ".onCreate"
Cohesion: 0.27
Nodes (4): DisplayMessage, ViewerDashboardScreen(), ViewerInboxTab(), ViewerMainTab()

### Community 43 - "SecureSMSForwarder — Living Session Handoff & Swarm Brief"
Cohesion: 0.50
Nodes (4): 2. What Is Already Done, 3. Swarm Execution Map (Next Action), 4. Environment & Tooling Bootstrap (For Fresh Ephemeral VMs), SecureSMSForwarder — Living Session Handoff & Swarm Brief

### Community 44 - "SignalingEnvelope"
Cohesion: 0.18
Nodes (3): SignalingEnvelope, 3. Architecture & Module Map, Firebase Realtime Database Paths

### Community 49 - "SmsObserver.kt"
Cohesion: 0.23
Nodes (3): SmsObserver, Requirement: Dual SMS capture with deduplication, Scenario: Duplicate capture from receiver and content observer

### Community 64 - "AppDatabase"
Cohesion: 0.16
Nodes (5): AppDatabase, Requirement: Crash-safe Room queue with exponential backoff, Scenario: Retry limit exhaustion and manual requeue, Scenario: Unacknowledged message retries with capped backoff, Scenario: Viewer acknowledges receipt

### Community 88 - "Context"
Cohesion: 0.32
Nodes (4): NetworkCallback, 5. Multi-Network Safe `NetworkCallback`, Context, Lane C — Foreground Service, SMS Wiring, Manifest & Gradle Cleanup (Parallel Wave 2)

### Community 91 - "Requirements"
Cohesion: 0.25
Nodes (5): Purpose, Requirement: On-demand historical SMS sync, Requirements, Scenario: User syncs historical date range, sms-capture-and-queue Specification

## Knowledge Gaps
- **17 isolated node(s):** `UNASSIGNED`, `SENDER`, `VIEWER`, `IDLE`, `OFFER_GENERATING` (+12 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 234 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **29 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `FirebaseSignalingProvider` connect `FirebaseSignalingProvider` to `BiometricHelper.kt`, `SignalingProvider`, `.initializeTransport`, `SmsReceiver.kt`, `SignalingEnvelope`, `RoleManager`, `Why`, `Context`?**
  _High betweenness centrality (0.132) - this node is a cross-community bridge._
- **Are the 20 inferred relationships involving `ForwardingService` (e.g. with `3. Swarm Execution Map (Next Action)` and `4. Known Issues & Audited Bugs (as of `v1.0.23`)`) actually correct?**
  _`ForwardingService` has 20 INFERRED edges - model-reasoned connections that need verification._
- **What connects `UNASSIGNED`, `SENDER`, `VIEWER` to the rest of the system?**
  _17 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `SessionDescription` be split into smaller, more focused modules?**
  _Cohesion score 0.14761904761904762 - nodes in this community are weakly interconnected._
- **Why does `TransportManager` connect `TransportManager` to `ForwardingService.kt`, `Requirements`, `TrustStore`, `SignalingProvider`, `.initializeTransport`, `ADDED Requirements`, `ForwardingService`, `SecureSMSForwarder — Living Session Handoff & Swarm Brief`, `TransportManager.kt`, `WebRtcDirectTransport`, `Context`, `KeyManager`, `MessageEncryption`, `MessageQueueManager`?**
  _High betweenness centrality (0.127) - this node is a cross-community bridge._
- **Are the 18 inferred relationships involving `TransportManager` (e.g. with `3. Swarm Execution Map (Next Action)` and `6. `TransportManager` Concurrency & Strict Framing`) actually correct?**
  _`TransportManager` has 18 INFERRED edges - model-reasoned connections that need verification._
- **Should `AppUpdater.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.0641025641025641 - nodes in this community are weakly interconnected._
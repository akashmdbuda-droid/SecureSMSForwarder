# Tasks: rebuild-e2ee-relay-core (Swarm-Ready Execution Plan)

> **Swarm Coordination Rules:**
> - Follow **Superpowers TDD** (`.agents/skills/test-driven-development/SKILL.md`): write failing unit tests first, then implement minimal code to pass.
> - Lanes **A** and **B** have **zero file overlap** and can execute in parallel immediately.
> - Lanes **C** and **D** depend on Lanes **A** and **B** (specifically `AppContainer`, `StorageCipher`, `RelayTransport`, `ConnectionUiState`, and `TransportManager`) and can then execute in parallel with each other.
> - Lane **E** runs last for CI verification, OpenSpec archive, Graphify refresh, and release `v1.1.0` (only tag/release after verification passes).

---

## Lane A — Crypto At-Rest, Storage & Singleton AppContainer (Parallel Wave 1)
**Owned files:** `crypto/storage/LocalStorageCipher.kt`, `storage/MessageDao.kt`, `core/domain/MessageQueueManager.kt`, `core/domain/SettingsManager.kt`, `pairing/TrustStore.kt`, `AppContainer.kt`, `SecureSmsApplication.kt`, `app/src/test/.../MessageQueueManagerTest.kt`, `app/src/test/.../LocalStorageCipherTest.kt`, `app/src/test/.../TrustStoreTest.kt`

- [ ] A.1 Create `StorageCipher` interface + `LocalStorageCipher` (Tink AEAD backed by Android Keystore with `"AEADv1:"` prefix and backward-compatible UTF-8 fallback for pre-`v1.1.0` plaintext rows) + unit tests (`LocalStorageCipherTest`).
- [ ] A.2 Add `MessageDao.getSenderQueueMessages()` (including `FAILED` alongside pending states while keeping `getPendingMessages()` excluding `FAILED`).
- [ ] A.3 Update `MessageQueueManager` to accept `StorageCipher`, encrypt bodies on `enqueueMessage`/`enqueueTestMessage`/`insertViewerMessage`, decrypt during ±30s deduplication, and add/update unit tests in `MessageQueueManagerTest`.
- [ ] A.4 Update `TrustStore.addTrustedPeer()` to preserve existing custom `peer_name` when re-saving the same `deviceId` without an explicit custom name, and add `TrustStoreTest`.
- [ ] A.5 Update `SettingsManager` so `isNotificationsEnabled` defaults to `true`.
- [ ] A.6 Create `AppContainer` and `SecureSmsApplication` holding singleton instances of `SettingsManager`, `RoleManager`, `TrustStore`, `KeyManager`, `StorageCipher`, `AppDatabase`, and `MessageQueueManager`.

---

## Lane B — Pure E2EE Firebase Relay Transport & Coordinator (Parallel Wave 1)
**Owned files:** `p2p/ConnectionUiState.kt`, `p2p/relay/RelayTransport.kt`, `p2p/relay/FirebaseRelayTransport.kt`, `p2p/TransportManager.kt`, delete `p2p/webrtc/WebRtcDirectTransport.kt`, delete `p2p/signaling/*`, `app/src/test/.../TransportManagerTest.kt`

- [ ] B.1 Create `ConnectionUiState` data class and `RelayTransport` interface (with `sendEncryptedRelayMessage`, `setEncryptedRelayMessageListener`, `requestWakeup`, `setWakeupListener`, `isRelayAvailable`, `setRelayAvailabilityListener`, `isPeerOnline`, `getPeerLastSeen`, `setPeerPresenceListener`, `setErrorListener`, `goOnline`, `goOffline`, `close`).
- [ ] B.2 Implement `FirebaseRelayTransport` preserving existing RTDB paths (`presence/`, `signaling/<id>/wakeup`, `messages/<id>/inbox/`), tracking every `ValueEventListener` and `ChildEventListener` for complete teardown in `close()`, and surfacing all `DatabaseError`/write failures via `onErrorListener`.
- [ ] B.3 Rebuild `TransportManager` as a pure E2EE Firebase Relay coordinator (no WebRTC, no legacy plaintext fallbacks): expose `StateFlow<ConnectionUiState>`, guard `drainQueue()` with a coroutine `Mutex`, make `requestConnection()` explicitly clear `isPaused = false` + go online + send wakeup + requeue failed + drain, and call `relayTransport.close()` in `close()`.
- [ ] B.4 Delete obsolete `WebRtcDirectTransport.kt`, `SignalingProvider.kt`, `FirebaseSignalingProvider.kt`, `ManualSignalingProvider.kt`, and `SignalingEnvelope.kt`.
- [ ] B.5 Write comprehensive `TransportManagerTest` using a `FakeRelayTransport` covering: `requestConnection()` unpausing from `PAUSED`, `Mutex`-serialized `drainQueue()`, HPKE SMS/ACK/WIPE round-trip, rejection of legacy non-JSON payloads, and Firebase error propagation into `ConnectionUiState.lastError`.

---

## Lane C — Foreground Service, SMS Wiring, Manifest & Gradle Cleanup (Parallel Wave 2)
**Owned files:** `background/ForwardingService.kt`, `background/BootReceiver.kt`, `sms/SmsReceiver.kt`, `sms/SmsObserver.kt`, `sms/SmsHistoryScanner.kt`, `AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`

- [ ] C.1 Remove `libs.webrtc` from `app/build.gradle.kts` and `gradle/libs.versions.toml`.
- [ ] C.2 Update `AndroidManifest.xml`: register `android:name=".SecureSmsApplication"` and set `android:allowBackup="false"`.
- [ ] C.3 Rebuild `ForwardingService.kt`: use `AppContainer` singletons, expose persistent companion `StateFlow<ConnectionUiState>`, support `ACTION_CONNECT` / `ACTION_DISCONNECT` / `ACTION_RESTART` / `ACTION_DRAIN_QUEUE`, manage `serviceScope` + cancellable `transportScope`, fix `NetworkCallback.onLost()` to check `cm.getNetworkCapabilities(cm.activeNetwork)`, decrypt notification bodies via `StorageCipher`, and honor `settingsManager.isNotificationsEnabled`.
- [ ] C.4 Update `SmsReceiver.kt`, `SmsObserver.kt`, `SmsHistoryScanner.kt`, and `BootReceiver.kt` to use `AppContainer` singletons cleanly.

---

## Lane D — Compose UI Dashboards, MainActivity & GitHub Actions CI (Parallel Wave 2)
**Owned files:** `MainActivity.kt`, `ui/dashboard/SenderDashboardScreen.kt`, `ui/dashboard/ViewerDashboardScreen.kt`, delete `ui/signaling/SignalingScreen.kt`, `.github/workflows/ci.yml`

- [ ] D.1 Delete `ui/signaling/SignalingScreen.kt` and remove all manual signaling references from `MainActivity.kt`.
- [ ] D.2 Update `MainActivity.kt`: use `AppContainer`, bind welcome toast to `BuildConfig.VERSION_NAME`, collect `ForwardingService.connectionUiState`, route Connect -> `ACTION_CONNECT` via `ContextCompat.startForegroundService`, Disconnect -> `ACTION_DISCONNECT`, Unpair -> `ACTION_RESTART`, Reset Role -> `stopService`, bind Sender queue to `getSenderQueueMessages()`, and decrypt Viewer bodies via `StorageCipher`.
- [ ] D.3 Update `SenderDashboardScreen.kt` & `ViewerDashboardScreen.kt`: display `ConnectionUiState.lastError` diagnostic banner when non-null, update `ConnectionTab` metrics for pure E2EE Relay + Room AEAD, add `isPeerOffline` Disconnect/Reconnect parity to `SenderMainTab`, and wrap `ViewerInboxTab` mark-as-read inside `LaunchedEffect(msg.id)`.
- [ ] D.4 Add `.github/workflows/ci.yml` running `./gradlew testDebugUnitTest assembleDebug` on pushes to `main` and pull requests.

---

## Lane E — Verification, OpenSpec Archive, Graphify Refresh & Release `v1.1.0` (Final Wave)
**Owned files:** `openspec/specs/**`, `openspec/changes/archive/**`, `app/build.gradle.kts`, `ui/changelog/Changelog.kt`, `docs/PROJECT_CONTEXT.md`, `docs/VERSIONING.md`, `docs/HANDOFF.md`, `graphify-out/**`

- [ ] E.1 Push implementation branch/commits and verify `.github/workflows/ci.yml` (`./gradlew testDebugUnitTest assembleDebug`) passes completely with zero errors.
- [ ] E.2 Sync all delta specs into `openspec/specs/`, run `openspec validate --specs`, and archive `rebuild-e2ee-relay-core` (`openspec archive rebuild-e2ee-relay-core`).
- [ ] E.3 Update `docs/PROJECT_CONTEXT.md` (remove resolved `BUG-01`–`BUG-12`, document pure E2EE Relay + `LocalStorageCipher` + `AppContainer`), `docs/VERSIONING.md`, `docs/HANDOFF.md`, and run `graphify update . --force`.
- [ ] E.4 Once confirmed working, execute Release Process (`.agents/AGENTS.md`): bump `versionName = "1.1.0"` & `versionCode = 25` in `app/build.gradle.kts`, add `v1.1.0` entry to `Changelog.kt`, commit, tag `v1.1.0`, and push (`git push origin main && git push origin v1.1.0`).

# Proposal: Rebuild Core Transport as Pure E2EE Firebase Relay & Harden Security

## Why

Pressing **Connect** on an already-paired device in `v1.0.23` currently fails to connect due to accumulated regressions across `v1.0.17`–`v1.0.23`:
1. `ForwardingService.kt:128-133` creates `SettingsManager` as a local variable whose `SharedPreferences.OnSharedPreferenceChangeListener` gets garbage-collected (`WeakHashMap`), and `MainActivity` / `TransportManager.requestConnection()` never clears `isPaused`, leaving the engine permanently stuck in `PAUSED`.
2. `ForwardingService.connectionStateFlow` is a plain nullable `var` replaced on startup/`ACTION_RESTART`, so Jetpack Compose never observes new service instances and remains stuck on `DISCONNECTED`.
3. `ForwardingService.registerNetworkCallback()` calls `FirebaseDatabase.getInstance().goOffline()` on any `onLost(network)` event (even during Wi-Fi <-> Cellular handoff), and `FirebaseSignalingProvider` leaks all 6 RTDB listeners across restarts.
4. `WebRtcDirectTransport.kt` adds 300+ lines of fragile SDP/ICE/public-TURN (`openrelay.metered.ca`) machinery that drains battery, leaks IPs to third-party STUN/TURN servers, and is unnecessary for ~160-byte SMS payloads.
5. Local Room storage persists SMS bodies as raw plaintext (`MessageQueueManager.kt:38`), `AndroidManifest.xml` has `android:allowBackup="true"`, and Firebase errors (such as expired Test Mode rules) are silently swallowed in `Log.e`.

This change replaces the tangled WebRTC + Firebase dual-tier core with a clean, single-tier **100% End-to-End Encrypted (Tink HPKE) Firebase Relay architecture**, hardens on-device security, fixes all lifecycle/UI bugs, and **preserves existing paired device keys without requiring re-pairing**.

## What Changes

### Scope (In-Scope)
- **Remove WebRTC & Dead Legacy Code:**
  - Remove `WebRtcDirectTransport.kt`, `ManualSignalingProvider.kt`, `SignalingScreen.kt`, `SignalingEnvelope.kt`, and the unused `libs.webrtc` dependency from `app/build.gradle.kts` / `gradle/libs.versions.toml`.
  - Remove unsigned legacy plaintext fallback parsing (`WIPE:ALL`, `ACK:`, `msgId|body`) from `TransportManager`.
- **Clean Pure E2EE Firebase Relay Transport (`FirebaseRelayTransport` + `TransportManager`):**
  - Single-tier transport over Firebase Realtime Database HTTPS/WSS (port 443): all SMS, ACK, and WIPE payloads remain Tink HPKE encrypted (`SecureSmsProtocol_V1`) before leaving the device and are deleted from Firebase only after confirmed local decryption + Room persistence.
  - Proper `close()` detaching every `ValueEventListener` and `ChildEventListener`.
  - `Mutex`-serialized `drainQueue()` preventing concurrent duplicate dispatches and `retryCount` races.
  - Surface Firebase `DatabaseError` / permission / connectivity errors in reactive UI state so silent failures (e.g., expired RTDB rules) are immediately visible to the user.
- **Clean Singleton App State & Service Lifecycle (`AppContainer` + `ForwardingService`):**
  - Introduce an `Application`-scoped `AppContainer` holding singletons for `SettingsManager`, `RoleManager`, `TrustStore`, `KeyManager`, `LocalStorageCipher`, `AppDatabase`, and `MessageQueueManager` — eliminating GC'd `SharedPreferences` listeners.
  - Expose a stable singleton `StateFlow<ConnectionUiState>` from `ForwardingService` so Compose always reflects live transitions across cold start and `ACTION_RESTART`.
  - Guarantee tapping **Connect** unpauses (`isConnectionPaused = false`), starts `ForwardingService` with `ACTION_CONNECT` if stopped, and immediately wakes up + syncs.
  - Make `NetworkCallback.onLost()` check whether another validated internet network remains active before calling `FirebaseDatabase.getInstance().goOffline()`.
  - Restart `ForwardingService` on peer unpair (`onRemoveTrustClick`) and stop it on role reset (`onResetRoleClick`).
- **Security Hardening (Zero Re-Pairing Required):**
  - Preserve existing `TrustStore` (`secure_sms_trust_store`) and `KeyManager` (`device_identity_keyset`) formats so already-paired devices stay paired seamlessly on upgrade.
  - Encrypt locally stored SMS bodies at rest in Room using Android Keystore-backed Tink AEAD (`LocalStorageCipher`), with transparent backward-compatible read fallback for any pre-existing plaintext rows.
  - Set `android:allowBackup="false"` in `AndroidManifest.xml`.
- **UI, Queue & Versioning Fixes:**
  - Expose `FAILED` messages in Sender Queue (`MessageDao.getSenderQueueMessages()`) so the `Retry (<count>)` button works.
  - Add `Disconnect` / `Reconnect` standby parity on `SenderMainTab` during `RELAY STANDBY (Peer Offline)`.
  - Move `ViewerInboxTab` mark-as-read DB updates into `LaunchedEffect(msg.id)`.
  - Honor `SettingsManager.isNotificationsEnabled` (default `true`) in `ForwardingService.showIncomingMessageNotification()`.
  - Bind `MainActivity` welcome toast dynamically to `BuildConfig.VERSION_NAME`.
  - Add `.github/workflows/ci.yml` to run `./gradlew testDebugUnitTest assembleDebug` on pushes/PRs so every swarm/agent change gets automated Android SDK build & test verification.

### Non-Goals
- Requiring users to re-pair existing devices (cryptographic Ed25519 sender signatures are deferred to a future opt-in change since they require a new QR exchange).
- Supporting multiple simultaneous paired peers (remains 1:1 Sender <-> Viewer).
- Modifying Firebase Console RTDB rules directly from the repo (errors will be surfaced clearly in UI with actionable guidance).

### Target Release Impact
- **Minor Release:** `1.0.23` (`versionCode = 24`) -> `1.1.0` (`versionCode = 25`) per `docs/VERSIONING.md`.

## Capabilities

### New Capabilities
*(None — changes evolve and harden the 5 existing capabilities.)*

### Modified Capabilities
- `transport-and-relay`: Replace dual-tier WebRTC + Relay with pure E2EE Firebase Relay; require guaranteed unpause/service-start on Connect, clean listener teardown, multi-network-safe offline detection, serialized queue draining, and visible error diagnostics.
- `sms-capture-and-queue`: Require Tink AEAD at-rest encryption for locally stored SMS payloads (with legacy plaintext read migration) and visibility of `FAILED` messages in the Sender Queue tab.
- `crypto-and-pairing`: Require preserving existing paired keysets across upgrade, setting `android:allowBackup="false"`, rejecting legacy non-JSON payloads, and tearing down transport on unpair/role reset.
- `ui-dashboards`: Require reactive `ConnectionUiState` propagation across service restarts, guaranteed Connect startup, Sender standby parity, side-effect-safe read receipts, notification toggle enforcement, and visible error display.
- `versioning-and-updater`: Require dynamic `BuildConfig.VERSION_NAME` welcome toast and GitHub Actions CI verification workflow.

## Impact

- **Removed files:** `WebRtcDirectTransport.kt`, `ManualSignalingProvider.kt`, `SignalingEnvelope.kt`, `SignalingScreen.kt` (and `libs.webrtc` dependency).
- **New files:** `AppContainer.kt` / `SecureSmsApplication.kt`, `crypto/storage/LocalStorageCipher.kt`, `p2p/relay/RelayTransport.kt` & `FirebaseRelayTransport.kt`, `.github/workflows/ci.yml`, plus expanded unit tests under `app/src/test/`.
- **Modified files:** `AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml`, `MainActivity.kt`, `ForwardingService.kt`, `TransportManager.kt`, `MessageQueueManager.kt`, `SettingsManager.kt`, `TrustStore.kt`, `MessageDao.kt`, `SmsReceiver.kt`, `SmsObserver.kt`, `SmsHistoryScanner.kt`, `SenderDashboardScreen.kt`, `ViewerDashboardScreen.kt`, `Changelog.kt`.
- **Database / Upgrade Safety:** No Room schema bump needed; existing `TrustStore` and `KeyManager` data remain 100% compatible.

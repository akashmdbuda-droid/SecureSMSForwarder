# SecureSMSForwarder — Project Context

> **Living Context Document** — Maintained alongside the Graphify knowledge graph (`graphify-out/`), OpenSpec living specifications (`openspec/specs/`), and Version Ledger (`docs/VERSIONING.md`).
>
> **Current Version:** `v1.1.0` (`versionCode = 25`) | **Last Updated:** `2026-11-21`

---

## 1. Project Overview

**SecureSMSForwarder** is an Android application (Kotlin + Jetpack Compose) that securely forwards incoming SMS messages from a **Sender** phone (holding the SIM card) to a paired **Viewer** device (e.g., secondary phone or tablet across international networks such as Hungary <-> India) using end-to-end encryption and a pure HTTPS/WSS encrypted relay architecture.

### Core Design Goals
1. **Zero Cloud or Disk Plaintext:**
   - **Wire E2EE:** All forwarded SMS messages, ACKs, and WIPE commands are serialized as strict JSON and encrypted on-device with Google Tink HPKE (`DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305`, `contextInfo = "SecureSmsProtocol_V1"`) before leaving the device. Relay messages are deleted from Firebase Realtime Database only after confirmed local decryption and Room persistence.
   - **At-Rest Encryption:** Local Room `MessageEntity.encryptedPayload` rows are encrypted via Android Keystore-backed Tink AES256-GCM (`LocalStorageCipher`, `"AEADv1:"` header with backward-compatible UTF-8 read fallback for pre-`v1.1.0` rows), and Android Auto Backup is disabled (`android:allowBackup="false"`).
2. **Pure 100% E2EE Firebase Realtime Database Relay:**
   - Single-tier transport (`RelayTransport` / `FirebaseRelayTransport`) over standard HTTPS/WSS port 443 with real-time peer presence heartbeats (`presence/<deviceId>`), wakeup pings (`signaling/<deviceId>/wakeup`), and zero third-party WebRTC/STUN/TURN dependencies or IP leaks.
   - Surfaces all Firebase `DatabaseError` / permission / write failures directly in `ConnectionUiState.lastError` with actionable UI diagnostic banners.
3. **Crash-Safe & Offline-Resilient Queuing:**
   - Incoming SMS messages are deduplicated (±30s window via decrypted body comparison), encrypted at rest, persisted in Room (`AppDatabase`, `MessageEntity`), and dispatched via a `Mutex`-serialized `TransportManager.drainQueueInternal()` with capped exponential backoff (up to 25 retries, max 60s interval) until an encrypted `ACK` arrives.
4. **Reliable Process-Scoped Lifecycle:**
   - `SecureSmsApplication` hosts an `AppContainer` holding strongly-referenced lazy singletons (`SettingsManager`, `RoleManager`, `TrustStore`, `KeyManager`, `LocalStorageCipher`, `AppDatabase`, `MessageQueueManager`) so `SharedPreferences` listeners are never GC'd.
   - `ForwardingService` exposes a persistent companion `StateFlow<ConnectionUiState>`, supports explicit `ACTION_CONNECT` / `ACTION_DISCONNECT` / `ACTION_RESTART` / `ACTION_DRAIN_QUEUE` / `ACTION_WIPE_PEER` intents, cleanly detaches all RTDB listeners on pause/restart/destroy, and uses a multi-network-safe `NetworkCallback`.
5. **In-App Auto-Updates & CI:**
   - Checks GitHub Releases (`akashmdbuda-droid/SecureSMSForwarder`) via `AppUpdater`, downloads the signed release APK, and invokes Android's package installer via `FileProvider`.
   - Automated Android CI (`.github/workflows/ci.yml`) runs `./gradlew testDebugUnitTest assembleDebug` on pushes and PRs.

---

## 2. Tech Stack & Build Configuration

| Component | Details |
| :--- | :--- |
| **Language / UI** | Kotlin, Jetpack Compose, Material 3 |
| **Android SDK** | `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`, JVM target 11 |
| **Local Storage** | Room (`AppDatabase` v4, `MessageDao`, `MessageEntity`) + Tink AEAD (`LocalStorageCipher`) + SharedPreferences (`RoleManager`, `SettingsManager`, `TrustStore`) |
| **Cryptography** | Google Tink (`tink-android`), Android Keystore master key (`android-keystore://secure_sms_master_key`), HPKE X25519 + ChaCha20-Poly1305 (wire) and AES256-GCM AEAD (local Room) |
| **Networking / Relay** | Pure E2EE Firebase Realtime Database Relay (`FirebaseRelayTransport`, HTTPS/WSS port 443) |
| **QR Pairing & Auth** | ZXing (`CustomScannerActivity`, `QrPairingScreen`), AndroidX Biometric (`BiometricHelper`) |
| **Testing** | JUnit 4 + Robolectric 4.11.1 (`app/src/test/`) |
| **CI / Release** | `.github/workflows/ci.yml` (`./gradlew testDebugUnitTest assembleDebug`) & `.github/workflows/release.yml` (`v*` tags -> `./gradlew assembleRelease` -> GitHub Release APK) |

---

## 3. Architecture & Module Map

```
app/src/main/java/com/example/securesmsforwarder/
├── SecureSmsApplication.kt                # Application subclass providing process-scoped AppContainer
├── AppContainer.kt                        # Lazy singleton container (KeyManager, LocalStorageCipher, TrustStore, RoleManager, SettingsManager, AppDatabase, MessageQueueManager)
├── MainActivity.kt                        # Entry point, Compose root, role routing, reactive ConnectionUiState, QR pairing, update & changelog dialogs
├── background/
│   ├── ForwardingService.kt               # Foreground service (remoteMessaging|dataSync), persistent StateFlow<ConnectionUiState>, multi-network-safe NetworkCallback, SmsObserver, TransportManager
│   └── BootReceiver.kt                    # Starts ForwardingService on BOOT_COMPLETED when auto-start enabled
├── core/
│   ├── domain/
│   │   ├── RoleManager.kt                 # Persists DeviceRole (UNASSIGNED, SENDER, VIEWER)
│   │   ├── SettingsManager.kt             # Singleton SharedPreferences + StateFlows (dark_mode, connection_paused, auto_start, notifications=true, last_seen_version_code)
│   │   └── MessageQueueManager.kt         # AEAD at-rest encrypt/decrypt, ±30s dedup, Room queue transitions, exponential backoff, requeueFailedMessages, viewer message insert
│   └── util/
│       └── BiometricHelper.kt             # BiometricPrompt wrapper gating pairing, unpairing, wipe, role reset
├── crypto/
│   ├── identity/KeyManager.kt             # X25519 HPKE identity keyset (`device_identity_keyset`) in Android Keystore + 12-hex-char SHA-256 fingerprint
│   ├── encryption/MessageEncryption.kt    # Tink HybridEncrypt / HybridDecrypt with contextInfo = "SecureSmsProtocol_V1"
│   └── storage/LocalStorageCipher.kt      # StorageCipher interface + AeadStorageCipher ("AEADv1:" prefix + legacy UTF-8 fallback) + LocalStorageCipher (Android Keystore AES256-GCM)
├── pairing/
│   └── TrustStore.kt                      # Stores trusted peer deviceId, public keyset JSON, custom-name-preserving friendly name, lastSyncedTimestamp
├── p2p/
│   ├── ConnectionUiState.kt               # Reactive UI state (status, isRelayConnected, isPeerOnline, peerLastSeen, isPaused, lastError)
│   ├── TransportManager.kt                # Pure E2EE relay coordinator, Mutex-serialized drainQueueInternal(), strict JSON SMS/ACK/WIPE framing, 3.5s retry loop
│   └── relay/
│       ├── RelayTransport.kt              # Pure E2EE relay transport interface
│       └── FirebaseRelayTransport.kt      # Firebase RTDB relay with tracked listener teardown, presence heartbeats, wakeup pings, and error reporting
├── sms/
│   ├── SmsReceiver.kt                     # High-priority SMS_RECEIVED BroadcastReceiver with 15s WakeLock + goAsync()
│   ├── SmsObserver.kt                     # ContentObserver on content://sms as fallback capture (cancellable scope)
│   ├── SmsParser.kt                       # Multipart PDU assembly into ParsedSms
│   └── SmsHistoryScanner.kt               # On-demand historical inbox date-range sync
├── storage/
│   ├── AppDatabase.kt                     # Room DB (version 4, fallbackToDestructiveMigration)
│   ├── MessageDao.kt                      # Queries for pending, sender queue (incl. FAILED), unread, all, and failed counts
│   └── MessageEntity.kt                   # MessageState enum & Room table definition
├── ui/
│   ├── setup/SetupScreen.kt               # Initial SENDER / VIEWER role selection
│   ├── pairing/QrPairingScreen.kt         # QR display + ZXing scanner trigger
│   ├── pairing/CustomScannerActivity.kt   # Full-sensor QR scanner activity
│   ├── dashboard/SenderDashboardScreen.kt # Dashboard, Queue, Connection, Security, Settings tabs + ErrorDiagnosticBanner
│   ├── dashboard/ViewerDashboardScreen.kt # Dashboard, Threaded Inbox (LaunchedEffect read tracking), Connection, Security, Settings tabs
│   ├── dashboard/SharedComponents.kt      # GlowingConnectionRing, StatusPill
│   ├── changelog/Changelog.kt             # ReleaseNote list & "What's New" AlertDialog
│   └── theme/                             # Color, Theme, Type
└── updater/
    └── AppUpdater.kt                      # GitHub Releases API check + DownloadManager + FileProvider APK install
```

### Firebase Realtime Database Paths (Backward-Compatible)
| Path | Purpose | Lifecycle |
| :--- | :--- | :--- |
| `pairing_requests/<targetDeviceId>/<senderDeviceId>` | One-way QR scan reciprocal key exchange | Deleted immediately on receipt |
| `presence/<deviceId>` | `{ online: Boolean, lastSeen: ServerValue.TIMESTAMP, role: String }` | `onDisconnect()` & pause/close set `online = false` |
| `signaling/<deviceId>/wakeup` | Timestamp ping to trigger remote reconnect & queue drain | Deleted on receipt |
| `messages/<targetDeviceId>/inbox/<safeMessageId>` | Base64 Tink HPKE ciphertext (strict JSON: `SMS`, `ACK`, `WIPE`) | Deleted only after `onProcessed(true)` |

---

## 4. Known Issues & Audited Bug Status

All 16 defects identified in the `v1.0.23` audit (`BUG-01`–`BUG-13`, `SEC-01`–`SEC-03`) were **resolved in `v1.1.0`** via OpenSpec change `2026-11-21-rebuild-e2ee-relay-core`:
- **Resolved (`BUG-01`–`BUG-13`):** Persistent companion `StateFlow<ConnectionUiState>`, multi-network-safe `NetworkCallback.onLost()`, complete RTDB listener teardown & cancellable `transportScope`, `FAILED` items visible in `SenderQueueTab` via `getSenderQueueMessages()`, WebRTC/Trickle ICE removed entirely, `Mutex`-serialized `drainQueueInternal()`, custom peer name preservation in `TrustStore`, service restart/stop on unpair & role reset, `SenderMainTab` `Peer Offline` Disconnect/Reconnect parity, `LaunchedEffect` read tracking in `ViewerInboxTab`, dynamic `BuildConfig.VERSION_NAME` welcome toast, `isNotificationsEnabled` (default `true`) enforced, singleton `AppContainer` eliminating GC'd `SettingsManager` listener, and guaranteed `ACTION_CONNECT` unpause/start flow.
- **Resolved (`SEC-01`–`SEC-03`):** Tink AEAD at-rest Room encryption (`LocalStorageCipher`), `android:allowBackup="false"`, WebRTC/STUN/public TURN removed, legacy non-JSON wire fallbacks rejected, and Firebase `DatabaseError`/write failures surfaced visibly via `ErrorDiagnosticBanner`.

### Open / Deferred Enhancements
- **Optional Future:** Cryptographic Ed25519 sender authentication signatures (deferred so existing paired devices do not require re-scanning QR codes on upgrade).
- **Operational Note:** Firebase Console Realtime Database security rules should be maintained outside Test Mode expiry; if rules deny access, `v1.1.0` now surfaces `Permission denied` immediately in the dashboard error banner.

---

## 5. Development Tooling & Workflow Context

1. **Session Handoff** (`docs/HANDOFF.md`): Always-current checkpoint for new chats/agents.
2. **Graphify** (`graphify-out/`): Local AST + doc knowledge graph. Query via `graphify query`, `graphify explain`, `graphify path`; refresh via `graphify update . --force`.
3. **OpenSpec** (`openspec/`): Spec-Driven Development. Source-of-truth specs live in `openspec/specs/`, active proposals in `openspec/changes/`, and completed history in `openspec/changes/archive/`.
4. **Superpowers** (`.agents/skills/`): Disciplined engineering skills (`brainstorming`, `systematic-debugging`, `writing-plans`, `test-driven-development`, `verification-before-completion`, etc.).
5. **Versioning & Release** (`docs/VERSIONING.md` + `.agents/AGENTS.md` + `.github/workflows/ci.yml` + `.github/workflows/release.yml`): Automated debug CI builds/tests on pushes and signed release APKs on `v*` tags.

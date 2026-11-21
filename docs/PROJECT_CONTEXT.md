# SecureSMSForwarder — Project Context

> **Living Context Document** — Maintained alongside the Graphify knowledge graph (`graphify-out/`), OpenSpec living specifications (`openspec/specs/`), and Version Ledger (`docs/VERSIONING.md`).
>
> **Current Version:** `v1.0.23` (`versionCode = 24`) | **Last Updated:** `2026-11-21`

---

## 1. Project Overview

**SecureSMSForwarder** is an Android application (Kotlin + Jetpack Compose) that securely forwards incoming SMS messages from a **Sender** phone (holding the SIM card) to a paired **Viewer** device (e.g., secondary phone or tablet across international networks such as Hungary <-> India) using end-to-end encryption and a fail-proof dual-tier transport.

### Core Design Goals
1. **Zero Cloud Plaintext:** All forwarded SMS messages, ACKs, and WIPE commands are encrypted on-device with Google Tink HPKE (`DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305`) before leaving the device. Relay messages are deleted from Firebase Realtime Database once confirmed decrypted and persisted locally.
2. **Dual-Tier Fail-Proof Connectivity:**
   - **Tier 1 (Direct P2P):** WebRTC DataChannel (`secure_sms_channel`) with Trickle ICE, multi-port STUN, and Metered.ca OpenRelay UDP/TCP/TLS TURN servers for strict CGNAT/firewall traversal.
   - **Tier 2 (Encrypted Fallback Relay):** Firebase Realtime Database inbox (`messages/<remoteDeviceId>/inbox/<safeMessageId>`) over HTTPS/WSS port 443 with real-time peer presence tracking (`presence/<deviceId>`).
3. **Crash-Safe & Offline-Resilient Queuing:** Incoming SMS messages are persisted in Room (`AppDatabase`, `MessageEntity`) before dispatch and retried with capped exponential backoff (up to 25 retries, max 60s interval) until an encrypted `ACK` is received from the Viewer.
4. **In-App Auto-Updates:** Checks GitHub Releases (`akashmdbuda-droid/SecureSMSForwarder`) via `AppUpdater`, downloads the signed release APK, and invokes Android's package installer via `FileProvider`.

---

## 2. Tech Stack & Build Configuration

| Component | Details |
| :--- | :--- |
| **Language / UI** | Kotlin, Jetpack Compose, Material 3 |
| **Android SDK** | `minSdk = 26`, `targetSdk = 36`, `compileSdk = 36`, JVM target 11 |
| **Local Storage** | Room (`AppDatabase` v4, `MessageDao`, `MessageEntity`) + SharedPreferences (`RoleManager`, `SettingsManager`, `TrustStore`) |
| **Cryptography** | Google Tink (`tink-android`), Android Keystore master key (`android-keystore://secure_sms_master_key`), HPKE X25519 + ChaCha20-Poly1305 |
| **Networking / P2P** | WebRTC (`WebRtcDirectTransport`), Firebase Realtime Database (`FirebaseSignalingProvider`) |
| **QR Pairing & Auth** | ZXing (`CustomScannerActivity`, `QrPairingScreen`), AndroidX Biometric (`BiometricHelper`) |
| **Testing** | JUnit 4 + Robolectric 4.11.1 (`app/src/test/`) |
| **CI / Release** | GitHub Actions (`.github/workflows/release.yml`) triggered on `v*` tags -> `./gradlew assembleRelease` -> GitHub Release APK |

---

## 3. Architecture & Module Map

```
app/src/main/java/com/example/securesmsforwarder/
├── MainActivity.kt                        # Entry point, Compose root, role routing, QR pairing, update & changelog dialogs
├── background/
│   ├── ForwardingService.kt               # Foreground service (remoteMessaging|dataSync), owns TransportManager, WebRTC, SmsObserver, NetworkCallback
│   └── BootReceiver.kt                    # Starts ForwardingService on BOOT_COMPLETED when auto-start enabled
├── core/
│   ├── domain/
│   │   ├── RoleManager.kt                 # Persists DeviceRole (UNASSIGNED, SENDER, VIEWER)
│   │   ├── SettingsManager.kt             # SharedPreferences + StateFlows (dark_mode, connection_paused, auto_start, notifications, last_seen_version_code)
│   │   └── MessageQueueManager.kt         # Deduplication, Room queue transitions, exponential backoff, requeueFailedMessages, viewer message insert
│   └── util/
│       └── BiometricHelper.kt             # BiometricPrompt wrapper gating pairing, unpairing, wipe, role reset
├── crypto/
│   ├── identity/KeyManager.kt             # X25519 HPKE identity keyset in Android Keystore + 12-hex-char SHA-256 fingerprint
│   └── encryption/MessageEncryption.kt    # Tink HybridEncrypt / HybridDecrypt with contextInfo = "SecureSmsProtocol_V1"
├── pairing/
│   └── TrustStore.kt                      # Stores single trusted peer's deviceId, public keyset JSON, friendly name, lastSyncedTimestamp
├── p2p/
│   ├── TransportManager.kt                # Dual-tier coordinator (WebRTC + Relay), E2EE payload framing (SMS/ACK/WIPE/KEY), 3.5s retry/drain loop
│   ├── signaling/
│   │   ├── SignalingProvider.kt           # Interface for WebRTC signaling, presence, and E2EE relay
│   │   ├── FirebaseSignalingProvider.kt   # Firebase RTDB signaling, Trickle ICE, presence heartbeats, E2EE inbox with safe-delete callback
│   │   ├── ManualSignalingProvider.kt     # Legacy/unpaired manual QR SDP exchange fallback
│   │   └── SignalingEnvelope.kt           # Base64 SDP + deviceId serialization
│   └── webrtc/
│       └── WebRtcDirectTransport.kt       # PeerConnectionFactory, DataChannel, STUN/TURN config, SDP & ICE flows
├── sms/
│   ├── SmsReceiver.kt                     # High-priority SMS_RECEIVED BroadcastReceiver with 15s WakeLock + goAsync()
│   ├── SmsObserver.kt                     # ContentObserver on content://sms as fallback capture
│   ├── SmsParser.kt                       # Multipart PDU assembly into ParsedSms
│   └── SmsHistoryScanner.kt               # On-demand historical inbox date-range sync
├── storage/
│   ├── AppDatabase.kt                     # Room DB (version 4, fallbackToDestructiveMigration)
│   ├── MessageDao.kt                      # Queries for pending/unread/all/failed messages
│   └── MessageEntity.kt                   # MessageState enum & Room table definition
├── ui/
│   ├── setup/SetupScreen.kt               # Initial SENDER / VIEWER role selection
│   ├── pairing/QrPairingScreen.kt         # QR display + ZXing scanner trigger
│   ├── pairing/CustomScannerActivity.kt   # Full-sensor QR scanner activity
│   ├── signaling/SignalingScreen.kt       # Manual signaling UI (when unpaired)
│   ├── dashboard/SenderDashboardScreen.kt # Dashboard, Queue, Connection, Security, Settings tabs
│   ├── dashboard/ViewerDashboardScreen.kt # Dashboard, Threaded Inbox, Connection, Security, Settings tabs
│   ├── dashboard/SharedComponents.kt      # GlowingConnectionRing, StatusPill
│   ├── changelog/Changelog.kt             # ReleaseNote list & "What's New" AlertDialog
│   └── theme/                             # Color, Theme, Type
└── updater/
    └── AppUpdater.kt                      # GitHub Releases API check + DownloadManager + FileProvider APK install
```

### Firebase Realtime Database Paths
| Path | Purpose | Lifecycle |
| :--- | :--- | :--- |
| `pairing_requests/<targetDeviceId>/<senderDeviceId>` | One-way QR scan reciprocal key exchange | Deleted immediately on receipt |
| `presence/<deviceId>` | `{ online: Boolean, lastSeen: ServerValue.TIMESTAMP, role: String }` | `onDisconnect()` sets `online = false` |
| `signaling/<deviceId>/offer` & `/answer` | Base64 `SignalingEnvelope` SDP | Deleted on receipt by remote peer |
| `signaling/<deviceId>/candidates/<pushId>` | Trickle ICE candidate map (`sdpMid`, `sdpMLineIndex`, `sdp`) | Deleted on consumption / new SDP |
| `signaling/<deviceId>/wakeup` | Timestamp ping to trigger remote reconnect & queue drain | Deleted on receipt |
| `messages/<targetDeviceId>/inbox/<safeMessageId>` | Base64 Tink HPKE ciphertext (SMS, ACK, WIPE) | Deleted only after `onProcessed(true)` |

---

## 4. Known Issues & Audited Bugs (as of `v1.0.23`)

A deep code audit on `2026-11-21` identified the following concrete defects that impair reliability and user experience:

| ID | Severity | Location | Issue Description |
| :--- | :--- | :--- | :--- |
| **BUG-01** | **High** | `ForwardingService.kt:54,126` + `MainActivity.kt:251-253` | `ForwardingService.connectionStateFlow` is a plain `var StateFlow<String>?` in `companion object`. Compose reads it once during composition and does **not** observe replacement when `ForwardingService` starts asynchronously or restarts (`ACTION_RESTART` after QR pairing), leaving the UI stuck on `"DISCONNECTED"` or a stale status until the app is force-closed. |
| **BUG-02** | **High** | `ForwardingService.kt:174-177` | `ConnectivityManager.NetworkCallback.onLost(network)` unconditionally calls `FirebaseDatabase.getInstance().goOffline()`. When a device transitions from Wi-Fi to Cellular (or vice versa), `onLost(oldNetwork)` kills Firebase even though another validated internet network is active. |
| **BUG-03** | **High** | `FirebaseSignalingProvider.kt` + `ForwardingService.kt:200-205` | `FirebaseSignalingProvider` has no `close()` method to detach its 6 Realtime Database listeners (`connectedRef`, `presence`, `offer/answer`, `wakeup`, `candidates`, `inbox`), and `ForwardingService` leaks its `settingsManager.connectionPausedFlow` coroutine on every `ACTION_RESTART`. Restarting after pairing or unpairing leaves ghost listeners firing duplicate/stale callbacks. |
| **BUG-04** | **High** | `MessageDao.kt:27` + `SenderDashboardScreen.kt:199` | `MessageDao.getPendingMessages()` excludes `state = 'FAILED'`, yet `MainActivity` passes `pendingMessages` to `SenderQueueTab` where `failedCount = queuedMessages.count { it.state == MessageState.FAILED }`. Consequently, `FAILED` messages are invisible and the "Retry Failed" button added in `v1.0.23` can **never** appear. |
| **BUG-05** | **High** | `WebRtcDirectTransport.kt:200-202` | `addRemoteIceCandidate()` has no buffering for candidates that arrive before `setRemoteDescription()` completes (Trickle ICE race condition; previously fixed in `v1.0.19` and lost in the `v1.0.20` revert to `v1.0.18`). Also, `setRemoteDescription()` silently drops offers if `peerConnection == null`. |
| **BUG-06** | **Medium** | `TransportManager.kt:360-400` | `drainQueue()` launches an unguarded coroutine on every trigger (3.5s loop, presence, reconnect, SMS receive) without mutual exclusion (`Mutex`), causing concurrent duplicate dispatches and `retryCount` races. |
| **BUG-07** | **Medium** | `TransportManager.kt:230-235` | Receiving a `KEY:` message over WebRTC calls `trustStore.addTrustedPeer(fingerprint, peerKey)` with the default `name = "Paired Device"`, overwriting the user's custom paired device name on every reconnect. |
| **BUG-08** | **Medium** | `MainActivity.kt:119-122, 350, 489` | Unpairing (`onRemoveTrustClick`) does not restart `ForwardingService`, leaving the revoked peer active in memory; resetting role (`onResetRoleClick`) does not stop `ForwardingService`. |
| **BUG-09** | **Medium** | `SenderDashboardScreen.kt:148` | `SenderMainTab` omits `isPeerOffline` from the connected/standby button branch (unlike `ViewerMainTab`), showing "Connect" instead of Disconnect/Reconnect while in `"RELAY STANDBY (Peer Offline)"`. |
| **BUG-10** | **Medium** | `ViewerDashboardScreen.kt:247-249` | `ViewerInboxTab` invokes `onMessageClick(msg.id)` directly during Compose composition rather than in a `LaunchedEffect`, triggering repeated DB writes on recomposition. |
| **BUG-11** | **Low** | `MainActivity.kt:32` | Hardcoded welcome toast still says `"Welcome to v1.0.22!"` despite `versionName = "1.0.23"`. Should derive from `BuildConfig.VERSION_NAME` to prevent future drift. |
| **BUG-12** | **Low** | `SettingsManager.kt:33` + `ForwardingService.kt:264` | `isNotificationsEnabled` defaults to `false` and is never consulted by `showIncomingMessageNotification()`. |
| **BUG-13** | **Critical** | `ForwardingService.kt:128-133` + `MainActivity.kt:307-316` + `TransportManager.kt:441-451` | `ForwardingService` instantiates `SettingsManager` as a local variable whose `SharedPreferences` listener (`WeakHashMap`) gets garbage-collected. Tapping **Connect** never calls `setConnectionPaused(false)`, `requestConnection()` never clears `isPaused`, and if `ForwardingService` was killed, Connect toasts `"Signaling Service not running"` instead of starting the service. |
| **SEC-01** | **High** | `MessageQueueManager.kt:38,117` | Local Room `MessageEntity.encryptedPayload` stores raw UTF-8 plaintext SMS text instead of Tink AEAD ciphertext. |
| **SEC-02** | **High** | `AndroidManifest.xml:19` | `android:allowBackup="true"` allows Android Auto Backup to copy Room DB and SharedPreferences off-device. |
| **SEC-03** | **Medium** | `WebRtcDirectTransport.kt:74-111` + `FirebaseSignalingProvider.kt` | WebRTC leaks IPs to Google STUN and routes through shared public TURN (`openrelay.metered.ca` with hardcoded public creds), while Firebase `DatabaseError` (e.g., expired Test Mode RTDB rules) is silently swallowed in `Log.e` with zero UI visibility. |

> **Active Remediation Plan:** See `docs/HANDOFF.md` and the validated OpenSpec change `openspec/changes/rebuild-e2ee-relay-core/` (Option B: pure E2EE Firebase Relay core rebuild + security hardening + backward-compatible pairing, targeting `v1.1.0`).

---

## 5. Development Tooling & Workflow Context

This repository integrates four complementary tools plus cross-session handoff:
1. **Session Handoff** (`docs/HANDOFF.md`): Always-current checkpoint with locked user decisions, swarm lanes, and next steps for new chats/agents.
2. **Graphify** (`graphify-out/`): Local AST + doc knowledge graph. Query via `graphify query`, `graphify explain`, `graphify path`; refresh via `graphify update .`.
3. **OpenSpec** (`openspec/`): Spec-Driven Development. Source-of-truth specs live in `openspec/specs/`, active proposals in `openspec/changes/`, and completed history in `openspec/changes/archive/`.
4. **Superpowers** (`.agents/skills/`): Disciplined engineering skills (`brainstorming`, `systematic-debugging`, `writing-plans`, `test-driven-development`, `verification-before-completion`, etc.).
5. **Versioning & Release** (`docs/VERSIONING.md` + `.agents/AGENTS.md` + `.github/workflows/release.yml`): Automated APK builds and GitHub Releases on `v*` tags.

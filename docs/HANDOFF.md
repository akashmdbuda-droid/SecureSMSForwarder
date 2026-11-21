# SecureSMSForwarder — Living Session Handoff & Swarm Brief

> **READ THIS FIRST IN EVERY NEW CHAT OR SWARM AGENT.**
> This file is the single source of truth for where work left off so any new agent or swarm can resume immediately without starting fresh. Update this file whenever a milestone, lane, or release completes.

**Last Updated:** `2026-11-21`
**Current App Version:** `v1.1.0` (`versionCode = 25`)
**Active OpenSpec Change:** *None* (Completed & archived: `openspec/changes/archive/2026-11-21-rebuild-e2ee-relay-core/`)
**Current Phase:** **RELEASED `v1.1.0` (`versionCode = 25`). Pure E2EE Firebase Relay core rebuilt, verified on GitHub Actions Android CI, synced & archived in OpenSpec, graph refreshed, and published via GitHub Releases.**

---

## 1. Locked Architectural Decisions (Preserve Going Forward)

1. **Pure 100% E2EE Firebase Realtime Database Relay (Option B):**
   - **Active Core:** Single-tier encrypted relay (`RelayTransport` / `FirebaseRelayTransport` + `TransportManager`) over HTTPS/WSS port 443, Google Tink HPKE (`SecureSmsProtocol_V1`), QR pairing, Room DB with Tink AEAD at-rest encryption (`LocalStorageCipher`), SMS capture (`SmsReceiver`, `SmsObserver`, `SmsHistoryScanner`), Material 3 Compose screens, and GitHub Releases auto-updater.
   - **Permanently Removed:** WebRTC (`WebRtcDirectTransport`, `libs.webrtc`), public Metered.ca STUN/TURN servers, `ManualSignalingProvider`, `SignalingScreen`, `SignalingEnvelope`, and legacy non-JSON wire fallbacks.
2. **Backward-Compatible Pairing & Storage:**
   - `KeyManager` (`device_identity_keyset` in `secure_sms_crypto_prefs`) and `TrustStore` (`secure_sms_trust_store`) key formats MUST remain backward-compatible so paired devices stay paired across upgrades.
   - `LocalStorageCipher` uses a separate keyset (`local_storage_aead_keyset`) in `secure_sms_crypto_prefs`, prefixes encrypted Room rows with `"AEADv1:"`, and transparently decodes legacy UTF-8 rows.
   - Firebase RTDB paths (`presence/`, `signaling/<id>/wakeup`, `messages/<id>/inbox/`, `pairing_requests/`) stay backward-compatible.
3. **Security Invariants:**
   - `android:allowBackup="false"` in `AndroidManifest.xml`.
   - Strict JSON-only wire payloads (`SMS`, `ACK`, `WIPE`); non-JSON/unknown types are rejected without modifying local state.
   - All Firebase `DatabaseError` / write failures surface via `ConnectionUiState.lastError` and `ErrorDiagnosticBanner` in the UI.

---

## 2. Completed Milestones (`v1.1.0`)

- [x] **Wave 1 — Lane A:** `StorageCipher` + `LocalStorageCipher` (`AEADv1:` + UTF-8 fallback), `MessageDao.getSenderQueueMessages()`, AEAD-backed `MessageQueueManager`, name-preserving `TrustStore`, `SettingsManager` (`notifications = true` default + immediate Flow updates), `AppContainer` + `SecureSmsApplication`, and unit tests (`LocalStorageCipherTest`, `MessageQueueManagerTest`, `TrustStoreTest`).
- [x] **Wave 1 — Lane B:** `ConnectionUiState`, `RelayTransport`, `FirebaseRelayTransport` (tracked listener teardown + error propagation), pure E2EE `TransportManager` (`Mutex`-serialized `drainQueueInternal()`, strict JSON framing, unpause-safe `requestConnection()`), WebRTC/legacy signaling deletion, and `TransportManagerTest`.
- [x] **Wave 2 — Lane C:** `ForwardingService` (`AppContainer`, persistent `StateFlow<ConnectionUiState>`, `ACTION_CONNECT`/`DISCONNECT`/`RESTART`/`DRAIN_QUEUE`/`WIPE_PEER`, multi-network-safe `NetworkCallback.onLost()`, decrypted notifications honoring `isNotificationsEnabled`), `BootReceiver`, `SmsReceiver`, `SmsObserver`, `SmsHistoryScanner`, `AndroidManifest.xml` (`SecureSmsApplication` + `allowBackup="false"`), and Gradle WebRTC removal.
- [x] **Wave 2 — Lane D:** `SignalingScreen` deletion, `MainActivity` (`AppContainer`, dynamic `BuildConfig.VERSION_NAME` toast, guaranteed Connect flow, Unpair restart, Reset Role stop, Sender failed-queue binding, Viewer AEAD decryption), `SenderDashboardScreen` & `ViewerDashboardScreen` (`ErrorDiagnosticBanner`, Relay metrics, Sender standby parity, `LaunchedEffect` read tracking), and `.github/workflows/ci.yml`.
- [x] **Wave 3 — Lane E:** Verified `.github/workflows/ci.yml` (`./gradlew testDebugUnitTest assembleDebug` green), synced all 5 delta specs into `openspec/specs/` (`5 passed, 0 failed`), archived `2026-11-21-rebuild-e2ee-relay-core`, updated `Changelog.kt` + living docs, refreshed `graphify-out/`, bumped to `v1.1.0` (`versionCode = 25`), tagged `v1.1.0`, and published signed APK via `.github/workflows/release.yml`.

---

## 3. Environment & Tooling Bootstrap (For Fresh Ephemeral VMs)

If a new chat starts on a fresh VM, reinstall the lightweight CLI tools (~15s) before running commands:
```bash
uv tool install graphifyy
npm install -g --prefix "$HOME/.local" @fission-ai/openspec@latest
export PATH="$HOME/.local/bin:$PATH"
```
- **Knowledge graph:** Committed in `graphify-out/`. Query with `graphify query "<question>"` / `graphify explain "<symbol>"`; refresh after edits with `graphify update . --force`.
- **Android CI & Release:** Ephemeral Linux VMs do not preinstall the Android SDK. Pushing to `main` or PRs automatically runs `.github/workflows/ci.yml` (`./gradlew testDebugUnitTest assembleDebug`); pushing a `v*` tag triggers `.github/workflows/release.yml` to build and publish the signed release APK.
- **Next feature workflow:** Follow `.agents/AGENTS.md` (Orient -> `openspec new change` / `openspec-propose` -> align -> TDD implement -> CI verify -> sync & archive -> bump/changelog/docs/graph -> tag & push).

# SecureSMSForwarder — Living Session Handoff & Swarm Brief

> **READ THIS FIRST IN EVERY NEW CHAT OR SWARM AGENT.**
> This file is the single source of truth for where work left off so any new agent or swarm can resume immediately without starting fresh. Update this file whenever a milestone, lane, or release completes.

**Last Updated:** `2026-11-21`
**Current App Version:** `v1.0.23` (`versionCode = 24`)
**Active OpenSpec Change:** `openspec/changes/rebuild-e2ee-relay-core/` (Target: `v1.1.0`, `versionCode = 25`)
**Current Phase:** **PLANNING COMPLETE & VALIDATED (`openspec validate --all` -> 6/6 passed). READY FOR SWARM IMPLEMENTATION (`/openspec-apply-change` / `/teamwork`).**

---

## 1. Locked User Decisions (Do Not Re-Ask)

1. **Architecture — Option B (Clean Core Rebuild inside existing repo):**
   - **Keep:** Repo, package ID (`com.example.securesmsforwarder`), signing keystore, GitHub Releases auto-updater, Google Tink HPKE crypto (`KeyManager`, `MessageEncryption`), QR pairing, Room DB, SMS capture (`SmsReceiver`, `SmsObserver`, `SmsHistoryScanner`), Material 3 Compose screens.
   - **Replace:** Remove WebRTC (`WebRtcDirectTransport`), public Metered.ca STUN/TURN servers, dead `ManualSignalingProvider` / `SignalingScreen`, and legacy plaintext fallbacks. Rebuild the transport/service core as **100% End-to-End Encrypted Firebase Realtime Database Relay** (HTTPS/WSS port 443, HPKE ciphertext only, wiped on confirmed receipt).
2. **Preserve Existing Pairing (Zero Re-Pairing):**
   - Existing `KeyManager` (`device_identity_keyset` in `secure_sms_crypto_prefs`) and `TrustStore` (`secure_sms_trust_store`) key formats MUST remain 100% backward-compatible so the user's already-paired Sender and Viewer stay paired across the `v1.1.0` upgrade.
   - Keep Firebase RTDB paths (`presence/`, `signaling/<id>/wakeup`, `messages/<id>/inbox/`, `pairing_requests/`) backward-compatible.
3. **Security Priorities:**
   - Encrypt SMS payloads at rest in Room via Android Keystore Tink AEAD (`LocalStorageCipher` with `"AEADv1:"` header + transparent UTF-8 fallback for pre-`v1.1.0` rows).
   - Set `android:allowBackup="false"` in `AndroidManifest.xml`.
   - Reject non-JSON legacy payloads on the wire.
   - Surface Firebase `DatabaseError` / permission errors visibly in the UI (so issues like expired Test Mode RTDB rules never fail silently).
4. **Execution Style:** User intends to run a **multi-agent swarm** to execute `openspec/changes/rebuild-e2ee-relay-core/tasks.md`.

---

## 2. What Is Already Done

- [x] Full codebase audit of `v1.0.23` and root-cause isolation of why **Connect** fails (GC'd `SettingsManager` `WeakHashMap` listener + `requestConnection()` not clearing `isPaused` + non-reactive `ForwardingService.connectionStateFlow` + `onLost()` calling `goOffline()` during Wi-Fi/Cellular switch + leaked RTDB listeners + silent Firebase errors).
- [x] **Graphify** integrated (`.agents/rules/graphify.md`, `.agents/skills/graphify/`, `.graphifyignore`) and knowledge graph built in `graphify-out/`.
- [x] **Superpowers v7.0.2** skills installed under `.agents/skills/` and unified with **OpenSpec** in `.agents/AGENTS.md`, `.agents/rules/superpowers-workflow.md`, and `.agents/workflows/feature-lifecycle.md`.
- [x] **OpenSpec** initialized (`openspec/config.yaml`), 5 baseline living specs authored in `openspec/specs/`, and active Option B change `openspec/changes/rebuild-e2ee-relay-core/` (`proposal.md`, 5 delta specs, `design.md`, swarm-ready `tasks.md`) validated (`6 passed, 0 failed`).
- [x] Living context docs created: `docs/PROJECT_CONTEXT.md`, `docs/VERSIONING.md`, and this `docs/HANDOFF.md`.

---

## 3. Swarm Execution Map (Next Action)

Execute `openspec/changes/rebuild-e2ee-relay-core/tasks.md` using Superpowers TDD (`test-driven-development`) and `verification-before-completion`:

| Wave | Lane | Independent File Ownership | Dependencies |
| :--- | :--- | :--- | :--- |
| **Wave 1 (Parallel)** | **Lane A** — Crypto At-Rest, Storage & `AppContainer` | `crypto/storage/LocalStorageCipher.kt`, `storage/MessageDao.kt`, `core/domain/MessageQueueManager.kt`, `core/domain/SettingsManager.kt`, `pairing/TrustStore.kt`, `AppContainer.kt`, `SecureSmsApplication.kt`, related unit tests | None |
| **Wave 1 (Parallel)** | **Lane B** — Pure E2EE Firebase Relay & `TransportManager` | `p2p/ConnectionUiState.kt`, `p2p/relay/RelayTransport.kt`, `p2p/relay/FirebaseRelayTransport.kt`, `p2p/TransportManager.kt`, delete `p2p/webrtc/*` & `p2p/signaling/*`, `TransportManagerTest.kt` | None (uses `StorageCipher` contract defined in `design.md`) |
| **Wave 2 (Parallel)** | **Lane C** — `ForwardingService`, SMS wiring, Manifest & Gradle | `background/ForwardingService.kt`, `background/BootReceiver.kt`, `sms/*`, `AndroidManifest.xml`, `app/build.gradle.kts`, `gradle/libs.versions.toml` | Lanes A & B |
| **Wave 2 (Parallel)** | **Lane D** — `MainActivity`, Dashboards & CI Workflow | `MainActivity.kt`, `ui/dashboard/SenderDashboardScreen.kt`, `ui/dashboard/ViewerDashboardScreen.kt`, delete `ui/signaling/SignalingScreen.kt`, `.github/workflows/ci.yml` | Lanes A & B |
| **Wave 3 (Final)** | **Lane E** — CI Verify, OpenSpec Archive, Graphify & Release `v1.1.0` | `openspec/specs/**`, `openspec/changes/archive/**`, `app/build.gradle.kts`, `Changelog.kt`, `docs/*.md`, `graphify-out/**` | Lanes C & D |

---

## 4. Environment & Tooling Bootstrap (For Fresh Ephemeral VMs)

If a new chat starts on a fresh VM, reinstall the lightweight CLI tools (takes ~15s) before running commands:
```bash
uv tool install graphifyy
npm install -g --prefix "$HOME/.local" @fission-ai/openspec@latest
export PATH="$HOME/.local/bin:$PATH"
```
- **Knowledge graph:** Already committed in `graphify-out/`. Query with `graphify query "<question>"` / `graphify explain "<symbol>"`; refresh after edits with `graphify update . --force`.
- **Android SDK note:** Ephemeral Linux VMs do **not** have the Android SDK preinstalled. Lane D adds `.github/workflows/ci.yml` so pushing commits to GitHub automatically runs `./gradlew testDebugUnitTest assembleDebug` on `ubuntu-latest` with the full Android SDK. Verify CI passes via `gh run list` / `gh run watch` before archiving and tagging `v1.1.0`.
- **Release rule:** Do **NOT** create or push a `v1.1.0` tag until CI build + unit tests pass and changes are verified/confirmed working (pushing `v*` triggers `.github/workflows/release.yml` to publish the signed APK to users).

# SecureSMSForwarder — Versioning & Release Ledger

This document is the authoritative version history and release-synchronization reference for **SecureSMSForwarder**. Every release MUST keep all version touchpoints in sync.

---

## 1. Current Version Snapshot

| Field | Value | Source File |
| :--- | :--- | :--- |
| **`versionName`** | `1.0.23` | `app/build.gradle.kts` (`defaultConfig.versionName`) |
| **`versionCode`** | `24` | `app/build.gradle.kts` (`defaultConfig.versionCode`) |
| **Latest Git Tag** | `v1.0.23` | `git tag -l --sort=-v:refname` |
| **Room DB Schema** | `4` | `app/src/main/java/com/example/securesmsforwarder/storage/AppDatabase.kt` |
| **Protocol Context** | `SecureSmsProtocol_V1` | `app/src/main/java/com/example/securesmsforwarder/crypto/encryption/MessageEncryption.kt` |
| **Auto-Update Feed** | GitHub Releases (`releases/latest`) | `app/src/main/java/com/example/securesmsforwarder/updater/AppUpdater.kt` |

### Known Version Drift (Tracked for Next Release)
- `MainActivity.kt:32`: Welcome Toast still reads `Welcome to v1.0.22!` (should be updated / bound to `BuildConfig.VERSION_NAME`).
- `public/version.json`: Legacy Firebase Hosting artifact frozen at `1.0.3` (no longer used by `AppUpdater`, which queries GitHub Releases API directly).

---

## 2. Versioning Policy

We follow **Semantic Versioning (`MAJOR.MINOR.PATCH`)** with strictly monotonic integer `versionCode`:
- **PATCH (`1.0.X`):** Bug fixes, reliability improvements, transport hardening, UI polish.
- **MINOR (`1.X.0`):** Backward-compatible new user-facing capabilities or architectural milestones.
- **MAJOR (`X.0.0`):** Breaking protocol/keyset changes or incompatible major rewrites.
- **Git Tag Format:** Always `v<versionName>` (e.g., `v1.0.24`), matching the `on.push.tags: ['v*']` trigger in `.github/workflows/release.yml` and `AppUpdater.kt` (`tag_name.removePrefix("v")`).
- **`versionCode`:** Always increment by `+1` on every release (`24` -> `25` for `v1.0.24`).

---

## 3. Version Synchronization Checklist

Whenever a feature or fix is **confirmed as working**, complete every step in order (per `.agents/AGENTS.md`):

1. **Archive OpenSpec Change:** Sync delta specs into `openspec/specs/`, validate (`openspec validate --specs`), and archive (`openspec archive <change-name>`).
2. **Bump Version:** Increment `versionCode` and update `versionName` in `app/build.gradle.kts`.
3. **Update Welcome Toast:** Ensure `MainActivity.kt` displays the new `versionName` (prefer `v${BuildConfig.VERSION_NAME}`).
4. **Update In-App Changelog:** Add a `ReleaseNote` entry at the top of `Changelog` in `app/src/main/java/com/example/securesmsforwarder/ui/changelog/Changelog.kt`.
5. **Update Context & Version Ledger:**
   - Append the release row to Section 4 of `docs/VERSIONING.md`.
   - Update `Current Version` and resolve any fixed bugs in `docs/PROJECT_CONTEXT.md`.
6. **Refresh Knowledge Graph:** Run `graphify update .` to keep `graphify-out/` synchronized.
7. **Commit:** `git commit -am "Bump version to <new_version>"` (or include feature summary).
8. **Tag Release:** `git tag v<new_version>`
9. **Push:** `git push origin main && git push origin v<new_version>`
10. **Verify CI:** Confirm `.github/workflows/release.yml` succeeds and publishes the signed APK to GitHub Releases.

---

## 4. Release History Ledger

| Version | `versionCode` | Date | Git Tag | OpenSpec Change | Summary |
| :--- | :---: | :--- | :--- | :--- | :--- |
| **1.0.23** | 24 | 2026-09-21 | `v1.0.23` | *(pre-OpenSpec)* | Peer presence heartbeats, safe relay delivery handshake, Android 14 `remoteMessaging` FGS, wakeful `SmsReceiver`, auto-drain on peer online, Sync Now & Retry Failed UI, 25-retry exponential backoff |
| **1.0.22** | 23 | 2026-09-05 | `v1.0.22` | *(pre-OpenSpec)* | Dedicated Disconnect button, one-tap Reconnect button, Connect immediately unpauses |
| **1.0.21** | 22 | 2026-09-04 | `v1.0.21` | *(pre-OpenSpec)* | Dual-tier fail-proof transport: added E2EE Firebase Fallback Relay + multi-port STUN & TCP TURN fallback |
| **1.0.20** | 21 | 2026-08-17 | `v1.0.20` | *(pre-OpenSpec)* | Reverted connection engine to `v1.0.18` for baseline stability |
| **1.0.19** | 20 | 2026-08-16 | `v1.0.19` | *(pre-OpenSpec)* | Flight-mode reconnection, remote ICE candidate buffering, multi-port STUN/TCP TURN |
| **1.0.18** | 19 | 2026-08-14 | `v1.0.18` | *(pre-OpenSpec)* | Added in-app Changelog (`Changelog.kt`) and `.agents/AGENTS.md` release process |
| **1.0.17** | 18 | 2026-08-14 | `v1.0.17` | *(pre-OpenSpec)* | Trickle ICE support, faster network change recovery, instant Connect UI feedback |
| **1.0.16** | 17 | 2026-08-14 | `v1.0.16` | *(pre-OpenSpec)* | Added TURN server fallback and bug fixes |
| **1.0.0 – 1.0.15** | 1 – 16 | 2026-08 | `v1.0` – `v1.0.15` | *(pre-OpenSpec)* | Initial P2P WebRTC SMS forwarder, Tink HPKE crypto, QR pairing, Room queue, GitHub Releases auto-updater |

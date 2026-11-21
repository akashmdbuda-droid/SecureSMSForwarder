# Design: Rebuild Core Transport as Pure E2EE Firebase Relay & Harden Security

## Context
After auditing `v1.0.23` (`versionCode = 24`), root-cause analysis showed that tapping **Connect** fails due to interacting defects across `ForwardingService`, `SettingsManager`, `TransportManager`, `FirebaseSignalingProvider`, and `WebRtcDirectTransport`:
- `ForwardingService.initializeTransport()` instantiated a local `SettingsManager` whose `SharedPreferences.OnSharedPreferenceChangeListener` was garbage-collected (`WeakHashMap`), while `TransportManager.requestConnection()` never cleared `isPaused`.
- `ForwardingService.connectionStateFlow` was a plain nullable `var`, causing Compose UI to freeze on `DISCONNECTED` after async service startup or `ACTION_RESTART`.
- `ForwardingService.NetworkCallback.onLost()` unconditionally called `FirebaseDatabase.getInstance().goOffline()` during Wi-Fi <-> Cellular handoffs.
- `FirebaseSignalingProvider` leaked all 6 RTDB listeners across restarts and swallowed `DatabaseError` (e.g., expired RTDB rules) in `Log.e`.
- `WebRtcDirectTransport` maintained 300+ lines of fragile SDP/ICE/public-TURN (`openrelay.metered.ca`) machinery unnecessary for ~160-byte SMS payloads and weakened privacy by leaking IPs to third-party STUN/TURN servers.
- Local Room storage persisted raw plaintext UTF-8 SMS text (`MessageQueueManager.kt:38`), and `AndroidManifest.xml` had `android:allowBackup="true"`.

User constraints confirmed during brainstorming:
1. **Option B:** Keep repo, signing keystore, auto-updater, Room DB, Tink HPKE identity keys, QR pairing, SMS capture, and Compose UI; rebuild only the transport/service/state core cleanly.
2. **Keep existing pairing active:** Zero re-pairing required on upgrade (`KeyManager` and `TrustStore` formats stay 100% compatible).
3. **Prioritize security:** Remove WebRTC/STUN/TURN attack surface, encrypt Room DB payloads at rest, disable cloud backup, reject legacy unsigned plaintext payloads, and surface Firebase errors clearly.
4. **Swarm-ready architecture:** Clean module boundaries with non-overlapping file ownership for parallel implementation.

---

## Architecture Overview

```
SecureSmsApplication -> AppContainer (singletons, strong refs for process lifetime)
 ├── KeyManager             (existing Android Keystore X25519 HPKE identity — UNCHANGED format)
 ├── MessageEncryption      (existing Tink HPKE SecureSmsProtocol_V1 — UNCHANGED wire crypto)
 ├── LocalStorageCipher     (NEW: Tink AEAD backed by Android Keystore for Room payloads)
 ├── TrustStore             (existing secure_sms_trust_store prefs — UNCHANGED keys, name-safe)
 ├── RoleManager            (existing role prefs)
 ├── SettingsManager        (singleton; strong prefListener reference; notifications default = true)
 ├── AppDatabase / Dao      (existing Room v4 + new getSenderQueueMessages() query)
 └── MessageQueueManager    (uses StorageCipher for encrypt/decrypt at rest + dedup)

ForwardingService (FGS: remoteMessaging|dataSync)
 ├── Companion StateFlow<ConnectionUiState> (stable singleton observed by Compose)
 ├── serviceScope + cancellable transportScope
 ├── Multi-network safe ConnectivityManager.NetworkCallback
 ├── SmsObserver (SENDER only)
 └── TransportManager
      ├── RelayTransport (interface) -> FirebaseRelayTransport (sole transport, clean close())
      ├── Mutex-serialized drainQueue()
      ├── 3.5s retry & backoff loop (up to 25 attempts, capped 60s)
      └── Strict JSON E2EE payload framing (SMS, ACK, WIPE only)
```

---

## Detailed Design Decisions

### 1. Backward-Compatible At-Rest Encryption (`LocalStorageCipher`)
- Define interface `StorageCipher` (`encrypt(plaintext: String): ByteArray`, `decrypt(payload: ByteArray?): String`) so `MessageQueueManager` stays 100% unit-testable on JVM without Android Keystore.
- `LocalStorageCipher(context)` uses Tink `AndroidKeysetManager` with:
  - `KEYSET_NAME = "local_storage_aead_keyset"`
  - `PREF_FILE_NAME = "secure_sms_crypto_prefs"` (same encrypted prefs file, separate keyset name so existing `device_identity_keyset` is untouched)
  - `KeyTemplates.get("AES256_GCM")`
  - `MASTER_KEY_URI = "android-keystore://secure_sms_master_key"` (existing master key)
- Prefix every encrypted byte array with a 7-byte magic header: `"AEADv1:".toByteArray(Charsets.UTF_8)`.
- On `decrypt(payload)`:
  - If `payload == null` or empty -> return `""`.
  - If `payload` starts with `"AEADv1:"` -> decrypt the remaining bytes with Tink `Aead.decrypt(cipherBytes, "RoomMessage_V1".toByteArray())`.
  - Otherwise -> treat as legacy pre-`v1.1.0` plaintext row and decode as UTF-8 (`String(payload, Charsets.UTF_8)`).
- In `MessageQueueManager.enqueueMessage()`: compare `storageCipher.decrypt(existing.encryptedPayload) == parsedSms.body` across the ±30s `recent` window so deduplication remains exact even with randomized AEAD nonces.

### 2. Application Singleton Container (`SecureSmsApplication` + `AppContainer`)
- Add `SecureSmsApplication : Application()` registered via `android:name=".SecureSmsApplication"` in `AndroidManifest.xml`.
- Expose `val appContainer: AppContainer` lazily initialized once per process.
- All components (`MainActivity`, `ForwardingService`, `SmsReceiver`, `SmsObserver`, `SmsHistoryScanner`, `BootReceiver`) obtain `SettingsManager`, `RoleManager`, `TrustStore`, `KeyManager`, `StorageCipher`, `AppDatabase`, and `MessageQueueManager` from `AppContainer`.
- Result: `SettingsManager.prefListener` is NEVER garbage-collected, eliminating the `WeakHashMap` pause/unpause bug permanently.

### 3. Pure E2EE Firebase Relay (`RelayTransport` + `FirebaseRelayTransport`)
- Firebase RTDB paths stay **100% compatible** with existing paired devices (so even if one phone updates slightly before the other, relay delivery continues working!):
  - `presence/<deviceId>` -> `{ online: Boolean, lastSeen: ServerValue.TIMESTAMP, role: String }` + `onDisconnect()` sets `online = false`.
  - `signaling/<deviceId>/wakeup` -> `Long` timestamp ping (deleted on receipt).
  - `messages/<deviceId>/inbox/<safeMessageId>` -> Base64 Tink HPKE ciphertext (deleted only after `onProcessed(true)`).
  - `pairing_requests/<targetDeviceId>/<senderDeviceId>` -> unchanged in `MainActivity`.
- `FirebaseRelayTransport` registers listeners in explicit tracking lists (`valueListeners`, `childListeners`) and detaches EVERY listener in `close()`, also marking local presence offline when paused/closed cleanly.
- Error reporting: every `onCancelled(error)` and `addOnFailureListener` invokes `onErrorListener("Firebase error: ${error.message}")`; successful connection/read clears transient errors.
- Remove `WebRtcDirectTransport.kt`, `ManualSignalingProvider.kt`, `SignalingEnvelope.kt`, `SignalingProvider.kt`, `FirebaseSignalingProvider.kt`, `SignalingScreen.kt`, and `libs.webrtc` from Gradle.

### 4. Reactive `ConnectionUiState` & Guaranteed Connect Flow
- Define:
  ```kotlin
  data class ConnectionUiState(
      val status: String = "DISCONNECTED",
      val isRelayConnected: Boolean = false,
      val isPeerOnline: Boolean = false,
      val peerLastSeen: Long = 0L,
      val isPaused: Boolean = false,
      val lastError: String? = null
  )
  ```
- `ForwardingService.companion`:
  ```kotlin
  private val _connectionUiState = MutableStateFlow(ConnectionUiState())
  val connectionUiState: StateFlow<ConnectionUiState> = _connectionUiState.asStateFlow()
  ```
- Service actions handled in `onStartCommand`:
  - `ACTION_CONNECT`: sets `settingsManager.isConnectionPaused = false`, brings Firebase online (`goOnline()`), initializes transport if null, unpauses `transportManager`, calls `transportManager.requestConnection()`.
  - `ACTION_DISCONNECT`: sets `settingsManager.isConnectionPaused = true`, pauses `transportManager`.
  - `ACTION_RESTART`: tears down `smsObserver`, cancels `transportScope`, closes `transportManager` (which calls `relayTransport.close()`), and calls `initializeTransport()`.
  - `ACTION_DRAIN_QUEUE`: initializes transport if needed and calls `transportManager.triggerQueueDrain()`.
- Tapping **Connect** in `MainActivity` ALWAYS sets `settingsManager.isConnectionPaused = false` and calls `ContextCompat.startForegroundService(this, Intent(..., ACTION_CONNECT))` — guaranteeing recovery even if the OS killed `ForwardingService` or `transportManager` was paused!

### 5. Multi-Network Safe `NetworkCallback`
- In `ForwardingService.registerNetworkCallback()`:
  - `onAvailable(network)`: call `FirebaseDatabase.getInstance().goOnline()` and if `!settingsManager.isConnectionPaused && trustStore.getTrustedDeviceId() != null`, trigger `transportManager?.requestConnection()` after a 1s settle delay inside `serviceScope`.
  - `onLost(network)`: query `cm.getNetworkCapabilities(cm.activeNetwork)`. Only call `FirebaseDatabase.getInstance().goOffline()` if no active network has both `NET_CAPABILITY_INTERNET` and `NET_CAPABILITY_VALIDATED`.

### 6. `TransportManager` Concurrency & Strict Framing
- Guard `drainQueue()` with `private val drainMutex = Mutex()`. Use `drainMutex.withLock { ... }` so overlapping triggers (3.5s loop, SMS receive, peer online, wakeup) never send duplicate copies or race `retryCount`.
- Reject non-JSON decrypted payloads (delete legacy `WIPE:ALL`, `ACK:`, `msgId|body` fallback branches and WebRTC `KEY:` handling).
- Call `relayTransport.close()` inside `TransportManager.close()`.
- In `TransportManager.requestConnection()`: explicitly clear `isPaused = false`, update UI state, call `relayTransport.goOnline()`, `relayTransport.requestWakeup()`, requeue failed messages, and `drainQueue()`.

### 7. Queue Visibility, UI Polish & CI Verification
- `MessageDao.getSenderQueueMessages()` selects `state IN ('QUEUED', 'ENCRYPTED', 'TRANSMITTING', 'PERSISTED_BY_B', 'FAILED') ORDER BY sequenceNumber ASC`. Keep `getPendingMessages()` excluding `FAILED` so background auto-drain skips failed items until requeued.
- `SenderMainTab`: include `isPeerOffline` alongside `isConnected || isConnecting` for Disconnect/Reconnect controls, and render an error card when `uiState.lastError != null`.
- `ViewerMainTab`: render an error card when `uiState.lastError != null`.
- `ViewerInboxTab`: mark unread messages as read inside `LaunchedEffect(msg.id)`.
- `ConnectionTab`: display `Encrypted Relay (HTTPS/WSS)` and `Tink HPKE + Room AEAD` accurately (removing Direct P2P strings).
- `MainActivity`: use `"Welcome to v${BuildConfig.VERSION_NAME}!"` toast; call `ACTION_RESTART` on unpair and `stopService()` on role reset.
- `.github/workflows/ci.yml`: run `./gradlew testDebugUnitTest assembleDebug` on push to `main` and pull requests using `actions/setup-java@v4` (Temurin 17).

---

## Risks & Mitigations
- **Upgrade compatibility with an older peer during rollout:** Firebase RTDB paths (`presence/`, `signaling/<id>/wakeup`, `messages/<id>/inbox/`) and Tink HPKE JSON payloads (`type = SMS | ACK | WIPE`) are 100% preserved, so a `v1.1.0` device can still talk to a `v1.0.23` device via relay while both phones update!
- **No local Android SDK in ephemeral cloud VMs:** Adding `.github/workflows/ci.yml` ensures every swarm PR/commit gets full Gradle compilation + JUnit/Robolectric verification in GitHub Actions before tagging `v1.1.0`.
- **Android Keystore in Robolectric unit tests:** Abstracting `StorageCipher` behind an interface lets unit tests run deterministically on JVM with a `FakeStorageCipher`, while `LocalStorageCipher` is clean and minimal.

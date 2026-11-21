package com.example.securesmsforwarder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.securesmsforwarder.background.ForwardingService
import com.example.securesmsforwarder.core.domain.DeviceRole
import com.example.securesmsforwarder.core.util.BiometricHelper
import com.example.securesmsforwarder.crypto.identity.KeyManager
import com.example.securesmsforwarder.sms.SmsHistoryScanner
import com.example.securesmsforwarder.ui.changelog.ChangelogDialog
import com.example.securesmsforwarder.ui.dashboard.DisplayMessage
import com.example.securesmsforwarder.ui.dashboard.SenderDashboardScreen
import com.example.securesmsforwarder.ui.dashboard.ViewerDashboardScreen
import com.example.securesmsforwarder.ui.pairing.QrPairingScreen
import com.example.securesmsforwarder.ui.setup.SetupScreen
import com.example.securesmsforwarder.ui.theme.SecureSmsForwarderTheme
import com.example.securesmsforwarder.updater.AppUpdater
import com.example.securesmsforwarder.updater.UpdateInfo
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Toast.makeText(this, "Welcome to v${BuildConfig.VERSION_NAME}!", Toast.LENGTH_LONG).show()

        enableEdgeToEdge()
        val appContainer = SecureSmsApplication.container(this)
        val roleManager = appContainer.roleManager
        val keyManager = appContainer.keyManager
        val db = appContainer.database
        val settingsManager = appContainer.settingsManager
        val trustStore = appContainer.trustStore
        val queueManager = appContainer.messageQueueManager
        val storageCipher = appContainer.storageCipher

        if (roleManager.currentRole != DeviceRole.UNASSIGNED) {
            val serviceIntent = Intent(this, ForwardingService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
        }

        setContent {
            val isSystemDark = isSystemInDarkTheme()
            val forceDark by settingsManager.forceDarkModeFlow.collectAsState()

            SecureSmsForwarderTheme(darkTheme = forceDark || isSystemDark) {
                var currentRole by remember { mutableStateOf(roleManager.currentRole) }
                var showPairingScreen by remember { mutableStateOf(false) }
                var serializedKey by remember { mutableStateOf("") }
                var fingerprint by remember { mutableStateOf("") }
                var trustedDevicesCount by remember { mutableStateOf(0) }
                var showNameDialog by remember { mutableStateOf(false) }
                var deviceName by remember { mutableStateOf("") }
                var lastSyncedTimestamp by remember { mutableStateOf(0L) }
                val biometricHelper = remember { BiometricHelper }
                val appUpdater = remember { AppUpdater(this@MainActivity) }
                var showUpdateDialog by remember { mutableStateOf<UpdateInfo?>(null) }
                var showChangelog by remember { mutableStateOf(false) }

                LaunchedEffect(Unit) {
                    if (settingsManager.lastSeenVersionCode < BuildConfig.VERSION_CODE) {
                        showChangelog = true
                        settingsManager.lastSeenVersionCode = BuildConfig.VERSION_CODE
                    }
                    fingerprint = keyManager.getHumanFriendlyFingerprint()
                    trustedDevicesCount = if (trustStore.getTrustedDeviceId() != null) 1 else 0
                    deviceName = trustStore.getTrustedDeviceName()
                    lastSyncedTimestamp = trustStore.getLastSyncedTimestamp()

                    launch(Dispatchers.IO) {
                        val updateInfo = appUpdater.checkForUpdates()
                        if (updateInfo.isUpdateAvailable) {
                            withContext(Dispatchers.Main) {
                                showUpdateDialog = updateInfo
                            }
                        }
                    }
                }

                showUpdateDialog?.let { updateInfo ->
                    AlertDialog(
                        onDismissRequest = { showUpdateDialog = null },
                        title = { Text("Update Available") },
                        text = { Text("Version ${updateInfo.latestVersionName} is available. Would you like to download and install it?") },
                        confirmButton = {
                            TextButton(onClick = {
                                updateInfo.downloadUrl?.let { url ->
                                    appUpdater.downloadAndInstall(url)
                                }
                                showUpdateDialog = null
                            }) {
                                Text("Update")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showUpdateDialog = null }) {
                                Text("Later")
                            }
                        }
                    )
                }

                if (showChangelog) {
                    ChangelogDialog(
                        onDismiss = { showChangelog = false }
                    )
                }

                val onPairDeviceClick: () -> Unit = {
                    biometricHelper.promptBiometricAuth(
                        activity = this@MainActivity,
                        title = "Pair Device",
                        subtitle = "Verify identity to pair a new device",
                        onSuccess = {
                            lifecycleScope.launch {
                                serializedKey = keyManager.getSerializedPublicKey()
                                fingerprint = keyManager.getHumanFriendlyFingerprint()
                                showPairingScreen = true
                            }
                        },
                        onError = { err ->
                            Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                val onRemoveTrustClick: () -> Unit = {
                    biometricHelper.promptBiometricAuth(
                        activity = this@MainActivity,
                        title = "Remove Trusted Device",
                        subtitle = "Verify to unpair this device",
                        onSuccess = {
                            trustStore.revokePeer()
                            trustedDevicesCount = 0
                            deviceName = trustStore.getTrustedDeviceName()
                            val restartIntent = Intent(this@MainActivity, ForwardingService::class.java).apply {
                                action = ForwardingService.ACTION_RESTART
                            }
                            ContextCompat.startForegroundService(this@MainActivity, restartIntent)
                            Toast.makeText(this@MainActivity, "Device unpaired", Toast.LENGTH_SHORT).show()
                        },
                        onError = {
                            Toast.makeText(this@MainActivity, "Authentication failed", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                val startConnectFlow: (String) -> Unit = { toastText ->
                    if (trustStore.getTrustedDeviceId() != null) {
                        settingsManager.isConnectionPaused = false
                        val connectIntent = Intent(this@MainActivity, ForwardingService::class.java).apply {
                            action = ForwardingService.ACTION_CONNECT
                        }
                        ContextCompat.startForegroundService(this@MainActivity, connectIntent)
                        Toast.makeText(this@MainActivity, toastText, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Please pair a device first in the Security tab",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                val onDisconnectClick: () -> Unit = {
                    settingsManager.isConnectionPaused = true
                    val disconnectIntent = Intent(this@MainActivity, ForwardingService::class.java).apply {
                        action = ForwardingService.ACTION_DISCONNECT
                    }
                    ContextCompat.startForegroundService(this@MainActivity, disconnectIntent)
                    Toast.makeText(this@MainActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                }

                val onResetRoleClick: () -> Unit = {
                    biometricHelper.promptBiometricAuth(
                        activity = this@MainActivity,
                        title = "Reset Role",
                        subtitle = "Verify identity to reset device role",
                        onSuccess = {
                            roleManager.currentRole = DeviceRole.UNASSIGNED
                            currentRole = DeviceRole.UNASSIGNED
                            stopService(Intent(this@MainActivity, ForwardingService::class.java))
                        },
                        onError = { err ->
                            Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                val onClearMessagesClick: () -> Unit = {
                    biometricHelper.promptBiometricAuth(
                        activity = this@MainActivity,
                        title = "Clear Messages",
                        subtitle = "Verify identity to wipe all messages",
                        onSuccess = {
                            lifecycleScope.launch(Dispatchers.IO) {
                                queueManager.deleteAll()
                                val transportManager = ForwardingService.activeTransportManager
                                if (transportManager != null) {
                                    transportManager.sendWipeCommand()
                                } else {
                                    val wipeIntent = Intent(this@MainActivity, ForwardingService::class.java).apply {
                                        action = ForwardingService.ACTION_WIPE_PEER
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, wipeIntent)
                                }
                            }
                        },
                        onError = { err ->
                            Toast.makeText(this@MainActivity, err, Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                val onRequestBatteryOptimization: () -> Unit = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${this@MainActivity.packageName}")
                    }
                    try {
                        startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Action not supported on this device.", Toast.LENGTH_SHORT).show()
                    }
                }

                val triggerQueueDrainOrStart: () -> Unit = {
                    val transportManager = ForwardingService.activeTransportManager
                    if (transportManager != null) {
                        transportManager.triggerQueueDrain()
                    } else {
                        val drainIntent = Intent(this@MainActivity, ForwardingService::class.java).apply {
                            action = ForwardingService.ACTION_DRAIN_QUEUE
                        }
                        ContextCompat.startForegroundService(this@MainActivity, drainIntent)
                    }
                }

                if (showPairingScreen) {
                    val localDeviceId = remember { keyManager.getPublicKeyFingerprint() }
                    val localKeyJson = remember { keyManager.getSerializedPublicKey() }

                    LaunchedEffect(showPairingScreen) {
                        val ref = FirebaseDatabase.getInstance().getReference("pairing_requests/$localDeviceId")
                        val listener = object : ValueEventListener {
                            override fun onDataChange(snapshot: DataSnapshot) {
                                snapshot.children.forEach { child ->
                                    val remoteDeviceId = child.key ?: return@forEach
                                    val remoteKeyJson = child.getValue(String::class.java) ?: return@forEach

                                    trustStore.addTrustedPeer(remoteDeviceId, remoteKeyJson)
                                    trustedDevicesCount = 1
                                    deviceName = trustStore.getTrustedDeviceName()
                                    showNameDialog = true
                                    showPairingScreen = false

                                    child.ref.removeValue()

                                    val serviceIntent = Intent(this@MainActivity, ForwardingService::class.java).apply {
                                        action = ForwardingService.ACTION_RESTART
                                    }
                                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                                }
                            }

                            override fun onCancelled(error: DatabaseError) {
                                Toast.makeText(this@MainActivity, "Firebase Error: ${error.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                        ref.addValueEventListener(listener)
                        try {
                            awaitCancellation()
                        } finally {
                            ref.removeEventListener(listener)
                        }
                    }

                    QrPairingScreen(
                        serializedPublicKey = serializedKey,
                        fingerprint = fingerprint,
                        onQrScanned = { scannedKeyJson ->
                            try {
                                val cleanJson = scannedKeyJson.trim()
                                val peerFingerprint = KeyManager.computeFingerprint(cleanJson)
                                trustStore.addTrustedPeer(peerFingerprint, cleanJson)

                                trustedDevicesCount = 1
                                deviceName = trustStore.getTrustedDeviceName()
                                showPairingScreen = false
                                showNameDialog = true
                                Toast.makeText(this@MainActivity, "Scanned successfully! Connecting to peer...", Toast.LENGTH_SHORT).show()

                                val serviceIntent = Intent(this@MainActivity, ForwardingService::class.java).apply {
                                    action = ForwardingService.ACTION_RESTART
                                }
                                ContextCompat.startForegroundService(this@MainActivity, serviceIntent)

                                val ref = FirebaseDatabase.getInstance().getReference("pairing_requests/$peerFingerprint/$localDeviceId")
                                ref.setValue(localKeyJson).addOnSuccessListener {
                                    Toast.makeText(this@MainActivity, "Pairing request sent to peer!", Toast.LENGTH_SHORT).show()
                                }.addOnFailureListener {
                                    Toast.makeText(this@MainActivity, "Network error: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this@MainActivity, "Invalid QR code", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    return@SecureSmsForwarderTheme
                }

                val rawUiState by ForwardingService.connectionUiState.collectAsState()
                val isConnectionPaused by settingsManager.connectionPausedFlow.collectAsState()
                val connectionUiState = if (isConnectionPaused && rawUiState.status != "PAUSED") {
                    rawUiState.copy(status = "PAUSED", isPaused = true)
                } else {
                    rawUiState
                }

                val allMessages = db.messageDao().getAllMessages().collectAsState(initial = emptyList())
                val senderQueueMessages = queueManager.getSenderQueueMessagesFlow().collectAsState(initial = emptyList())

                when (currentRole) {
                    DeviceRole.UNASSIGNED -> {
                        SetupScreen(onRoleSelected = { role ->
                            roleManager.currentRole = role
                            currentRole = role
                            val serviceIntent = Intent(this@MainActivity, ForwardingService::class.java)
                            ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                        })
                    }
                    DeviceRole.SENDER -> {
                        LaunchedEffect(Unit) {
                            val perms = mutableListOf<String>()
                            if (checkSelfPermission(android.Manifest.permission.RECEIVE_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                perms.add(android.Manifest.permission.RECEIVE_SMS)
                            }
                            if (checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                perms.add(android.Manifest.permission.READ_SMS)
                            }
                            if (perms.isNotEmpty()) {
                                requestPermissions(perms.toTypedArray(), 102)
                            }
                        }

                        SenderDashboardScreen(
                            connectionUiState = connectionUiState,
                            queuedMessages = senderQueueMessages.value,
                            identityFingerprint = fingerprint,
                            trustedDevicesCount = trustedDevicesCount,
                            deviceName = deviceName,
                            lastSyncedTimestamp = lastSyncedTimestamp,
                            settingsManager = settingsManager,
                            onPairDeviceClick = onPairDeviceClick,
                            onRemoveTrustClick = onRemoveTrustClick,
                            onEstablishConnectionClick = { startConnectFlow("Connecting...") },
                            onDisconnectClick = onDisconnectClick,
                            onSendTestMessageClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    queueManager.enqueueTestMessage()
                                    triggerQueueDrainOrStart()
                                }
                            },
                            onRetryFailedClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val count = queueManager.requeueFailedMessages()
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "Re-queued $count messages", Toast.LENGTH_SHORT).show()
                                    }
                                    triggerQueueDrainOrStart()
                                }
                            },
                            onResetRoleClick = onResetRoleClick,
                            onClearMessagesClick = onClearMessagesClick,
                            onSyncHistoryClick = { start, end ->
                                if (checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    val ts = System.currentTimeMillis()
                                    trustStore.setLastSyncedTimestamp(ts)
                                    lastSyncedTimestamp = ts
                                    SmsHistoryScanner.syncHistory(this@MainActivity, start, end)
                                } else {
                                    requestPermissions(arrayOf(android.Manifest.permission.READ_SMS), 101)
                                    // User will have to click sync again after granting for now
                                }
                            },
                            onRequestBatteryOptimization = onRequestBatteryOptimization
                        )
                    }
                    DeviceRole.VIEWER -> {
                        LaunchedEffect(Unit) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 103)
                                }
                            }
                        }

                        val displayMessages = remember(allMessages.value) {
                            val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
                            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
                            allMessages.value
                                .filter { it.forwardingDevice.isNotEmpty() }
                                .map { entity ->
                                    val date = Date(entity.timestamp)
                                    DisplayMessage(
                                        id = entity.messageId,
                                        sender = entity.senderDeviceId,
                                        body = runCatching { storageCipher.decrypt(entity.encryptedPayload) }
                                            .getOrDefault("[Unable to decrypt message]"),
                                        time = timeFormat.format(date),
                                        date = dateFormat.format(date),
                                        timestamp = entity.timestamp,
                                        isRead = entity.isRead,
                                        forwardingDevice = entity.forwardingDevice
                                    )
                                }
                        }

                        ViewerDashboardScreen(
                            connectionUiState = connectionUiState,
                            messages = displayMessages,
                            identityFingerprint = fingerprint,
                            trustedDevicesCount = trustedDevicesCount,
                            deviceName = deviceName,
                            settingsManager = settingsManager,
                            onPairDeviceClick = onPairDeviceClick,
                            onRemoveTrustClick = onRemoveTrustClick,
                            onEstablishConnectionClick = { startConnectFlow("Connecting...") },
                            onSyncClick = { startConnectFlow("Syncing with Sender...") },
                            onDisconnectClick = onDisconnectClick,
                            onClearMessagesClick = onClearMessagesClick,
                            onResetRoleClick = onResetRoleClick,
                            onRequestBatteryOptimization = onRequestBatteryOptimization,
                            onMessageClick = { msgId ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.messageDao().markAsRead(msgId)
                                }
                            }
                        )
                    }
                }

                if (showNameDialog) {
                    var deviceNameInput by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showNameDialog = false },
                        title = { Text("Name this device") },
                        text = {
                            OutlinedTextField(
                                value = deviceNameInput,
                                onValueChange = { deviceNameInput = it },
                                label = { Text("Device Name (e.g. John's iPad)") }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val finalName = if (deviceNameInput.isBlank()) "Paired Device" else deviceNameInput
                                trustStore.setTrustedDeviceName(finalName)
                                deviceName = finalName
                                showNameDialog = false
                            }) {
                                Text("Save")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showNameDialog = false }) {
                                Text("Skip")
                            }
                        }
                    )
                }
            }
        }
    }
}
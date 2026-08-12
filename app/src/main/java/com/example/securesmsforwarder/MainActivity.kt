package com.example.securesmsforwarder

import android.os.Bundle
// import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import com.example.securesmsforwarder.core.domain.DeviceRole
import com.example.securesmsforwarder.core.domain.RoleManager
import com.example.securesmsforwarder.crypto.identity.KeyManager
import com.example.securesmsforwarder.ui.dashboard.SenderDashboardScreen
import com.example.securesmsforwarder.ui.dashboard.ViewerDashboardScreen
import com.example.securesmsforwarder.storage.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.securesmsforwarder.ui.pairing.QrPairingScreen
import com.example.securesmsforwarder.ui.setup.SetupScreen
import com.example.securesmsforwarder.ui.theme.SecureSmsForwarderTheme
import kotlinx.coroutines.launch

import androidx.fragment.app.FragmentActivity

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        android.widget.Toast.makeText(this, "✅ V1.0.13 INSTALLED! AUTO-UPDATER IS PERFECT!", android.widget.Toast.LENGTH_LONG).show()

        enableEdgeToEdge()
        val roleManager = RoleManager(this)
        val keyManager = KeyManager(this)
        val db = AppDatabase.getDatabase(this)

        if (roleManager.currentRole != DeviceRole.UNASSIGNED) {
            val serviceIntent = android.content.Intent(this, com.example.securesmsforwarder.background.ForwardingService::class.java)
            startForegroundService(serviceIntent)
        }

        setContent {
            val settingsManager = remember { com.example.securesmsforwarder.core.domain.SettingsManager(this) }
            val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val forceDark by settingsManager.forceDarkModeFlow.collectAsState()
            
            SecureSmsForwarderTheme(darkTheme = forceDark || isSystemDark) {
                var currentRole by remember { mutableStateOf(roleManager.currentRole) }
                var showPairingScreen by remember { mutableStateOf(false) }
                var showSignalingScreen by remember { mutableStateOf(false) }
                var serializedKey by remember { mutableStateOf("") }
                var fingerprint by remember { mutableStateOf("") }
                var trustedDevicesCount by remember { mutableStateOf(0) }
                var showNameDialog by remember { mutableStateOf(false) }
                var showUnpauseDialog by remember { mutableStateOf(false) }
                var deviceName by remember { mutableStateOf("") }
                var lastSyncedTimestamp by remember { mutableStateOf(0L) }
                val biometricHelper = remember { com.example.securesmsforwarder.core.util.BiometricHelper }
                val appUpdater = remember { com.example.securesmsforwarder.updater.AppUpdater(this@MainActivity) }
                var showUpdateDialog by remember { mutableStateOf<com.example.securesmsforwarder.updater.UpdateInfo?>(null) }
                
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    fingerprint = keyManager.getHumanFriendlyFingerprint()
                    val trustStore = com.example.securesmsforwarder.pairing.TrustStore(this@MainActivity)
                    trustedDevicesCount = if (trustStore.getTrustedDeviceId() != null) 1 else 0
                    deviceName = trustStore.getTrustedDeviceName()
                    lastSyncedTimestamp = trustStore.getLastSyncedTimestamp()
                    
                    launch(Dispatchers.IO) {
                        val updateInfo = appUpdater.checkForUpdates()
                        if (updateInfo.isUpdateAvailable) {
                            showUpdateDialog = updateInfo
                        }
                    }
                }

                showUpdateDialog?.let { updateInfo ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showUpdateDialog = null },
                        title = { androidx.compose.material3.Text("Update Available") },
                        text = { androidx.compose.material3.Text("Version ${updateInfo.latestVersionName} is available. Would you like to download and install it?") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                updateInfo.downloadUrl?.let { url ->
                                    appUpdater.downloadAndInstall(url)
                                }
                                showUpdateDialog = null
                            }) {
                                androidx.compose.material3.Text("Update")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showUpdateDialog = null }) {
                                androidx.compose.material3.Text("Later")
                            }
                        }
                    )
                }

                val onRemoveTrustClick: () -> Unit = {
                    biometricHelper.promptBiometricAuth(
                        activity = this@MainActivity,
                        title = "Remove Trusted Device",
                        subtitle = "Verify to unpair this device",
                        onSuccess = {
                            val trustStore = com.example.securesmsforwarder.pairing.TrustStore(this@MainActivity)
                            trustStore.revokePeer()
                            trustedDevicesCount = 0
                            android.widget.Toast.makeText(this@MainActivity, "Device unpaired", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        onError = {
                            android.widget.Toast.makeText(this@MainActivity, "Authentication failed", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                if (showSignalingScreen) {
                    val activeProvider = com.example.securesmsforwarder.background.ForwardingService.activeSignalingProvider
                    if (activeProvider == null) {
                        // Service isn't running or initialized yet
                        android.widget.Toast.makeText(this, "Signaling Service not running", android.widget.Toast.LENGTH_SHORT).show()
                        showSignalingScreen = false
                    } else {
                        val trustStore = com.example.securesmsforwarder.pairing.TrustStore(this)
                        com.example.securesmsforwarder.ui.signaling.SignalingScreen(
                            signalingProvider = activeProvider,
                            role = currentRole,
                            trustedDeviceId = trustStore.getTrustedDeviceId() ?: "",
                            onConnectionEstablished = {
                                showSignalingScreen = false
                            },
                            onBack = {
                                showSignalingScreen = false
                            }
                        )
                    }
                    return@SecureSmsForwarderTheme
                }

                if (showPairingScreen) {
                    val localDeviceId = keyManager.getPublicKeyFingerprint()
                    val localKeyJson = keyManager.getSerializedPublicKey()
                    val trustStore = com.example.securesmsforwarder.pairing.TrustStore(this@MainActivity)

                    androidx.compose.runtime.LaunchedEffect(showPairingScreen) {
                        val ref = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("pairing_requests/$localDeviceId")
                        val listener = object : com.google.firebase.database.ValueEventListener {
                            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                                snapshot.children.forEach { child ->
                                    val remoteDeviceId = child.key ?: return@forEach
                                    val remoteKeyJson = child.getValue(String::class.java) ?: return@forEach
                                    
                                    val trustStore = com.example.securesmsforwarder.pairing.TrustStore(this@MainActivity)
                                    trustStore.addTrustedPeer(remoteDeviceId, remoteKeyJson)
                                    trustedDevicesCount = 1
                                    showNameDialog = true
                                    showPairingScreen = false
                                    
                                    child.ref.removeValue()
                                    
                                    val serviceIntent = android.content.Intent(this@MainActivity, com.example.securesmsforwarder.background.ForwardingService::class.java).apply {
                                        action = "ACTION_RESTART"
                                    }
                                    startForegroundService(serviceIntent)
                                }
                            }
                            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                                android.widget.Toast.makeText(this@MainActivity, "Firebase Error: ${error.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                        ref.addValueEventListener(listener)
                        try {
                            kotlinx.coroutines.awaitCancellation()
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
                                val hashBytes = java.security.MessageDigest.getInstance("SHA-256").digest(cleanJson.toByteArray(Charsets.UTF_8))
                                val peerFingerprint = hashBytes.take(6).joinToString("") { "%02X".format(it) }
                                trustStore.addTrustedPeer(peerFingerprint, cleanJson)
                                
                                trustedDevicesCount = 1
                                showPairingScreen = false
                                showNameDialog = true
                                android.widget.Toast.makeText(this@MainActivity, "Scanned successfully! Connecting to peer...", android.widget.Toast.LENGTH_SHORT).show()
                                
                                val serviceIntent = android.content.Intent(this@MainActivity, com.example.securesmsforwarder.background.ForwardingService::class.java).apply {
                                    action = "ACTION_RESTART"
                                }
                                startForegroundService(serviceIntent)
                                
                                val ref = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("pairing_requests/$peerFingerprint/$localDeviceId")
                                ref.setValue(localKeyJson).addOnSuccessListener {
                                    android.widget.Toast.makeText(this@MainActivity, "Pairing request sent to peer!", android.widget.Toast.LENGTH_SHORT).show()
                                }.addOnFailureListener {
                                    android.widget.Toast.makeText(this@MainActivity, "Network error: ${it.message}", android.widget.Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@MainActivity, "Invalid QR code", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    return@SecureSmsForwarderTheme
                }

                if (showUnpauseDialog) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showUnpauseDialog = false },
                        title = { androidx.compose.material3.Text("Connection Paused") },
                        text = { androidx.compose.material3.Text("The connection is currently paused to save battery. Would you like to unpause and connect now?") },
                        confirmButton = {
                            androidx.compose.material3.TextButton(
                                onClick = {
                                    settingsManager.isConnectionPaused = false
                                    showUnpauseDialog = false
                                    android.widget.Toast.makeText(this@MainActivity, "Connection resumed", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                androidx.compose.material3.Text("Unpause & Connect")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showUnpauseDialog = false }) {
                                androidx.compose.material3.Text("Cancel")
                            }
                        }
                    )
                }

                val isConnectionPaused by settingsManager.connectionPausedFlow.collectAsState()
                val connectionStateFlow = com.example.securesmsforwarder.background.ForwardingService.connectionStateFlow
                val rawConnectionStatus = connectionStateFlow?.collectAsState()?.value ?: "DISCONNECTED"
                val connectionStatus = if (isConnectionPaused) "PAUSED" else rawConnectionStatus
                
                val allMessages = db.messageDao().getAllMessages().collectAsState(initial = emptyList())
                val pendingMessages = db.messageDao().getPendingMessages().collectAsState(initial = emptyList())

                when (currentRole) {
                    DeviceRole.UNASSIGNED -> {
                        SetupScreen(onRoleSelected = { role ->
                            roleManager.currentRole = role
                            currentRole = role
                            val serviceIntent = android.content.Intent(this@MainActivity, com.example.securesmsforwarder.background.ForwardingService::class.java)
                            startForegroundService(serviceIntent)
                        })
                    }
                    DeviceRole.SENDER -> {
                        val trustStore = com.example.securesmsforwarder.pairing.TrustStore(this)
                        
                        androidx.compose.runtime.LaunchedEffect(Unit) {
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
                            connectionStatus = connectionStatus,
                            queuedMessages = pendingMessages.value,
                            identityFingerprint = fingerprint,
                            trustedDevicesCount = trustedDevicesCount,
                            deviceName = deviceName,
                            lastSyncedTimestamp = lastSyncedTimestamp,
                            settingsManager = settingsManager,
                            onPairDeviceClick = { 
                                com.example.securesmsforwarder.core.util.BiometricHelper.promptBiometricAuth(
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
                                    onError = { err -> android.widget.Toast.makeText(this@MainActivity, err, android.widget.Toast.LENGTH_SHORT).show() }
                                )
                            },
                            onRemoveTrustClick = onRemoveTrustClick,
                            onEstablishConnectionClick = {
                                if (trustStore.getTrustedDeviceId() != null) {
                                    val transportManager = com.example.securesmsforwarder.background.ForwardingService.activeTransportManager
                                    if (transportManager != null) {
                                        transportManager.requestConnection()
                                        android.widget.Toast.makeText(this@MainActivity, "Reconnecting...", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(this@MainActivity, "Signaling Service not running", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    showSignalingScreen = true
                                }
                            },
                            onSendTestMessageClick = {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val queueManager = com.example.securesmsforwarder.core.domain.MessageQueueManager(db.messageDao())
                                    queueManager.enqueueTestMessage()
                                }
                            },
                            onResetRoleClick = {
                                com.example.securesmsforwarder.core.util.BiometricHelper.promptBiometricAuth(
                                    activity = this@MainActivity,
                                    title = "Reset Role",
                                    subtitle = "Verify identity to reset device role",
                                    onSuccess = {
                                        roleManager.currentRole = DeviceRole.UNASSIGNED
                                        currentRole = DeviceRole.UNASSIGNED
                                    },
                                    onError = { err -> android.widget.Toast.makeText(this@MainActivity, err, android.widget.Toast.LENGTH_SHORT).show() }
                                )
                            },
                            onClearMessagesClick = {
                                com.example.securesmsforwarder.core.util.BiometricHelper.promptBiometricAuth(
                                    activity = this@MainActivity,
                                    title = "Clear Messages",
                                    subtitle = "Verify identity to wipe all messages",
                                    onSuccess = {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            db.messageDao().deleteAll()
                                            com.example.securesmsforwarder.background.ForwardingService.activeTransportManager?.sendWipeCommand()
                                        }
                                    },
                                    onError = { err -> android.widget.Toast.makeText(this@MainActivity, err, android.widget.Toast.LENGTH_SHORT).show() }
                                )
                            },
                            onSyncHistoryClick = { start, end ->
                                if (checkSelfPermission(android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    val ts = System.currentTimeMillis()
                                    com.example.securesmsforwarder.pairing.TrustStore(this@MainActivity).setLastSyncedTimestamp(ts)
                                    lastSyncedTimestamp = ts
                                    com.example.securesmsforwarder.sms.SmsHistoryScanner.syncHistory(this@MainActivity, start, end)
                                } else {
                                    requestPermissions(arrayOf(android.Manifest.permission.READ_SMS), 101)
                                    // User will have to click sync again after granting for now
                                }
                            },
                            onRequestBatteryOptimization = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                intent.data = android.net.Uri.parse("package:${this@MainActivity.packageName}")
                                try {
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(this@MainActivity, "Action not supported on this device.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                    DeviceRole.VIEWER -> {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 103)
                                }
                            }
                        }
                        
                        val trustStore = com.example.securesmsforwarder.pairing.TrustStore(this)
                        val displayMessages = allMessages.value
                            .filter { it.forwardingDevice.isNotEmpty() }
                            .map { entity ->
                            com.example.securesmsforwarder.ui.dashboard.DisplayMessage(
                                id = entity.messageId,
                                sender = entity.senderDeviceId,
                                body = String(entity.encryptedPayload ?: ByteArray(0), Charsets.UTF_8),
                                time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(entity.timestamp)),
                                date = java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.getDefault()).format(java.util.Date(entity.timestamp)),
                                timestamp = entity.timestamp,
                                isRead = entity.isRead,
                                forwardingDevice = entity.forwardingDevice
                            )
                        }
                        
                        ViewerDashboardScreen(
                            connectionStatus = connectionStatus,
                            messages = displayMessages,
                            identityFingerprint = fingerprint,
                            trustedDevicesCount = trustedDevicesCount,
                            deviceName = deviceName,
                            settingsManager = settingsManager,
                            onPairDeviceClick = {
                                com.example.securesmsforwarder.core.util.BiometricHelper.promptBiometricAuth(
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
                                    onError = { err -> android.widget.Toast.makeText(this@MainActivity, err, android.widget.Toast.LENGTH_SHORT).show() }
                                )
                            },
                            onRemoveTrustClick = onRemoveTrustClick,
                            onEstablishConnectionClick = {
                                if (settingsManager.isConnectionPaused) {
                                    showUnpauseDialog = true
                                } else if (trustStore.getTrustedDeviceId() != null) {
                                    val transportManager = com.example.securesmsforwarder.background.ForwardingService.activeTransportManager
                                    if (transportManager != null) {
                                        transportManager.requestConnection()
                                        android.widget.Toast.makeText(this@MainActivity, "Reconnecting...", android.widget.Toast.LENGTH_SHORT).show()
                                    } else {
                                        android.widget.Toast.makeText(this@MainActivity, "Signaling Service not running", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    showSignalingScreen = true
                                }
                            },
                            onClearMessagesClick = {
                                com.example.securesmsforwarder.core.util.BiometricHelper.promptBiometricAuth(
                                    activity = this@MainActivity,
                                    title = "Clear Messages",
                                    subtitle = "Verify identity to wipe all messages",
                                    onSuccess = {
                                        lifecycleScope.launch(Dispatchers.IO) {
                                            db.messageDao().deleteAll()
                                            com.example.securesmsforwarder.background.ForwardingService.activeTransportManager?.sendWipeCommand()
                                        }
                                    },
                                    onError = { err -> android.widget.Toast.makeText(this@MainActivity, err, android.widget.Toast.LENGTH_SHORT).show() }
                                )
                            },
                            onResetRoleClick = {
                                com.example.securesmsforwarder.core.util.BiometricHelper.promptBiometricAuth(
                                    activity = this@MainActivity,
                                    title = "Reset Role",
                                    subtitle = "Verify identity to reset device role",
                                    onSuccess = {
                                        roleManager.currentRole = DeviceRole.UNASSIGNED
                                        currentRole = DeviceRole.UNASSIGNED
                                    },
                                    onError = { err -> android.widget.Toast.makeText(this@MainActivity, err, android.widget.Toast.LENGTH_SHORT).show() }
                                )
                            },
                            onRequestBatteryOptimization = {
                                val intent = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                intent.data = android.net.Uri.parse("package:${this@MainActivity.packageName}")
                                try {
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(this@MainActivity, "Action not supported on this device.", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            },
                            onMessageClick = { msgId ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    db.messageDao().markAsRead(msgId)
                                }
                            }
                        )
                    }
                }

                if (showNameDialog) {
                    var deviceNameInput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { showNameDialog = false },
                        title = { androidx.compose.material3.Text("Name this device") },
                        text = {
                            androidx.compose.material3.OutlinedTextField(
                                value = deviceNameInput,
                                onValueChange = { deviceNameInput = it },
                                label = { androidx.compose.material3.Text("Device Name (e.g. John's iPad)") }
                            )
                        },
                        confirmButton = {
                            androidx.compose.material3.TextButton(onClick = {
                                val trustStore = com.example.securesmsforwarder.pairing.TrustStore(this@MainActivity)
                                val finalName = if (deviceNameInput.isBlank()) "Paired Device" else deviceNameInput
                                trustStore.setTrustedDeviceName(finalName)
                                deviceName = finalName
                                showNameDialog = false
                            }) {
                                androidx.compose.material3.Text("Save")
                            }
                        },
                        dismissButton = {
                            androidx.compose.material3.TextButton(onClick = { showNameDialog = false }) {
                                androidx.compose.material3.Text("Skip")
                            }
                        }
                    )
                }
            }
        }
    }
}
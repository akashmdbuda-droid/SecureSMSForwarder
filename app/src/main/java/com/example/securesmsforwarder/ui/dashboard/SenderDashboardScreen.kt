package com.example.securesmsforwarder.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.securesmsforwarder.core.domain.SettingsManager
import com.example.securesmsforwarder.p2p.ConnectionUiState
import com.example.securesmsforwarder.storage.MessageEntity
import com.example.securesmsforwarder.storage.MessageState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SenderDashboardScreen(
    connectionUiState: ConnectionUiState,
    queuedMessages: List<MessageEntity>,
    identityFingerprint: String,
    trustedDevicesCount: Int,
    deviceName: String,
    lastSyncedTimestamp: Long,
    settingsManager: SettingsManager,
    onPairDeviceClick: () -> Unit,
    onRemoveTrustClick: () -> Unit,
    onEstablishConnectionClick: () -> Unit,
    onDisconnectClick: () -> Unit = {},
    onSendTestMessageClick: () -> Unit,
    onResetRoleClick: () -> Unit,
    onClearMessagesClick: () -> Unit,
    onSyncHistoryClick: (Long, Long) -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onRetryFailedClick: () -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                    label = { Text("Dashboard") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.List, contentDescription = "Queue") },
                    label = { Text("Queue") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Share, contentDescription = "Connection") },
                    label = { Text("Connection") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Lock, contentDescription = "Security") },
                    label = { Text("Security") },
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (selectedTab) {
                0 -> SenderMainTab(connectionUiState, onEstablishConnectionClick, onDisconnectClick, onSendTestMessageClick)
                1 -> SenderQueueTab(queuedMessages, onClearMessagesClick, onRetryFailedClick)
                2 -> ConnectionTab(connectionUiState, settingsManager)
                3 -> SecurityTab(identityFingerprint, trustedDevicesCount, deviceName, onPairDeviceClick, onRemoveTrustClick)
                4 -> SettingsTab(
                    settingsManager = settingsManager,
                    lastSyncedTimestamp = lastSyncedTimestamp,
                    onResetRoleClick = onResetRoleClick,
                    onSyncHistoryClick = onSyncHistoryClick,
                    onRequestBatteryOptimization = onRequestBatteryOptimization
                )
            }
        }
    }
}

@Composable
fun SenderMainTab(
    connectionUiState: ConnectionUiState,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onTest: () -> Unit
) {
    val connectionStatus = connectionUiState.status
    val isConnected = connectionStatus.startsWith("CONNECTED", ignoreCase = true)
    val isPeerOffline = connectionStatus.contains("Peer Offline", ignoreCase = true)
    val isConnecting = connectionStatus.equals("CONNECTING", ignoreCase = true) ||
        connectionStatus.equals("NEW", ignoreCase = true) ||
        connectionStatus.equals("CHECKING", ignoreCase = true)
    val isFailed = connectionStatus.equals("FAILED", ignoreCase = true) ||
        connectionStatus.equals("DISCONNECTED", ignoreCase = true) ||
        connectionStatus.equals("OFFLINE", ignoreCase = true)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        GlowingConnectionRing(isConnected = isConnected)
        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = connectionStatus.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            color = when {
                isConnected -> MaterialTheme.colorScheme.primary
                isPeerOffline -> MaterialTheme.colorScheme.secondary
                isFailed -> MaterialTheme.colorScheme.error
                isConnecting -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isConnecting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Connecting to paired device...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        } else if (isPeerOffline) {
            Text(
                "Relay is active. Paired device is offline or asleep; messages will be held securely in relay until it reconnects.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center
            )
        } else if (isFailed) {
            Text(
                "Disconnected. Paired device will auto-sync when online.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center
            )
        } else if (isConnected) {
            Text(
                "End-to-End Encrypted Relay • Peer Online",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        val lastError = connectionUiState.lastError
        if (!lastError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            ErrorDiagnosticBanner(
                errorMessage = lastError,
                modifier = Modifier.fillMaxWidth(0.9f)
            )
        }

        Spacer(modifier = Modifier.height(48.dp))

        Column(
            modifier = Modifier.fillMaxWidth(0.9f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isConnected || isConnecting || isPeerOffline) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect")
                    }
                    OutlinedButton(
                        onClick = onConnect,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reconnect")
                    }
                }
            } else {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Connect")
                }
            }

            Button(
                onClick = onTest,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Test SMS")
            }
        }
    }
}

@Composable
fun SenderQueueTab(
    queuedMessages: List<MessageEntity>,
    onWipeClick: () -> Unit,
    onRetryFailedClick: () -> Unit = {}
) {
    val failedCount = queuedMessages.count { it.state == MessageState.FAILED }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Message Queue", style = MaterialTheme.typography.headlineMedium)
            Row {
                if (failedCount > 0) {
                    OutlinedButton(
                        onClick = onRetryFailedClick,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry ($failedCount)")
                    }
                }
                IconButton(onClick = onWipeClick) {
                    Icon(Icons.Default.Delete, contentDescription = "Wipe", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        if (queuedMessages.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("All caught up!", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(queuedMessages, key = { it.messageId }) { msg ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(msg.senderDeviceId, style = MaterialTheme.typography.titleMedium)
                                StatusPill(
                                    status = msg.state.name,
                                    color = when (msg.state) {
                                        MessageState.DELIVERED -> Color(0xFF00E676)
                                        MessageState.FAILED -> Color(0xFFFF5252)
                                        else -> Color(0xFFFFB300)
                                    }
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Attempts: ${msg.retryCount} / 25", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionTab(connectionUiState: ConnectionUiState, settingsManager: SettingsManager) {
    val isPaused by settingsManager.connectionPausedFlow.collectAsState()
    val connectionStatus = connectionUiState.status
    val isPausedState = isPaused || connectionUiState.isPaused || connectionStatus.equals("PAUSED", ignoreCase = true)
    val isConnected = !isPausedState && (
        connectionStatus.startsWith("CONNECTED", ignoreCase = true) ||
            (connectionUiState.isRelayConnected && connectionUiState.isPeerOnline)
        )
    val isPeerOffline = !isPausedState && (
        connectionStatus.contains("Peer Offline", ignoreCase = true) ||
            (connectionUiState.isRelayConnected && !connectionUiState.isPeerOnline)
        )
    val isConnecting = !isPausedState && (
        connectionStatus.equals("CONNECTING", ignoreCase = true) ||
            connectionStatus.equals("NEW", ignoreCase = true) ||
            connectionStatus.equals("CHECKING", ignoreCase = true)
        )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text("Connection Metrics", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                val statusText = when {
                    isPausedState -> "Paused"
                    isConnected -> "Relay Active (Peer Online)"
                    isPeerOffline -> "Relay Standby (Peer Offline)"
                    isConnecting -> "Connecting..."
                    else -> "Disconnected"
                }
                val statusColor = when {
                    isPausedState -> MaterialTheme.colorScheme.secondary
                    isConnected -> MaterialTheme.colorScheme.primary
                    isPeerOffline || isConnecting -> MaterialTheme.colorScheme.secondary
                    else -> MaterialTheme.colorScheme.error
                }
                MetricRow("Status", statusText, statusColor)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                val transportLayer = when {
                    isPausedState -> "Paused"
                    isConnected || isPeerOffline -> "Encrypted Relay (HTTPS/WSS)"
                    isConnecting -> "Connecting..."
                    else -> "Disconnected"
                }
                MetricRow("Transport Layer", transportLayer, MaterialTheme.colorScheme.onSurface)
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                MetricRow("Encryption", "Tink HPKE + Room AEAD", MaterialTheme.colorScheme.onSurface)
            }
        }

        val lastError = connectionUiState.lastError
        if (!lastError.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            ErrorDiagnosticBanner(errorMessage = lastError)
        }

        Spacer(modifier = Modifier.height(24.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingToggle("Pause Connection (Save Battery)", isPaused) {
                    settingsManager.isConnectionPaused = it
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "When paused, the encrypted relay detaches to save battery. Any incoming SMS will remain encrypted in the local queue and sync automatically when you resume.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SecurityTab(identityFingerprint: String, trustedDevicesCount: Int, deviceName: String, onPairDeviceClick: () -> Unit, onRemoveTrustClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Security & Keys", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Identity Fingerprint", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Text(if (identityFingerprint.isNotEmpty()) identityFingerprint else "Loading...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
                
                Text("Trusted Devices", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                if (trustedDevicesCount > 0) {
                    Text(deviceName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    Text("1 Device Paired", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("No Devices Paired", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onPairDeviceClick, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Pair New Device (QR)")
        }
        
        if (trustedDevicesCount > 0) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onRemoveTrustClick, 
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text("Remove Paired Device")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTab(
    settingsManager: SettingsManager,
    lastSyncedTimestamp: Long = 0L,
    onResetRoleClick: () -> Unit = {}, 
    onSyncHistoryClick: (Long, Long) -> Unit = { _, _ -> },
    onRequestBatteryOptimization: () -> Unit = {},
    showSyncButton: Boolean = true
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val dateRangePickerState = rememberDateRangePickerState()

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    showDatePicker = false
                    val startDateMillis = dateRangePickerState.selectedStartDateMillis ?: (System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
                    val endDateMillis = dateRangePickerState.selectedEndDateMillis ?: System.currentTimeMillis()
                    onSyncHistoryClick(startDateMillis, endDateMillis)
                }) {
                    Text("Sync")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DateRangePicker(
                state = dateRangePickerState,
                modifier = Modifier.fillMaxWidth().height(400.dp)
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Preferences", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(24.dp))
        
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SettingToggle("Force Dark Mode", settingsManager.isDarkModeForced) { settingsManager.isDarkModeForced = it }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                SettingToggle("Auto-Start Service", settingsManager.isAutoStartEnabled) { settingsManager.isAutoStartEnabled = it }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                SettingToggle("Notifications", settingsManager.isNotificationsEnabled) { settingsManager.isNotificationsEnabled = it }
            }
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        Button(
            onClick = onRequestBatteryOptimization,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
            Text("Ignore Battery Optimization")
        }
        
        if (showSyncButton) {
            Spacer(modifier = Modifier.height(32.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Historical Sync", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val syncText = if (lastSyncedTimestamp > 0) {
                        val sdf = java.text.SimpleDateFormat("MMM dd, yyyy h:mm a", java.util.Locale.getDefault())
                        "Last Synced: ${sdf.format(java.util.Date(lastSyncedTimestamp))}"
                    } else {
                        "Never Synced"
                    }
                    Text(syncText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                        Text("Sync SMS History")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onResetRoleClick,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Reset Device Role")
        }
    }
}

@Composable
fun ErrorDiagnosticBanner(
    errorMessage: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Connection Error",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 2.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Connection Error",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                if (errorMessage.contains("Permission denied", ignoreCase = true)) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Check Firebase Realtime Database security rules.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

@Composable
fun MetricRow(label: String, value: String, valueColor: Color) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = valueColor)
    }
}

@Composable
fun SettingToggle(label: String, initialValue: Boolean, onCheckedChange: (Boolean) -> Unit) {
    var checked by remember(initialValue) { mutableStateOf(initialValue) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = {
            checked = it
            onCheckedChange(it)
        })
    }
}

package com.example.securesmsforwarder.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import com.example.securesmsforwarder.core.domain.SettingsManager

data class DisplayMessage(
    val id: String,
    val sender: String,
    val body: String,
    val time: String,
    val date: String,
    val timestamp: Long,
    val isRead: Boolean,
    val forwardingDevice: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerDashboardScreen(
    connectionStatus: String,
    messages: List<DisplayMessage>,
    identityFingerprint: String,
    trustedDevicesCount: Int,
    deviceName: String,
    settingsManager: SettingsManager,
    onPairDeviceClick: () -> Unit,
    onRemoveTrustClick: () -> Unit,
    onEstablishConnectionClick: () -> Unit,
    onClearMessagesClick: () -> Unit,
    onResetRoleClick: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onMessageClick: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val isConnected = connectionStatus.equals("CONNECTED", ignoreCase = true)

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
                    icon = { Icon(Icons.Default.MailOutline, contentDescription = "Inbox") },
                    label = { Text("Inbox") },
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
                0 -> ViewerMainTab(connectionStatus, onEstablishConnectionClick)
                1 -> ViewerInboxTab(messages, onClearMessagesClick, onMessageClick)
                2 -> ConnectionTab(connectionStatus, settingsManager) // Reused from SenderDashboardScreen.kt
                3 -> SecurityTab(identityFingerprint, trustedDevicesCount, deviceName, onPairDeviceClick, onRemoveTrustClick) // Reused
                4 -> SettingsTab(
                    settingsManager = settingsManager, 
                    onResetRoleClick = onResetRoleClick,
                    onRequestBatteryOptimization = onRequestBatteryOptimization,
                    showSyncButton = false
                ) // Reused
            }
        }
    }
}

@Composable
fun ViewerMainTab(connectionStatus: String, onConnect: () -> Unit) {
    val isConnected = connectionStatus.equals("CONNECTED", ignoreCase = true)
    val isConnecting = connectionStatus.equals("CONNECTING", ignoreCase = true) || connectionStatus.equals("NEW", ignoreCase = true) || connectionStatus.equals("CHECKING", ignoreCase = true)
    val isFailed = connectionStatus.equals("FAILED", ignoreCase = true)
    
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
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
                isFailed -> MaterialTheme.colorScheme.error
                isConnecting -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isConnecting) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.5f))
            Spacer(modifier = Modifier.height(8.dp))
            Text("Establishing P2P link...", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (isFailed) {
            Text("Connection dropped. The sender might be offline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } else if (isConnected) {
            Text("Viewer P2P • Strong Signal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        
        Spacer(modifier = Modifier.height(48.dp))
        
        Button(onClick = onConnect, modifier = Modifier.fillMaxWidth(0.5f)) {
            Text(if (isConnected || isConnecting) "Reconnect" else "Connect")
        }
    }
}

@Composable
fun ViewerInboxTab(messages: List<DisplayMessage>, onClear: () -> Unit, onMessageClick: (String) -> Unit) {
    var selectedSender by remember { mutableStateOf<String?>(null) }
    
    // Auto-detect unique forwarding devices
    val forwardingDevices = messages.map { it.forwardingDevice }.distinct().sorted()
    var selectedForwardingDevice by remember { mutableStateOf<String?>(forwardingDevices.firstOrNull()) }
    
    // Auto-select first if available and current selection is invalid
    if (selectedForwardingDevice !in forwardingDevices && forwardingDevices.isNotEmpty()) {
        selectedForwardingDevice = forwardingDevices.first()
    }

    if (selectedSender != null) {
        // Threaded View
        val threadMessages = messages.filter { it.sender == selectedSender && (it.forwardingDevice == selectedForwardingDevice || selectedForwardingDevice == null) }.sortedBy { it.timestamp }
        
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { selectedSender = null }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(selectedSender!!, style = MaterialTheme.typography.titleLarge)
            }
            
            val groupedByDate = threadMessages.groupBy { it.date }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                groupedByDate.forEach { (date, msgs) ->
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(
                                text = date,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                    .padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    
                    items(msgs) { msg ->
                        // Mark as read when viewed in thread
                        if (!msg.isRead) {
                            onMessageClick(msg.id)
                        }
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(0.85f).padding(vertical = 4.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(msg.body, style = MaterialTheme.typography.bodyLarge)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(msg.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    } else {
        // Grouped Inbox View
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("SMS Inbox", style = MaterialTheme.typography.headlineMedium)
                IconButton(onClick = onClear) {
                    Icon(Icons.Default.Delete, contentDescription = "Wipe", tint = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            // Device Filter UI
            if (forwardingDevices.size > 1) {
                androidx.compose.foundation.lazy.LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    items(forwardingDevices) { device ->
                        FilterChip(
                            selected = device == selectedForwardingDevice,
                            onClick = { selectedForwardingDevice = device },
                            label = { Text("Device: ${device.take(4)}") }
                        )
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            val filteredMessages = messages.filter { it.forwardingDevice == selectedForwardingDevice || selectedForwardingDevice == null }

            if (filteredMessages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Inbox is empty for this device", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val groupedMessages = filteredMessages.groupBy { it.sender }
                    .mapValues { entry -> entry.value.maxByOrNull { it.timestamp } }
                    .values.filterNotNull()
                    .sortedByDescending { it.timestamp }
                    
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(groupedMessages) { lastMsg ->
                        val unreadCount = filteredMessages.count { it.sender == lastMsg.sender && !it.isRead }
                        
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { selectedSender = lastMsg.sender }
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(
                                            text = lastMsg.sender,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = if (unreadCount > 0) androidx.compose.ui.text.font.FontWeight.Bold else androidx.compose.ui.text.font.FontWeight.Normal,
                                            color = if (unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(lastMsg.date, style = MaterialTheme.typography.labelSmall, color = if (unreadCount > 0) Color(0xFF25D366) else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = lastMsg.body,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (unreadCount > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        
                                        if (unreadCount > 0) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .background(Color(0xFF25D366), CircleShape),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = unreadCount.toString(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color.White,
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

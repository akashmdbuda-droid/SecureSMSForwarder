package com.example.securesmsforwarder.ui.changelog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

data class ReleaseNote(
    val version: String,
    val date: String,
    val changes: List<String>
)

val Changelog = listOf(
    ReleaseNote(
        version = "v1.0.22",
        date = "2026-09-05",
        changes = listOf(
            "Added a dedicated Disconnect button on the main dashboard for instant manual disconnect",
            "Added seamless one-tap Reconnect button when connected",
            "Improved connection controls: tapping Connect immediately unpauses and initiates connection sync"
        )
    ),
    ReleaseNote(
        version = "v1.0.21",
        date = "2026-09-04",
        changes = listOf(
            "Fail-Proof Always-Connected Architecture: paired devices now stay connected reliably across all Wi-Fi networks and mobile roaming",
            "Added End-to-End Encrypted (E2EE) Fallback Relay via standard HTTPS/WSS (port 443) so firewalls and strict NATs can never block message delivery",
            "Zero cloud storage: relay messages are encrypted with Google Tink HPKE on-device and wiped immediately upon delivery",
            "Automatic network recovery: seamlessly syncs pending messages when switching Wi-Fi networks or exiting flight mode",
            "Multi-port STUN and TCP TURN fallback (ports 80, 443, 3478) with continuous ICE gathering"
        )
    ),
    ReleaseNote(
        version = "v1.0.20",
        date = "2026-08-17",
        changes = listOf(
            "Reverted connection engine to v1.0.18 for proven connection stability"
        )
    ),
    ReleaseNote(
        version = "v1.0.19",
        date = "2026-08-16",
        changes = listOf(
            "Bulletproof reconnection after flight mode and network drops",
            "Automatic reconnect when internet is restored without manual button presses",
            "Added remote ICE candidate buffering to prevent lost TURN relay candidates",
            "Multi-port STUN and TCP TURN fallback for strict carrier CGNAT traversal (Hungary <-> India)",
            "Bidirectional connection wakeup handling and signaling freshness validation"
        )
    ),
    ReleaseNote(
        version = "v1.0.18",
        date = "2026-08-14",
        changes = listOf(
            "Added an in-app Changelog section to show what's new in each release"
        )
    ),
    ReleaseNote(
        version = "v1.0.17",
        date = "2026-08-14",
        changes = listOf(
            "Added Trickle ICE support for faster connection establishment",
            "Improved network change recovery (reconnects under 5 seconds)",
            "Instant UI feedback when clicking Connect"
        )
    ),
    ReleaseNote(
        version = "v1.0.16",
        date = "Previous",
        changes = listOf(
            "Various bug fixes and improvements"
        )
    )
)

@Composable
fun ChangelogDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's New") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(Changelog) { note ->
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = note.version,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = note.date,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        note.changes.forEach { change ->
                            Text(
                                text = "• $change",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
                            )
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

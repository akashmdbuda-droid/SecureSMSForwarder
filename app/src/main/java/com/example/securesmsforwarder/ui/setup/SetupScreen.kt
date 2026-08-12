package com.example.securesmsforwarder.ui.setup

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.securesmsforwarder.core.domain.DeviceRole

@Composable
fun SetupScreen(
    onRoleSelected: (DeviceRole) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Welcome to Secure SMS Forwarder",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        Text(
            text = "Select the role for this device:",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = { onRoleSelected(DeviceRole.SENDER) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "SMS Sender (Device A)", style = MaterialTheme.typography.titleMedium)
                Text(text = "This device receives the SMS and forwards them.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(
            onClick = { onRoleSelected(DeviceRole.VIEWER) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Secure Viewer (Device B)", style = MaterialTheme.typography.titleMedium)
                Text(text = "This device securely views the forwarded SMS.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

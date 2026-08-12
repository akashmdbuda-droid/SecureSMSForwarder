package com.example.securesmsforwarder.ui.signaling

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.example.securesmsforwarder.core.domain.DeviceRole
import com.example.securesmsforwarder.p2p.signaling.ManualSignalingProvider
import com.example.securesmsforwarder.p2p.signaling.SignalingState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignalingScreen(
    signalingProvider: ManualSignalingProvider,
    role: DeviceRole,
    trustedDeviceId: String, // from TrustStore
    onConnectionEstablished: () -> Unit,
    onBack: () -> Unit
) {
    val state by signalingProvider.signalingState.collectAsState()
    val localEnvelope by signalingProvider.localEnvelopeBase64.collectAsState()
    var pastedEnvelope by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val clipboardManager = LocalClipboardManager.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Secure P2P Connection") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("Role: ${role.name}", style = MaterialTheme.typography.labelMedium)
            Spacer(modifier = Modifier.height(16.dp))

            when (role) {
                DeviceRole.SENDER -> {
                    // SENDER WIZARD
                    when (state) {
                        SignalingState.OFFER_GENERATING -> {
                            Text("Preparing secure connection...", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Gathering connection info...")
                        }
                        SignalingState.OFFER_READY -> {
                            Text("Step 1 of 2", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Send connection request to the Viewer.", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                localEnvelope?.let {
                                    clipboardManager.setText(AnnotatedString(it))
                                }
                            }) {
                                Text("COPY REQUEST")
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("✓ Request ready", color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Next: paste the response from the Viewer.")
                            
                            Spacer(modifier = Modifier.height(32.dp))
                            HorizontalDivider()
                            Spacer(modifier = Modifier.height(32.dp))
                            
                            Text("Step 2 of 2", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = pastedEnvelope,
                                onValueChange = { pastedEnvelope = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Paste response here...") },
                                maxLines = 5,
                                isError = errorMessage != null
                            )
                            if (errorMessage != null) {
                                Text(
                                    text = errorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    try {
                                        signalingProvider.ingestRemoteEnvelope(pastedEnvelope.trim(), trustedDeviceId)
                                        errorMessage = null
                                        onConnectionEstablished()
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Invalid connection response"
                                    }
                                },
                                enabled = pastedEnvelope.isNotBlank()
                            ) {
                                Text("CONNECT")
                            }
                        }
                        else -> {
                            Text("Processing connection state...", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                            if (errorMessage != null) {
                                Spacer(modifier = Modifier.height(16.dp))
                                Text("Error: $errorMessage", color = MaterialTheme.colorScheme.error)
                                Button(onClick = { onBack() }) { Text("GO BACK") }
                            }
                        }
                    }
                }
                DeviceRole.VIEWER -> {
                    // VIEWER WIZARD
                    when (state) {
                        SignalingState.IDLE -> {
                            Text("Step 1 of 2", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Paste the connection request from the Sender.", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = pastedEnvelope,
                                onValueChange = { pastedEnvelope = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Paste request here...") },
                                maxLines = 5,
                                isError = errorMessage != null
                            )
                            if (errorMessage != null) {
                                Text(
                                    text = errorMessage!!,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = {
                                    try {
                                        signalingProvider.ingestRemoteEnvelope(pastedEnvelope.trim(), trustedDeviceId)
                                        errorMessage = null
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "Invalid connection request"
                                    }
                                },
                                enabled = pastedEnvelope.isNotBlank()
                            ) {
                                Text("PROCESS REQUEST")
                            }
                        }
                        SignalingState.ANSWER_GENERATING -> {
                            Text("Creating secure response...", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Gathering connection info...")
                        }
                        SignalingState.ANSWER_READY -> {
                            Text("Step 2 of 2", style = MaterialTheme.typography.titleSmall)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Send this response back to the Sender.", style = MaterialTheme.typography.bodyLarge)
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                localEnvelope?.let {
                                    clipboardManager.setText(AnnotatedString(it))
                                    onConnectionEstablished() // For viewer, copying the response is essentially the final UI step before connection is made by Sender.
                                }
                            }) {
                                Text("COPY RESPONSE")
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("✓ Response ready", color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Waiting for Sender...")
                        }
                        else -> {
                            Text("Processing connection state...", style = MaterialTheme.typography.titleMedium)
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                        }
                    }
                }
                DeviceRole.UNASSIGNED -> {
                    Text("Device role is unassigned. Please go back and select a role.")
                }
            }
        }
    }
}

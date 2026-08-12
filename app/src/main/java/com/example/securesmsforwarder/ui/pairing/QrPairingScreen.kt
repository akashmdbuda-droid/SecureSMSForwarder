package com.example.securesmsforwarder.ui.pairing

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun QrPairingScreen(
    serializedPublicKey: String,
    fingerprint: String,
    onQrScanned: (String) -> Unit
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val scannerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = com.journeyapps.barcodescanner.ScanContract(),
        onResult = { result ->
            if (result.contents != null) {
                onQrScanned(result.contents)
            }
        }
    )

    LaunchedEffect(serializedPublicKey) {
        qrBitmap = generateQrCode(serializedPublicKey)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Scan this QR on your other device",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        qrBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Pairing QR Code",
                modifier = Modifier.size(250.dp)
            )
        } ?: Box(modifier = Modifier.size(250.dp))

        Spacer(modifier = Modifier.height(32.dp))
        
        Text(
            text = "Fingerprint verification:",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = fingerprint,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(top = 8.dp)
        )
        
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        Button(
            onClick = {
                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(serializedPublicKey))
            },
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Copy Key (For Emulators)")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        var manualKey by remember { mutableStateOf("") }
        
        Text(
            text = "Or paste key manually (for emulators):",
            style = MaterialTheme.typography.bodyMedium
        )
        OutlinedTextField(
            value = manualKey,
            onValueChange = { manualKey = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Paste Peer Key") }
        )
        Button(
            onClick = {
                if (manualKey.isNotBlank()) {
                    onQrScanned(manualKey)
                }
            },
            enabled = manualKey.isNotBlank(),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Pair via Text")
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        val context = androidx.compose.ui.platform.LocalContext.current
        val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
            onResult = { isGranted ->
                if (isGranted) {
                    scannerLauncher.launch(com.journeyapps.barcodescanner.ScanOptions().apply {
                        setPrompt("Scan the QR code on the other device")
                        setBeepEnabled(false)
                        setCaptureActivity(CustomScannerActivity::class.java)
                    })
                } else {
                    android.widget.Toast.makeText(context, "Camera permission required to scan QR code", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        )

        androidx.compose.material3.Button(onClick = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                scannerLauncher.launch(com.journeyapps.barcodescanner.ScanOptions().apply {
                    setPrompt("Scan the QR code on the other device")
                    setBeepEnabled(false)
                    setCaptureActivity(CustomScannerActivity::class.java)
                })
            } else {
                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
            }
        }) {
            Text("Scan Other Device's QR")
        }
    }
}

suspend fun generateQrCode(text: String): Bitmap? = withContext(Dispatchers.IO) {
    try {
        val width = 500
        val height = 500
        val bitMatrix: BitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}

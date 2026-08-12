package com.example.securesmsforwarder.ui.pairing

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * A custom scanner activity to avoid common ZXing crashes.
 * It removes strict orientation locking and explicitly uses a safe AppCompat theme in the Manifest.
 */
class CustomScannerActivity : CaptureActivity()

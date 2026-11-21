package com.example.securesmsforwarder.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.securesmsforwarder.SecureSmsApplication
import com.example.securesmsforwarder.core.domain.DeviceRole

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val container = SecureSmsApplication.container(context)
            val roleManager = container.roleManager
            val settingsManager = container.settingsManager
            if (roleManager.currentRole != DeviceRole.UNASSIGNED && settingsManager.isAutoStartEnabled) {
                Log.d("BootReceiver", "Device booted. Starting ForwardingService.")
                val serviceIntent = Intent(context, ForwardingService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            } else {
                Log.d("BootReceiver", "Device booted but auto-start disabled or role UNASSIGNED. Skipping.")
            }
        }
    }
}

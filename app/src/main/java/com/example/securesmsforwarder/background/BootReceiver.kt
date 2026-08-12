package com.example.securesmsforwarder.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val roleManager = com.example.securesmsforwarder.core.domain.RoleManager(context)
            val settingsManager = com.example.securesmsforwarder.core.domain.SettingsManager(context)
            if (roleManager.currentRole != com.example.securesmsforwarder.core.domain.DeviceRole.UNASSIGNED && settingsManager.isAutoStartEnabled) {
                Log.d("BootReceiver", "Device booted. Starting ForwardingService.")
                val serviceIntent = Intent(context, ForwardingService::class.java)
                context.startForegroundService(serviceIntent)
            } else {
                Log.d("BootReceiver", "Device booted but auto-start disabled or role UNASSIGNED. Skipping.")
            }
        }
    }
}

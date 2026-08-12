package com.example.securesmsforwarder.core.domain

import android.content.Context
import android.content.SharedPreferences

enum class DeviceRole {
    UNASSIGNED,
    SENDER,
    VIEWER
}

class RoleManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("secure_sms_role", Context.MODE_PRIVATE)

    var currentRole: DeviceRole
        get() {
            val roleName = prefs.getString("device_role", DeviceRole.UNASSIGNED.name)
            return try {
                DeviceRole.valueOf(roleName!!)
            } catch (e: Exception) {
                DeviceRole.UNASSIGNED
            }
        }
        set(value) {
            prefs.edit().putString("device_role", value.name).apply()
        }
}

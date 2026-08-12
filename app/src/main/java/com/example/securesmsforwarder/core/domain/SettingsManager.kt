package com.example.securesmsforwarder.core.domain

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("secure_sms_settings", Context.MODE_PRIVATE)

    val forceDarkModeFlow = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean("dark_mode", true))
    val connectionPausedFlow = kotlinx.coroutines.flow.MutableStateFlow(prefs.getBoolean("connection_paused", false))

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
        when (key) {
            "dark_mode" -> forceDarkModeFlow.value = sharedPreferences.getBoolean(key, true)
            "connection_paused" -> connectionPausedFlow.value = sharedPreferences.getBoolean(key, false)
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    var isDarkModeForced: Boolean
        get() = prefs.getBoolean("dark_mode", true)
        set(value) {
            prefs.edit().putBoolean("dark_mode", value).apply()
        }

    var isAutoStartEnabled: Boolean
        get() = prefs.getBoolean("auto_start", true)
        set(value) = prefs.edit().putBoolean("auto_start", value).apply()

    var isNotificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications", false)
        set(value) = prefs.edit().putBoolean("notifications", value).apply()

    var isConnectionPaused: Boolean
        get() = prefs.getBoolean("connection_paused", false)
        set(value) {
            prefs.edit().putBoolean("connection_paused", value).apply()
        }
}

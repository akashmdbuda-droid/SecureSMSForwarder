package com.example.securesmsforwarder.pairing

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkJsonProtoKeysetFormat
import java.security.GeneralSecurityException

/**
 * Manages the public keys of trusted paired devices.
 */
class TrustStore(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "secure_sms_trust_store"
        private const val KEY_PEER_PUBLIC_KEY = "peer_public_keyset"
        private const val KEY_PEER_DEVICE_ID = "peer_device_id"
        private const val KEY_PEER_NAME = "peer_name"
        private const val KEY_PEER_LAST_SYNCED = "peer_last_synced"
    }

    /**
     * Saves the peer's public keyset and device ID after successful pairing.
     */
    fun addTrustedPeer(deviceId: String, serializedPublicKeyset: String, name: String = "Paired Device") {
        prefs.edit()
            .putString(KEY_PEER_DEVICE_ID, deviceId)
            .putString(KEY_PEER_PUBLIC_KEY, serializedPublicKeyset)
            .putString(KEY_PEER_NAME, name)
            .apply()
    }

    /**
     * Revokes the current trusted peer.
     */
    fun revokePeer() {
        prefs.edit().clear().apply()
    }

    /**
     * Checks if a specific device is trusted.
     */
    fun isDeviceTrusted(deviceId: String): Boolean {
        return prefs.getString(KEY_PEER_DEVICE_ID, null) == deviceId
    }

    /**
     * Returns the trusted peer's device ID.
     */
    fun getTrustedDeviceId(): String? {
        return prefs.getString(KEY_PEER_DEVICE_ID, null)
    }

    fun getTrustedDeviceName(): String {
        return prefs.getString(KEY_PEER_NAME, "Paired Device") ?: "Paired Device"
    }

    fun setTrustedDeviceName(name: String) {
        prefs.edit().putString(KEY_PEER_NAME, name).apply()
    }

    fun getLastSyncedTimestamp(): Long {
        return prefs.getLong(KEY_PEER_LAST_SYNCED, 0L)
    }

    fun setLastSyncedTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_PEER_LAST_SYNCED, timestamp).apply()
    }

    /**
     * Returns the trusted peer's public KeysetHandle, if one exists.
     */
    fun getTrustedPeerPublicKeyset(): KeysetHandle? {
        val serializedKey = prefs.getString(KEY_PEER_PUBLIC_KEY, null) ?: return null
        return try {
            TinkJsonProtoKeysetFormat.parseKeyset(serializedKey, com.google.crypto.tink.InsecureSecretKeyAccess.get())
        } catch (e: GeneralSecurityException) {
            null
        }
    }
    
    /**
     * Helper to serialize a public KeysetHandle for storing or sharing via QR.
     */
    fun serializePublicKeyset(keysetHandle: KeysetHandle): String {
        return TinkJsonProtoKeysetFormat.serializeKeyset(keysetHandle, com.google.crypto.tink.InsecureSecretKeyAccess.get())
    }
}

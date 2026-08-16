package com.example.securesmsforwarder.p2p.signaling

import android.util.Base64
import org.json.JSONObject
import org.webrtc.SessionDescription
import java.util.UUID

data class SignalingEnvelope(
    val protocol: String = "SecureSMS-SIGNAL",
    val version: Int = 1,
    val sessionId: String,
    val deviceId: String,
    val role: String,
    val sdp: String,
    val createdAt: Long,
    val expiresAt: Long
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("protocol", protocol)
        json.put("version", version)
        json.put("sessionId", sessionId)
        json.put("deviceId", deviceId)
        json.put("role", role)
        json.put("sdp", sdp)
        json.put("createdAt", createdAt)
        json.put("expiresAt", expiresAt)
        return json.toString()
    }

    fun toBase64String(): String {
        val jsonStr = toJson()
        val base64 = Base64.encodeToString(jsonStr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "SECURESMS1:$base64"
    }

    fun toSessionDescription(): SessionDescription {
        val type = if (role == "OFFER") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        return SessionDescription(type, sdp)
    }

    companion object {
        fun fromSessionDescription(
            sdp: SessionDescription,
            deviceId: String,
            sessionId: String = UUID.randomUUID().toString(),
            ttlMillis: Long = 60 * 1000L // 60 seconds validity for real-time signaling
        ): SignalingEnvelope {
            val role = if (sdp.type == SessionDescription.Type.OFFER) "OFFER" else "ANSWER"
            val now = System.currentTimeMillis()
            return SignalingEnvelope(
                sessionId = sessionId,
                deviceId = deviceId,
                role = role,
                sdp = sdp.description,
                createdAt = now,
                expiresAt = now + ttlMillis
            )
        }

        fun parseFromBase64String(base64Str: String): SignalingEnvelope {
            if (!base64Str.startsWith("SECURESMS1:")) {
                throw IllegalArgumentException("Invalid signaling envelope format")
            }
            val base64 = base64Str.removePrefix("SECURESMS1:")
            val jsonStr = String(Base64.decode(base64, Base64.NO_WRAP), Charsets.UTF_8)
            val json = JSONObject(jsonStr)

            if (json.getString("protocol") != "SecureSMS-SIGNAL") {
                throw IllegalArgumentException("Unsupported protocol")
            }
            if (json.getLong("expiresAt") < System.currentTimeMillis()) {
                throw IllegalStateException("Signaling envelope has expired")
            }

            return SignalingEnvelope(
                protocol = json.getString("protocol"),
                version = json.getInt("version"),
                sessionId = json.getString("sessionId"),
                deviceId = json.getString("deviceId"),
                role = json.getString("role"),
                sdp = json.getString("sdp"),
                createdAt = json.getLong("createdAt"),
                expiresAt = json.getLong("expiresAt")
            )
        }
    }
}

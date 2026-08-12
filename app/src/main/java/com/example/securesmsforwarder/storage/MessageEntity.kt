package com.example.securesmsforwarder.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MessageState {
    RECEIVED,
    QUEUED,
    ENCRYPTED,
    TRANSMITTING,
    PERSISTED_BY_B,
    ACKNOWLEDGED,
    DELIVERED,
    FAILED
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey
    val messageId: String,
    val sequenceNumber: Long,
    val timestamp: Long,
    val senderDeviceId: String, // Originating phone number
    val forwardingDevice: String = "", // Device fingerprint of the Sender app
    
    // Storing encrypted payload. The payload should not be plaintext.
    // If it is in RECEIVED state, it might be plaintext briefly, but should be encrypted quickly.
    val encryptedPayload: ByteArray?,
    
    val state: MessageState,
    val retryCount: Int = 0,
    val nextRetryTime: Long = 0L,
    val isRead: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MessageEntity

        if (messageId != other.messageId) return false
        if (sequenceNumber != other.sequenceNumber) return false
        if (timestamp != other.timestamp) return false
        if (senderDeviceId != other.senderDeviceId) return false
        if (forwardingDevice != other.forwardingDevice) return false
        if (encryptedPayload != null) {
            if (other.encryptedPayload == null) return false
            if (!encryptedPayload.contentEquals(other.encryptedPayload)) return false
        } else if (other.encryptedPayload != null) return false
        if (state != other.state) return false
        if (retryCount != other.retryCount) return false
        if (isRead != other.isRead) return false

        return true
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + senderDeviceId.hashCode()
        result = 31 * result + forwardingDevice.hashCode()
        result = 31 * result + (encryptedPayload?.contentHashCode() ?: 0)
        result = 31 * result + state.hashCode()
        result = 31 * result + retryCount
        result = 31 * result + nextRetryTime.hashCode()
        result = 31 * result + isRead.hashCode()
        return result
    }
}

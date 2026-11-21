package com.example.securesmsforwarder.core.domain

import com.example.securesmsforwarder.crypto.storage.StorageCipher
import com.example.securesmsforwarder.sms.ParsedSms
import com.example.securesmsforwarder.storage.MessageDao
import com.example.securesmsforwarder.storage.MessageEntity
import com.example.securesmsforwarder.storage.MessageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

class MessageQueueManager(
    private val messageDao: MessageDao,
    private val storageCipher: StorageCipher
) {
    // In memory sequence number generator for V1, 
    // Ideally this should be persisted to survive reboots to guarantee strictly monotonic increments.
    private var currentSequenceNumber: Long = System.currentTimeMillis()

    suspend fun enqueueMessage(parsedSms: ParsedSms) {
        val messageId = parsedSms.messageId ?: UUID.randomUUID().toString()
        val seqNum = synchronized(this) { currentSequenceNumber++ }

        val recent = messageDao.getRecentMessagesFromSender(
            parsedSms.sender,
            parsedSms.timestamp - 30_000L,
            parsedSms.timestamp + 30_000L
        )
        if (recent.any { runCatching { storageCipher.decrypt(it.encryptedPayload) }.getOrNull() == parsedSms.body }) {
            return // Duplicate message (e.g. from both Receiver and Observer)
        }

        // Persist immediately as QUEUED with at-rest AEAD encryption for crash-safe delivery.
        val initialEntity = MessageEntity(
            messageId = messageId,
            sequenceNumber = seqNum,
            timestamp = parsedSms.timestamp,
            senderDeviceId = parsedSms.sender, // Sender is the originating phone number
            encryptedPayload = storageCipher.encrypt(parsedSms.body),
            state = MessageState.QUEUED
        )

        messageDao.insertMessage(initialEntity)
    }

    suspend fun enqueueTestMessage() {
        val messageId = UUID.randomUUID().toString()
        val seqNum = synchronized(this) { currentSequenceNumber++ }

        val initialEntity = MessageEntity(
            messageId = messageId,
            sequenceNumber = seqNum,
            timestamp = System.currentTimeMillis(),
            senderDeviceId = "555-TEST",
            encryptedPayload = storageCipher.encrypt("Hello from the Sender App UI!"),
            state = MessageState.QUEUED
        )
        messageDao.insertMessage(initialEntity)
    }

    fun getPendingMessagesFlow(): Flow<List<MessageEntity>> {
        return messageDao.getPendingMessages()
    }

    fun getSenderQueueMessagesFlow(): Flow<List<MessageEntity>> {
        return messageDao.getSenderQueueMessages()
    }

    fun decryptBody(payload: ByteArray?): String {
        return storageCipher.decrypt(payload)
    }

    suspend fun markTransmitting(messageId: String) {
        val entity = messageDao.getMessageById(messageId)
        if (entity != null) {
            // Capped exponential backoff: 2s, 4s, 8s, 16s, 32s, max 60s
            val backoffMs = minOf(60_000L, (Math.pow(2.0, minOf(entity.retryCount, 5).toDouble()) * 2000L).toLong())
            val nextRetry = System.currentTimeMillis() + backoffMs
            messageDao.updateMessage(entity.copy(state = MessageState.TRANSMITTING, retryCount = entity.retryCount + 1, nextRetryTime = nextRetry))
        }
    }

    suspend fun checkRetries() {
        val now = System.currentTimeMillis()
        val messages = messageDao.getPendingMessages().first()
        messages.filter { it.state == MessageState.TRANSMITTING && it.nextRetryTime < now }
            .forEach { msg ->
                if (msg.retryCount > 25) {
                    messageDao.updateMessageState(msg.messageId, MessageState.FAILED)
                } else {
                    messageDao.updateMessageState(msg.messageId, MessageState.QUEUED)
                }
            }
    }

    suspend fun requeueFailedMessages(): Int {
        return messageDao.requeueFailedMessages()
    }

    fun getFailedMessagesCountFlow(): Flow<Int> {
        return messageDao.getFailedMessagesCount()
    }

    suspend fun markFailed(messageId: String) {
        messageDao.updateMessageState(messageId, MessageState.FAILED)
    }

    suspend fun markAcknowledged(messageId: String) {
        messageDao.updateMessageState(messageId, MessageState.ACKNOWLEDGED)
        messageDao.updateMessageState(messageId, MessageState.DELIVERED)
    }

    suspend fun insertViewerMessage(msgId: String, body: String, sender: String, forwarder: String, timestamp: Long): Boolean {
        val entity = MessageEntity(
            messageId = msgId,
            sequenceNumber = System.currentTimeMillis(),
            timestamp = timestamp,
            senderDeviceId = sender,
            forwardingDevice = forwarder,
            encryptedPayload = storageCipher.encrypt(body),
            state = MessageState.DELIVERED,
            retryCount = 0,
            nextRetryTime = 0L,
            isRead = false
        )
        val rowId = messageDao.insertMessage(entity)
        return rowId != -1L
    }

    suspend fun deleteAll() {
        messageDao.deleteAll()
    }
}

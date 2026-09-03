package com.example.securesmsforwarder.core.domain

import com.example.securesmsforwarder.sms.ParsedSms
import com.example.securesmsforwarder.storage.MessageDao
import com.example.securesmsforwarder.storage.MessageEntity
import com.example.securesmsforwarder.storage.MessageState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import java.util.UUID

class MessageQueueManager(
    private val messageDao: MessageDao
) {
    // In memory sequence number generator for V1, 
    // Ideally this should be persisted to survive reboots to guarantee strictly monotonic increments.
    private var currentSequenceNumber: Long = System.currentTimeMillis()

    suspend fun enqueueMessage(parsedSms: ParsedSms) {
        val messageId = parsedSms.messageId ?: UUID.randomUUID().toString()
        val seqNum = synchronized(this) { currentSequenceNumber++ }
        
        val bodyBytes = parsedSms.body.toByteArray(Charsets.UTF_8)
        val recent = messageDao.getRecentMessagesFromSender(parsedSms.sender, parsedSms.timestamp - 30000, parsedSms.timestamp + 30000)
        if (recent.any { it.encryptedPayload.contentEquals(bodyBytes) }) {
            return // Duplicate message (e.g. from both Receiver and Observer)
        }
        
        // 1. Initial creation (RECEIVED -> QUEUED locally)
        // In Phase 1 we are saving the raw body as bytes. In Phase 2, this will be
        // passed to the crypto layer BEFORE persisting, or persisted as QUEUED, then encrypted, then ENCRYPTED.
        // For reliability (crash-safe delivery), we persist immediately as QUEUED.
        
        val initialEntity = MessageEntity(
            messageId = messageId,
            sequenceNumber = seqNum,
            timestamp = parsedSms.timestamp,
            senderDeviceId = parsedSms.sender, // Sender is the originating phone number
            encryptedPayload = parsedSms.body.toByteArray(Charsets.UTF_8), // TODO: Phase 2 Encrypt this
            state = MessageState.QUEUED
        )
        
        messageDao.insertMessage(initialEntity)
        
        // 2. Trigger encryption (Phase 2)
        // encryptMessage(messageId)
        
        // 3. Trigger transport (Phase 3/4)
        // triggerTransport(messageId)
    }

    suspend fun enqueueTestMessage() {
        val messageId = UUID.randomUUID().toString()
        val seqNum = synchronized(this) { currentSequenceNumber++ }
        
        val initialEntity = MessageEntity(
            messageId = messageId,
            sequenceNumber = seqNum,
            timestamp = System.currentTimeMillis(),
            senderDeviceId = "555-TEST",
            encryptedPayload = "Hello from the Sender App UI!".toByteArray(Charsets.UTF_8),
            state = MessageState.QUEUED
        )
        messageDao.insertMessage(initialEntity)
    }

    fun getPendingMessagesFlow(): kotlinx.coroutines.flow.Flow<List<MessageEntity>> {
        return messageDao.getPendingMessages()
    }

    suspend fun markTransmitting(messageId: String) {
        val entity = messageDao.getMessageById(messageId)
        if (entity != null) {
            val nextRetry = System.currentTimeMillis() + (Math.pow(2.0, entity.retryCount.toDouble()) * 2000L).toLong()
            messageDao.updateMessage(entity.copy(state = MessageState.TRANSMITTING, retryCount = entity.retryCount + 1, nextRetryTime = nextRetry))
        }
    }

    suspend fun checkRetries() {
        val now = System.currentTimeMillis()
        val messages = messageDao.getPendingMessages().first()
        messages.filter { it.state == MessageState.TRANSMITTING && it.nextRetryTime < now }
            .forEach { msg ->
                if (msg.retryCount > 5) {
                    messageDao.updateMessageState(msg.messageId, MessageState.FAILED)
                } else {
                    messageDao.updateMessageState(msg.messageId, MessageState.QUEUED)
                }
            }
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
            encryptedPayload = body.toByteArray(Charsets.UTF_8),
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



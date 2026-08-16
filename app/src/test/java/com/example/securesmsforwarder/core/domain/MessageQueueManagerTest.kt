package com.example.securesmsforwarder.core.domain

import com.example.securesmsforwarder.sms.ParsedSms
import com.example.securesmsforwarder.storage.MessageDao
import com.example.securesmsforwarder.storage.MessageEntity
import com.example.securesmsforwarder.storage.MessageState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MessageQueueManagerTest {

    class FakeMessageDao : MessageDao {
        val messages = mutableMapOf<String, MessageEntity>()

        override suspend fun insertMessage(message: MessageEntity): Long {
            messages[message.messageId] = message
            return 1L
        }

        override suspend fun updateMessage(message: MessageEntity) {
            messages[message.messageId] = message
        }

        override suspend fun getMessageById(id: String): MessageEntity? {
            return messages[id]
        }

        override fun getAllMessages(): Flow<List<MessageEntity>> {
            return flowOf(messages.values.toList())
        }

        override suspend fun getUnreadMessages(): List<MessageEntity> {
            return messages.values.filter { !it.isRead }
        }

        override fun getPendingMessages(): Flow<List<MessageEntity>> {
            return flowOf(messages.values.filter { it.state != MessageState.DELIVERED })
        }

        override suspend fun updateMessageState(id: String, newState: MessageState) {
            val msg = messages[id]
            if (msg != null) {
                messages[id] = msg.copy(state = newState)
            }
        }

        override suspend fun markAsRead(id: String) {
            val msg = messages[id]
            if (msg != null) {
                messages[id] = msg.copy(isRead = true)
            }
        }

        override suspend fun deleteMessage(id: String) {
            messages.remove(id)
        }

        override suspend fun deleteAll() {
            messages.clear()
        }

        override suspend fun getRecentMessagesFromSender(sender: String, minTime: Long, maxTime: Long): List<MessageEntity> {
            return messages.values.filter { it.senderDeviceId == sender && it.timestamp in minTime..maxTime }
        }
    }

    @Test
    fun testEnqueueMessage_savesAsQueued() = runBlocking {
        val fakeDao = FakeMessageDao()
        val queueManager = MessageQueueManager(fakeDao)

        val sms = ParsedSms(
            sender = "+1234567890",
            timestamp = 1620000000L,
            body = "Your OTP is 123456",
            subscriptionId = 1
        )

        queueManager.enqueueMessage(sms)

        assertEquals(1, fakeDao.messages.size)
        
        val savedEntity = fakeDao.messages.values.first()
        
        assertEquals(MessageState.QUEUED, savedEntity.state)
        assertEquals("+1234567890", savedEntity.senderDeviceId)
        assertEquals(1620000000L, savedEntity.timestamp)
        assertNotNull(savedEntity.encryptedPayload)
        
        val decodedPayload = String(savedEntity.encryptedPayload!!, Charsets.UTF_8)
        assertEquals("Your OTP is 123456", decodedPayload)
    }
}

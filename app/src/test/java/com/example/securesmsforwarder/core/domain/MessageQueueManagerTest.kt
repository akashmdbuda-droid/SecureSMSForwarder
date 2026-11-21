package com.example.securesmsforwarder.core.domain

import com.example.securesmsforwarder.crypto.storage.AeadStorageCipher
import com.example.securesmsforwarder.sms.ParsedSms
import com.example.securesmsforwarder.storage.MessageDao
import com.example.securesmsforwarder.storage.MessageEntity
import com.example.securesmsforwarder.storage.MessageState
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test

class MessageQueueManagerTest {

    private lateinit var fakeDao: FakeMessageDao
    private lateinit var storageCipher: AeadStorageCipher
    private lateinit var queueManager: MessageQueueManager

    companion object {
        @JvmStatic
        @BeforeClass
        fun registerTink() {
            AeadConfig.register()
        }
    }

    @Before
    fun setUp() {
        fakeDao = FakeMessageDao()
        val aead = KeysetHandle
            .generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(Aead::class.java)
        storageCipher = AeadStorageCipher(aead)
        queueManager = MessageQueueManager(fakeDao, storageCipher)
    }

    class FakeMessageDao : MessageDao {
        val messages = mutableMapOf<String, MessageEntity>()

        private val pendingStates = setOf(
            MessageState.QUEUED,
            MessageState.ENCRYPTED,
            MessageState.TRANSMITTING,
            MessageState.PERSISTED_BY_B
        )

        private val senderQueueStates = setOf(
            MessageState.QUEUED,
            MessageState.ENCRYPTED,
            MessageState.TRANSMITTING,
            MessageState.PERSISTED_BY_B,
            MessageState.FAILED
        )

        override suspend fun insertMessage(message: MessageEntity): Long {
            if (messages.containsKey(message.messageId)) {
                return -1L
            }
            messages[message.messageId] = message
            return messages.size.toLong()
        }

        override suspend fun updateMessage(message: MessageEntity) {
            messages[message.messageId] = message
        }

        override suspend fun getMessageById(id: String): MessageEntity? {
            return messages[id]
        }

        override fun getAllMessages(): Flow<List<MessageEntity>> {
            return flowOf(messages.values.sortedByDescending { it.timestamp })
        }

        override suspend fun getUnreadMessages(): List<MessageEntity> {
            return messages.values.filter { !it.isRead }.sortedByDescending { it.timestamp }
        }

        override fun getPendingMessages(): Flow<List<MessageEntity>> {
            return flowOf(
                messages.values
                    .filter { it.state in pendingStates }
                    .sortedBy { it.sequenceNumber }
            )
        }

        override fun getSenderQueueMessages(): Flow<List<MessageEntity>> {
            return flowOf(
                messages.values
                    .filter { it.state in senderQueueStates }
                    .sortedBy { it.sequenceNumber }
            )
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

        override suspend fun getRecentMessagesFromSender(
            sender: String,
            minTime: Long,
            maxTime: Long
        ): List<MessageEntity> {
            return messages.values.filter {
                it.senderDeviceId == sender && it.timestamp in minTime..maxTime
            }
        }

        override suspend fun requeueFailedMessages(): Int {
            val failed = messages.values.filter { it.state == MessageState.FAILED }
            failed.forEach {
                messages[it.messageId] = it.copy(
                    state = MessageState.QUEUED,
                    retryCount = 0,
                    nextRetryTime = 0L
                )
            }
            return failed.size
        }

        override fun getFailedMessagesCount(): Flow<Int> {
            return flowOf(messages.values.count { it.state == MessageState.FAILED })
        }
    }

    @Test
    fun testEnqueueMessage_storesAeadEncryptedPayloadAndDecryptsCleanly() = runBlocking {
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

        val payload = savedEntity.encryptedPayload
        assertNotNull(payload)
        assertTrue(
            "Stored payload must start with AEADv1: header",
            payload!!.size > AeadStorageCipher.HEADER_PREFIX.size &&
                payload.copyOfRange(0, AeadStorageCipher.HEADER_PREFIX.size)
                    .contentEquals(AeadStorageCipher.HEADER_PREFIX)
        )
        assertFalse(
            "Stored payload must not be raw UTF-8 plaintext",
            payload.contentEquals("Your OTP is 123456".toByteArray(Charsets.UTF_8))
        )
        assertEquals("Your OTP is 123456", queueManager.decryptBody(payload))
    }

    @Test
    fun testEnqueueMessage_deduplicatesWithinWindowDespiteRandomizedNonces() = runBlocking {
        val firstSms = ParsedSms(
            sender = "+1234567890",
            timestamp = 1620000000L,
            body = "Duplicate OTP 654321",
            subscriptionId = 1,
            messageId = "msg-1"
        )
        val duplicateWithinWindow = ParsedSms(
            sender = "+1234567890",
            timestamp = 1620000000L + 15_000L,
            body = "Duplicate OTP 654321",
            subscriptionId = 1,
            messageId = "msg-2"
        )
        val outsideWindow = ParsedSms(
            sender = "+1234567890",
            timestamp = 1620000000L + 45_000L,
            body = "Duplicate OTP 654321",
            subscriptionId = 1,
            messageId = "msg-3"
        )

        queueManager.enqueueMessage(firstSms)
        queueManager.enqueueMessage(duplicateWithinWindow)
        assertEquals(1, fakeDao.messages.size)

        queueManager.enqueueMessage(outsideWindow)
        assertEquals(2, fakeDao.messages.size)
    }

    @Test
    fun testEnqueueTestMessage_storesEncryptedBody() = runBlocking {
        queueManager.enqueueTestMessage()

        assertEquals(1, fakeDao.messages.size)
        val saved = fakeDao.messages.values.first()
        val payload = saved.encryptedPayload
        assertNotNull(payload)
        assertTrue(
            payload!!.copyOfRange(0, AeadStorageCipher.HEADER_PREFIX.size)
                .contentEquals(AeadStorageCipher.HEADER_PREFIX)
        )
        assertEquals("Hello from the Sender App UI!", queueManager.decryptBody(payload))
    }

    @Test
    fun testInsertViewerMessage_encryptsBodyAndIgnoresDuplicateMessageId() = runBlocking {
        val isNew = queueManager.insertViewerMessage(
            msgId = "msg-123",
            body = "Test viewer body",
            sender = "+9876543210",
            forwarder = "DEVICE_A",
            timestamp = 1650000000L
        )
        val isDuplicate = queueManager.insertViewerMessage(
            msgId = "msg-123",
            body = "Test viewer body",
            sender = "+9876543210",
            forwarder = "DEVICE_A",
            timestamp = 1650000000L
        )

        assertTrue(isNew)
        assertFalse(isDuplicate)
        assertEquals(1, fakeDao.messages.size)

        val saved = fakeDao.messages["msg-123"]
        assertNotNull(saved)
        assertEquals(MessageState.DELIVERED, saved!!.state)
        assertNotNull(saved.encryptedPayload)
        assertFalse(saved.encryptedPayload!!.contentEquals("Test viewer body".toByteArray(Charsets.UTF_8)))
        assertEquals("Test viewer body", queueManager.decryptBody(saved.encryptedPayload))
    }

    @Test
    fun testSenderQueueFlowIncludesFailedWhilePendingExcludesFailed() = runBlocking {
        fakeDao.messages["msg-queued"] = MessageEntity(
            messageId = "msg-queued",
            sequenceNumber = 1L,
            timestamp = 1000L,
            senderDeviceId = "+111",
            encryptedPayload = storageCipher.encrypt("queued"),
            state = MessageState.QUEUED
        )
        fakeDao.messages["msg-failed"] = MessageEntity(
            messageId = "msg-failed",
            sequenceNumber = 2L,
            timestamp = 2000L,
            senderDeviceId = "+222",
            encryptedPayload = storageCipher.encrypt("failed"),
            state = MessageState.FAILED,
            retryCount = 26
        )
        fakeDao.messages["msg-delivered"] = MessageEntity(
            messageId = "msg-delivered",
            sequenceNumber = 3L,
            timestamp = 3000L,
            senderDeviceId = "+333",
            encryptedPayload = storageCipher.encrypt("delivered"),
            state = MessageState.DELIVERED
        )

        val pending = queueManager.getPendingMessagesFlow().first()
        val senderQueue = queueManager.getSenderQueueMessagesFlow().first()

        assertEquals(listOf("msg-queued"), pending.map { it.messageId })
        assertEquals(listOf("msg-queued", "msg-failed"), senderQueue.map { it.messageId })
    }

    @Test
    fun testCheckRetries_transitionsExceededToFailedAndRequeueResets() = runBlocking {
        val expiredRetryTime = System.currentTimeMillis() - 5_000L
        fakeDao.messages["msg-exhausted"] = MessageEntity(
            messageId = "msg-exhausted",
            sequenceNumber = 1L,
            timestamp = 1000L,
            senderDeviceId = "123",
            encryptedPayload = storageCipher.encrypt("exhausted"),
            state = MessageState.TRANSMITTING,
            retryCount = 26,
            nextRetryTime = expiredRetryTime
        )
        fakeDao.messages["msg-retryable"] = MessageEntity(
            messageId = "msg-retryable",
            sequenceNumber = 2L,
            timestamp = 1001L,
            senderDeviceId = "456",
            encryptedPayload = storageCipher.encrypt("retryable"),
            state = MessageState.TRANSMITTING,
            retryCount = 4,
            nextRetryTime = expiredRetryTime
        )

        queueManager.checkRetries()

        assertEquals(MessageState.FAILED, fakeDao.messages["msg-exhausted"]?.state)
        assertEquals(MessageState.QUEUED, fakeDao.messages["msg-retryable"]?.state)

        val requeuedCount = queueManager.requeueFailedMessages()
        assertEquals(1, requeuedCount)
        assertEquals(MessageState.QUEUED, fakeDao.messages["msg-exhausted"]?.state)
        assertEquals(0, fakeDao.messages["msg-exhausted"]?.retryCount)
        assertEquals(0L, fakeDao.messages["msg-exhausted"]?.nextRetryTime)
    }

    @Test
    fun testBackwardCompatibility_deduplicatesAndReadsLegacyPlaintextRow() = runBlocking {
        val legacyBody = "Legacy pre-v1.1.0 plaintext OTP 777888"
        fakeDao.messages["legacy-msg"] = MessageEntity(
            messageId = "legacy-msg",
            sequenceNumber = 1L,
            timestamp = 1700000000L,
            senderDeviceId = "+15550001111",
            encryptedPayload = legacyBody.toByteArray(Charsets.UTF_8),
            state = MessageState.QUEUED
        )

        assertEquals(legacyBody, queueManager.decryptBody(fakeDao.messages["legacy-msg"]?.encryptedPayload))

        queueManager.enqueueMessage(
            ParsedSms(
                sender = "+15550001111",
                timestamp = 1700000000L + 10_000L,
                body = legacyBody,
                subscriptionId = 1,
                messageId = "new-duplicate"
            )
        )

        assertEquals(1, fakeDao.messages.size)
        assertNotNull(fakeDao.messages["legacy-msg"])
    }
}

package com.example.securesmsforwarder.p2p

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import com.example.securesmsforwarder.core.domain.MessageQueueManager
import com.example.securesmsforwarder.crypto.encryption.MessageEncryption
import com.example.securesmsforwarder.crypto.storage.StorageCipher
import com.example.securesmsforwarder.p2p.relay.RelayTransport
import com.example.securesmsforwarder.pairing.TrustStore
import com.example.securesmsforwarder.sms.ParsedSms
import com.example.securesmsforwarder.storage.MessageDao
import com.example.securesmsforwarder.storage.MessageEntity
import com.example.securesmsforwarder.storage.MessageState
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HybridConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TransportManagerTest {

    companion object {
        private val testPrefixCounter = AtomicInteger(0)

        @JvmStatic
        @BeforeClass
        fun registerTink() {
            HybridConfig.register()
            AeadConfig.register()
        }
    }

    private val managersToClose = mutableListOf<TransportManager>()

    @After
    fun tearDown() {
        managersToClose.forEach { it.close() }
        managersToClose.clear()
    }

    private class PrefixedPrefsContext(
        base: Context,
        private val prefix: String
    ) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            return super.getSharedPreferences("${prefix}_$name", mode)
        }
    }

    private class FakeStorageCipher : StorageCipher {
        companion object {
            const val PREFIX = "FAKE_ENC:"
        }

        override fun encrypt(plaintext: String): ByteArray {
            return (PREFIX + plaintext).toByteArray(Charsets.UTF_8)
        }

        override fun decrypt(payload: ByteArray?): String {
            if (payload == null || payload.isEmpty()) return ""
            val raw = String(payload, Charsets.UTF_8)
            return if (raw.startsWith(PREFIX)) raw.removePrefix(PREFIX) else raw
        }
    }

    private class FakeMessageDao : MessageDao {
        val messages = ConcurrentHashMap<String, MessageEntity>()

        override suspend fun insertMessage(message: MessageEntity): Long {
            val existing = messages.putIfAbsent(message.messageId, message)
            return if (existing == null) 1L else -1L
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
            val pendingStates = setOf(
                MessageState.QUEUED,
                MessageState.ENCRYPTED,
                MessageState.TRANSMITTING,
                MessageState.PERSISTED_BY_B
            )
            return flowOf(
                messages.values
                    .filter { it.state in pendingStates }
                    .sortedBy { it.sequenceNumber }
            )
        }

        override fun getSenderQueueMessages(): Flow<List<MessageEntity>> {
            val queueStates = setOf(
                MessageState.QUEUED,
                MessageState.ENCRYPTED,
                MessageState.TRANSMITTING,
                MessageState.PERSISTED_BY_B,
                MessageState.FAILED
            )
            return flowOf(
                messages.values
                    .filter { it.state in queueStates }
                    .sortedBy { it.sequenceNumber }
            )
        }

        override suspend fun updateMessageState(id: String, newState: MessageState) {
            messages.computeIfPresent(id) { _, msg -> msg.copy(state = newState) }
        }

        override suspend fun markAsRead(id: String) {
            messages.computeIfPresent(id) { _, msg -> msg.copy(isRead = true) }
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
            var count = 0
            messages.values.filter { it.state == MessageState.FAILED }.forEach { failed ->
                messages[failed.messageId] = failed.copy(
                    state = MessageState.QUEUED,
                    retryCount = 0,
                    nextRetryTime = 0L
                )
                count++
            }
            return count
        }

        override fun getFailedMessagesCount(): Flow<Int> {
            return flowOf(messages.values.count { it.state == MessageState.FAILED })
        }
    }

    private class FakeRelayTransport(
        initialRelayAvailable: Boolean = false,
        initialPeerOnline: Boolean = false,
        private val autoConnectOnGoOnline: Boolean = true
    ) : RelayTransport {

        @Volatile
        private var relayAvailable: Boolean = initialRelayAvailable

        @Volatile
        private var peerOnline: Boolean = initialPeerOnline

        @Volatile
        private var peerLastSeen: Long = 0L

        @Volatile
        private var lastError: String? = null

        @Volatile
        var sendDelayMs: Long = 0L

        @Volatile
        var peerTransport: FakeRelayTransport? = null

        @Volatile
        var isClosed: Boolean = false

        val goOnlineCount = AtomicInteger(0)
        val goOfflineCount = AtomicInteger(0)
        val wakeupCount = AtomicInteger(0)
        val closeCount = AtomicInteger(0)

        val sentMessages = CopyOnWriteArrayList<Pair<String, String>>()
        val preservedInbox = ConcurrentHashMap<String, String>()
        val consumedIds = CopyOnWriteArrayList<String>()
        val processedResults = ConcurrentHashMap<String, Boolean>()

        private var encryptedListener: ((String, String, (Boolean) -> Unit) -> Unit)? = null
        private var wakeupListener: (() -> Unit)? = null
        private var relayAvailabilityListener: ((Boolean) -> Unit)? = null
        private var peerPresenceListener: ((Boolean, Long) -> Unit)? = null
        private var errorListener: ((String?) -> Unit)? = null

        override fun sendEncryptedRelayMessage(messageId: String, ciphertextBase64: String): Boolean {
            if (isClosed) return false
            if (sendDelayMs > 0L) {
                Thread.sleep(sendDelayMs)
            }
            sentMessages.add(messageId to ciphertextBase64)
            peerTransport?.deliverIncoming(messageId, ciphertextBase64)
            return true
        }

        override fun setEncryptedRelayMessageListener(
            listener: (messageId: String, ciphertextBase64: String, onProcessed: (Boolean) -> Unit) -> Unit
        ) {
            encryptedListener = listener
        }

        override fun requestWakeup() {
            if (isClosed) return
            wakeupCount.incrementAndGet()
            peerTransport?.triggerRemoteWakeup()
        }

        override fun setWakeupListener(listener: () -> Unit) {
            wakeupListener = listener
        }

        override fun isRelayAvailable(): Boolean = relayAvailable

        override fun setRelayAvailabilityListener(listener: (Boolean) -> Unit) {
            relayAvailabilityListener = listener
            listener.invoke(relayAvailable)
        }

        override fun isPeerOnline(): Boolean = peerOnline

        override fun getPeerLastSeen(): Long = peerLastSeen

        override fun setPeerPresenceListener(listener: (isOnline: Boolean, lastSeen: Long) -> Unit) {
            peerPresenceListener = listener
            listener.invoke(peerOnline, peerLastSeen)
        }

        override fun setErrorListener(listener: (String?) -> Unit) {
            errorListener = listener
            lastError?.let { listener.invoke(it) }
        }

        override fun goOnline() {
            if (isClosed) return
            goOnlineCount.incrementAndGet()
            if (autoConnectOnGoOnline) {
                relayAvailable = true
                relayAvailabilityListener?.invoke(true)
            }
        }

        override fun goOffline() {
            if (isClosed) return
            goOfflineCount.incrementAndGet()
            relayAvailable = false
            peerOnline = false
            relayAvailabilityListener?.invoke(false)
            peerPresenceListener?.invoke(false, peerLastSeen)
        }

        override fun close() {
            isClosed = true
            closeCount.incrementAndGet()
        }

        fun setRelayAvailable(available: Boolean) {
            relayAvailable = available
            relayAvailabilityListener?.invoke(available)
        }

        fun setPeerPresence(online: Boolean, lastSeen: Long = System.currentTimeMillis()) {
            peerOnline = online
            peerLastSeen = lastSeen
            peerPresenceListener?.invoke(online, lastSeen)
        }

        fun emitError(error: String?) {
            lastError = error
            errorListener?.invoke(error)
        }

        fun triggerRemoteWakeup() {
            wakeupListener?.invoke()
        }

        fun deliverIncoming(messageId: String, ciphertextBase64: String) {
            preservedInbox[messageId] = ciphertextBase64
            encryptedListener?.invoke(messageId, ciphertextBase64) { success ->
                processedResults[messageId] = success
                if (success) {
                    preservedInbox.remove(messageId)
                    consumedIds.add(messageId)
                }
            }
        }
    }

    private fun generateHpkeKeypair(): KeysetHandle {
        return KeysetHandle.generateNew(
            KeyTemplates.get("DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_CHACHA20_POLY1305")
        )
    }

    private fun newIsolatedTrustStore(tag: String): TrustStore {
        val uniquePrefix = "${tag}_${testPrefixCounter.incrementAndGet()}"
        val context = PrefixedPrefsContext(RuntimeEnvironment.getApplication(), uniquePrefix)
        return TrustStore(context)
    }

    private fun registerManager(manager: TransportManager): TransportManager {
        managersToClose.add(manager)
        return manager
    }

    private suspend fun awaitCondition(
        timeoutMs: Long = 3000L,
        failureMessage: String = "Timed out waiting for condition",
        condition: () -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10L)
        }
        if (!condition()) {
            fail(failureMessage)
        }
    }

    @Test
    fun requestConnection_whilePaused_unpausesGoesOnlineSendsWakeupAndDispatchesFailedMessages() = runBlocking {
        val encryption = MessageEncryption()
        val senderPrivateKey = generateHpkeKeypair()
        val viewerPrivateKey = generateHpkeKeypair()

        val senderTrustStore = newIsolatedTrustStore("sender_unpause")
        senderTrustStore.addTrustedPeer(
            deviceId = "VIEWER_FP_01",
            serializedPublicKeyset = senderTrustStore.serializePublicKeyset(viewerPrivateKey.publicKeysetHandle),
            name = "Viewer Phone"
        )

        val storageCipher = FakeStorageCipher()
        val dao = FakeMessageDao()
        val queueManager = MessageQueueManager(dao, storageCipher)
        val relay = FakeRelayTransport(initialRelayAvailable = true, initialPeerOnline = true)

        val manager = registerManager(
            TransportManager(
                relayTransport = relay,
                encryption = encryption,
                trustStore = senderTrustStore,
                queueManager = queueManager,
                storageCipher = storageCipher,
                isInitiator = true,
                localPrivateKeyProvider = { senderPrivateKey },
                localFingerprintProvider = { "SENDER_FP_01" },
                retryIntervalMs = 60_000L
            )
        )

        manager.setConnectionPaused(true)
        assertTrue(manager.isPaused)
        assertEquals("PAUSED", manager.connectionUiState.value.status)
        assertTrue(manager.connectionUiState.value.isPaused)
        assertFalse(manager.connectionUiState.value.isRelayConnected)
        assertEquals(1, relay.goOfflineCount.get())

        dao.messages["msg-failed-1"] = MessageEntity(
            messageId = "msg-failed-1",
            sequenceNumber = 1L,
            timestamp = 1700000100L,
            senderDeviceId = "+15551112222",
            encryptedPayload = storageCipher.encrypt("Retry me after unpause"),
            state = MessageState.FAILED,
            retryCount = 26
        )

        delay(30L)
        assertEquals(MessageState.FAILED, dao.messages["msg-failed-1"]?.state)
        assertTrue(relay.sentMessages.isEmpty())

        manager.requestConnection()

        assertFalse(manager.isPaused)
        assertFalse(manager.connectionUiState.value.isPaused)
        assertNotEquals("PAUSED", manager.connectionUiState.value.status)
        assertTrue(relay.goOnlineCount.get() >= 1)
        assertEquals(1, relay.wakeupCount.get())

        awaitCondition(failureMessage = "Expected FAILED message to be requeued and dispatched") {
            relay.sentMessages.any { it.first == "msg-failed-1" } &&
                dao.messages["msg-failed-1"]?.state == MessageState.TRANSMITTING
        }

        val sentBase64 = relay.sentMessages.first { it.first == "msg-failed-1" }.second
        val decryptedJson = JSONObject(
            String(
                encryption.decrypt(Base64.decode(sentBase64, Base64.NO_WRAP), viewerPrivateKey),
                Charsets.UTF_8
            )
        )
        assertEquals("SMS", decryptedJson.getString("type"))
        assertEquals("msg-failed-1", decryptedJson.getString("id"))
        assertEquals("Retry me after unpause", decryptedJson.getString("body"))
        assertEquals("+15551112222", decryptedJson.getString("sender"))
        assertEquals("SENDER_FP_01", decryptedJson.getString("forwarder"))
        assertEquals(1, dao.messages["msg-failed-1"]?.retryCount)
    }

    @Test
    fun drainQueueInternal_mutexSerializesConcurrentCallsWithoutDuplicates() = runBlocking {
        val encryption = MessageEncryption()
        val senderPrivateKey = generateHpkeKeypair()
        val viewerPrivateKey = generateHpkeKeypair()

        val senderTrustStore = newIsolatedTrustStore("sender_mutex")
        senderTrustStore.addTrustedPeer(
            deviceId = "VIEWER_FP_02",
            serializedPublicKeyset = senderTrustStore.serializePublicKeyset(viewerPrivateKey.publicKeysetHandle)
        )

        val storageCipher = FakeStorageCipher()
        val dao = FakeMessageDao()
        val queueManager = MessageQueueManager(dao, storageCipher)
        val relay = FakeRelayTransport(initialRelayAvailable = true, initialPeerOnline = true)

        val manager = registerManager(
            TransportManager(
                relayTransport = relay,
                encryption = encryption,
                trustStore = senderTrustStore,
                queueManager = queueManager,
                storageCipher = storageCipher,
                isInitiator = true,
                localPrivateKeyProvider = { senderPrivateKey },
                localFingerprintProvider = { "SENDER_FP_02" },
                retryIntervalMs = 60_000L
            )
        )

        // Allow initial retry loop tick on empty queue to settle before enqueuing
        delay(50L)

        relay.sendDelayMs = 40L
        queueManager.enqueueMessage(
            ParsedSms(
                sender = "+15550009999",
                timestamp = 1700000200L,
                body = "Mutex OTP 999888",
                subscriptionId = 1,
                messageId = "msg-mutex-1"
            )
        )

        val storedRaw = String(dao.messages["msg-mutex-1"]!!.encryptedPayload!!, Charsets.UTF_8)
        assertEquals("FAKE_ENC:Mutex OTP 999888", storedRaw)

        val jobs = (1..5).map {
            async(Dispatchers.IO) {
                manager.drainQueueInternal()
            }
        }
        jobs.awaitAll()

        assertEquals(1, relay.sentMessages.count { it.first == "msg-mutex-1" })
        assertEquals(MessageState.TRANSMITTING, dao.messages["msg-mutex-1"]?.state)
        assertEquals(1, dao.messages["msg-mutex-1"]?.retryCount)
    }

    @Test
    fun hpkeEndToEndRoundTrip_smsAckWipeAndUnauthorizedWipeIgnoredBySender() = runBlocking {
        val encryption = MessageEncryption()
        val senderPrivateKey = generateHpkeKeypair()
        val viewerPrivateKey = generateHpkeKeypair()

        val senderTrustStore = newIsolatedTrustStore("sender_e2e")
        val viewerTrustStore = newIsolatedTrustStore("viewer_e2e")

        senderTrustStore.addTrustedPeer(
            deviceId = "VIEWER_FP_03",
            serializedPublicKeyset = senderTrustStore.serializePublicKeyset(viewerPrivateKey.publicKeysetHandle),
            name = "Viewer"
        )
        viewerTrustStore.addTrustedPeer(
            deviceId = "SENDER_FP_03",
            serializedPublicKeyset = viewerTrustStore.serializePublicKeyset(senderPrivateKey.publicKeysetHandle),
            name = "Sender"
        )

        val senderCipher = FakeStorageCipher()
        val viewerCipher = FakeStorageCipher()
        val senderDao = FakeMessageDao()
        val viewerDao = FakeMessageDao()
        val senderQueue = MessageQueueManager(senderDao, senderCipher)
        val viewerQueue = MessageQueueManager(viewerDao, viewerCipher)

        val senderRelay = FakeRelayTransport(initialRelayAvailable = true, initialPeerOnline = true)
        val viewerRelay = FakeRelayTransport(initialRelayAvailable = true, initialPeerOnline = true)
        senderRelay.peerTransport = viewerRelay
        viewerRelay.peerTransport = senderRelay

        val receivedNotifications = CopyOnWriteArrayList<Pair<String, String>>()

        val senderManager = registerManager(
            TransportManager(
                relayTransport = senderRelay,
                encryption = encryption,
                trustStore = senderTrustStore,
                queueManager = senderQueue,
                storageCipher = senderCipher,
                isInitiator = true,
                localPrivateKeyProvider = { senderPrivateKey },
                localFingerprintProvider = { "SENDER_FP_03" },
                retryIntervalMs = 60_000L
            )
        )
        val viewerManager = registerManager(
            TransportManager(
                relayTransport = viewerRelay,
                encryption = encryption,
                trustStore = viewerTrustStore,
                queueManager = viewerQueue,
                storageCipher = viewerCipher,
                isInitiator = false,
                localPrivateKeyProvider = { viewerPrivateKey },
                localFingerprintProvider = { "VIEWER_FP_03" },
                retryIntervalMs = 60_000L,
                onMessageReceived = { sender, body ->
                    receivedNotifications.add(sender to body)
                }
            )
        )

        delay(50L)

        // 1. Sender -> Viewer SMS and Viewer -> Sender ACK
        senderQueue.enqueueMessage(
            ParsedSms(
                sender = "+15551234567",
                timestamp = 1700001234L,
                body = "Your OTP is 424242",
                subscriptionId = 1,
                messageId = "msg-e2e-1"
            )
        )
        senderManager.drainQueueInternal()

        awaitCondition(failureMessage = "Expected Viewer to persist SMS, consume inbox item, and Sender to receive & consume ACK") {
            viewerDao.messages.containsKey("msg-e2e-1") &&
                senderDao.messages["msg-e2e-1"]?.state == MessageState.DELIVERED &&
                receivedNotifications.isNotEmpty() &&
                viewerRelay.consumedIds.contains("msg-e2e-1") &&
                senderRelay.consumedIds.contains("ACK_msg-e2e-1")
        }

        val viewerStored = viewerDao.messages["msg-e2e-1"]
        assertNotNull(viewerStored)
        assertEquals(MessageState.DELIVERED, viewerStored?.state)
        assertTrue(
            String(viewerStored!!.encryptedPayload!!, Charsets.UTF_8)
                .startsWith(FakeStorageCipher.PREFIX)
        )
        assertEquals("Your OTP is 424242", viewerCipher.decrypt(viewerStored.encryptedPayload))
        assertEquals("+15551234567", viewerStored.senderDeviceId)
        assertEquals("SENDER_FP_03", viewerStored.forwardingDevice)
        assertEquals(listOf("+15551234567" to "Your OTP is 424242"), receivedNotifications.toList())
        assertTrue(viewerRelay.consumedIds.contains("msg-e2e-1"))
        assertTrue(senderRelay.consumedIds.contains("ACK_msg-e2e-1"))

        // 2. Sender -> Viewer WIPE clears Viewer DB
        senderManager.sendWipeCommand()
        awaitCondition(failureMessage = "Expected Viewer DB to be wiped by Sender WIPE command") {
            viewerDao.messages.isEmpty() && viewerRelay.consumedIds.contains("WIPE_ALL")
        }
        assertTrue(viewerDao.messages.isEmpty())

        // 3. Viewer -> Sender unauthorized WIPE must NOT delete Sender DB
        viewerManager.sendWipeCommand()
        awaitCondition(failureMessage = "Expected Sender to process and ignore Viewer WIPE command") {
            senderRelay.processedResults["WIPE_ALL"] == true
        }
        assertTrue(senderDao.messages.containsKey("msg-e2e-1"))
    }

    @Test
    fun strictJsonFraming_rejectsLegacyPlaintextAndUnknownTypesWithoutModifyingState() = runBlocking {
        val encryption = MessageEncryption()
        val senderPrivateKey = generateHpkeKeypair()
        val viewerPrivateKey = generateHpkeKeypair()

        val senderTrustStore = newIsolatedTrustStore("sender_strict")
        val viewerTrustStore = newIsolatedTrustStore("viewer_strict")
        senderTrustStore.addTrustedPeer(
            deviceId = "VIEWER_FP_04",
            serializedPublicKeyset = senderTrustStore.serializePublicKeyset(viewerPrivateKey.publicKeysetHandle)
        )
        viewerTrustStore.addTrustedPeer(
            deviceId = "SENDER_FP_04",
            serializedPublicKeyset = viewerTrustStore.serializePublicKeyset(senderPrivateKey.publicKeysetHandle)
        )

        val storageCipher = FakeStorageCipher()
        val senderDao = FakeMessageDao()
        val viewerDao = FakeMessageDao()
        val senderQueue = MessageQueueManager(senderDao, storageCipher)
        val viewerQueue = MessageQueueManager(viewerDao, storageCipher)

        val senderRelay = FakeRelayTransport(initialRelayAvailable = true, initialPeerOnline = true)
        val viewerRelay = FakeRelayTransport(initialRelayAvailable = true, initialPeerOnline = true)
        val viewerNotifications = CopyOnWriteArrayList<Pair<String, String>>()

        // Pause Sender so retry loop does not drain "pending-1"
        val senderManager = registerManager(
            TransportManager(
                relayTransport = senderRelay,
                encryption = encryption,
                trustStore = senderTrustStore,
                queueManager = senderQueue,
                storageCipher = storageCipher,
                isInitiator = true,
                localPrivateKeyProvider = { senderPrivateKey },
                localFingerprintProvider = { "SENDER_FP_04" },
                retryIntervalMs = 60_000L
            )
        )
        senderManager.setConnectionPaused(true)

        registerManager(
            TransportManager(
                relayTransport = viewerRelay,
                encryption = encryption,
                trustStore = viewerTrustStore,
                queueManager = viewerQueue,
                storageCipher = storageCipher,
                isInitiator = false,
                localPrivateKeyProvider = { viewerPrivateKey },
                localFingerprintProvider = { "VIEWER_FP_04" },
                retryIntervalMs = 60_000L,
                onMessageReceived = { s, b -> viewerNotifications.add(s to b) }
            )
        )

        viewerDao.messages["existing-1"] = MessageEntity(
            messageId = "existing-1",
            sequenceNumber = 1L,
            timestamp = 1700000300L,
            senderDeviceId = "+15550001",
            encryptedPayload = storageCipher.encrypt("Keep me"),
            state = MessageState.DELIVERED
        )
        senderDao.messages["msg-1"] = MessageEntity(
            messageId = "msg-1",
            sequenceNumber = 1L,
            timestamp = 1700000301L,
            senderDeviceId = "+15550002",
            encryptedPayload = storageCipher.encrypt("Pending msg"),
            state = MessageState.QUEUED
        )

        fun encryptFor(payload: String, recipientPublicKey: KeysetHandle): String {
            val ct = encryption.encrypt(payload.toByteArray(Charsets.UTF_8), recipientPublicKey)
            return Base64.encodeToString(ct, Base64.NO_WRAP)
        }

        viewerRelay.deliverIncoming("legacy-wipe", encryptFor("WIPE:ALL", viewerPrivateKey.publicKeysetHandle))
        senderRelay.deliverIncoming("legacy-ack", encryptFor("ACK:msg-1", senderPrivateKey.publicKeysetHandle))
        viewerRelay.deliverIncoming("legacy-sms", encryptFor("msg-1|hello", viewerPrivateKey.publicKeysetHandle))
        viewerRelay.deliverIncoming(
            "unknown-type",
            encryptFor("""{"type":"UNKNOWN","id":"u1"}""", viewerPrivateKey.publicKeysetHandle)
        )

        awaitCondition(failureMessage = "Expected all 4 invalid payloads to invoke onProcessed(false)") {
            viewerRelay.processedResults["legacy-wipe"] == false &&
                senderRelay.processedResults["legacy-ack"] == false &&
                viewerRelay.processedResults["legacy-sms"] == false &&
                viewerRelay.processedResults["unknown-type"] == false
        }

        // All rejected items remain preserved in relay inbox and local state is untouched
        assertTrue(viewerRelay.preservedInbox.containsKey("legacy-wipe"))
        assertTrue(senderRelay.preservedInbox.containsKey("legacy-ack"))
        assertTrue(viewerRelay.preservedInbox.containsKey("legacy-sms"))
        assertTrue(viewerRelay.preservedInbox.containsKey("unknown-type"))

        assertEquals(1, viewerDao.messages.size)
        assertTrue(viewerDao.messages.containsKey("existing-1"))
        assertTrue(viewerNotifications.isEmpty())
        assertEquals(MessageState.QUEUED, senderDao.messages["msg-1"]?.state)
    }

    @Test
    fun firebaseErrorPropagation_updatesAndClearsLastErrorInUiState() = runBlocking {
        val encryption = MessageEncryption()
        val localKey = generateHpkeKeypair()
        val trustStore = newIsolatedTrustStore("error_prop")
        val storageCipher = FakeStorageCipher()
        val dao = FakeMessageDao()
        val queueManager = MessageQueueManager(dao, storageCipher)
        val relay = FakeRelayTransport(initialRelayAvailable = true, initialPeerOnline = false)

        val manager = registerManager(
            TransportManager(
                relayTransport = relay,
                encryption = encryption,
                trustStore = trustStore,
                queueManager = queueManager,
                storageCipher = storageCipher,
                isInitiator = true,
                localPrivateKeyProvider = { localKey },
                localFingerprintProvider = { "SENDER_ERR" },
                retryIntervalMs = 60_000L
            )
        )

        assertNull(manager.connectionUiState.value.lastError)

        relay.emitError("Firebase error: Permission denied")
        assertEquals("Firebase error: Permission denied", manager.connectionUiState.value.lastError)

        relay.emitError(null)
        assertNull(manager.connectionUiState.value.lastError)
    }

    @Test
    fun close_invokesRelayTransportClose() {
        val encryption = MessageEncryption()
        val localKey = generateHpkeKeypair()
        val trustStore = newIsolatedTrustStore("close_test")
        val storageCipher = FakeStorageCipher()
        val dao = FakeMessageDao()
        val queueManager = MessageQueueManager(dao, storageCipher)
        val relay = FakeRelayTransport()

        val manager = TransportManager(
            relayTransport = relay,
            encryption = encryption,
            trustStore = trustStore,
            queueManager = queueManager,
            storageCipher = storageCipher,
            isInitiator = true,
            localPrivateKeyProvider = { localKey },
            localFingerprintProvider = { "SENDER_CLOSE" },
            retryIntervalMs = 60_000L
        )

        assertFalse(relay.isClosed)
        assertEquals(0, relay.closeCount.get())

        manager.close()

        assertTrue(relay.isClosed)
        assertEquals(1, relay.closeCount.get())
    }

    @Test
    fun statusTransitions_relayStandbyToConnectedAndAutoRequeuesFailedMessages() = runBlocking {
        val encryption = MessageEncryption()
        val senderPrivateKey = generateHpkeKeypair()
        val viewerPrivateKey = generateHpkeKeypair()

        val senderTrustStore = newIsolatedTrustStore("sender_status")
        senderTrustStore.addTrustedPeer(
            deviceId = "VIEWER_FP_05",
            serializedPublicKeyset = senderTrustStore.serializePublicKeyset(viewerPrivateKey.publicKeysetHandle)
        )

        val storageCipher = FakeStorageCipher()
        val dao = FakeMessageDao()
        val queueManager = MessageQueueManager(dao, storageCipher)
        val relay = FakeRelayTransport(initialRelayAvailable = false, initialPeerOnline = false)

        val manager = registerManager(
            TransportManager(
                relayTransport = relay,
                encryption = encryption,
                trustStore = senderTrustStore,
                queueManager = queueManager,
                storageCipher = storageCipher,
                isInitiator = true,
                localPrivateKeyProvider = { senderPrivateKey },
                localFingerprintProvider = { "SENDER_FP_05" },
                retryIntervalMs = 60_000L
            )
        )

        assertEquals("CONNECTING", manager.connectionUiState.value.status)
        assertFalse(manager.connectionUiState.value.isRelayConnected)
        assertFalse(manager.connectionUiState.value.isPeerOnline)

        dao.messages["msg-auto-requeue"] = MessageEntity(
            messageId = "msg-auto-requeue",
            sequenceNumber = 1L,
            timestamp = 1700000500L,
            senderDeviceId = "+15557778888",
            encryptedPayload = storageCipher.encrypt("Wakeup when peer online"),
            state = MessageState.FAILED,
            retryCount = 26
        )

        // Relay comes online while peer is still offline -> RELAY STANDBY (Peer Offline)
        relay.setRelayAvailable(true)
        assertEquals("RELAY STANDBY (Peer Offline)", manager.connectionUiState.value.status)
        assertTrue(manager.connectionUiState.value.isRelayConnected)
        assertFalse(manager.connectionUiState.value.isPeerOnline)

        delay(50L)
        assertEquals(MessageState.FAILED, dao.messages["msg-auto-requeue"]?.state)
        assertTrue(relay.sentMessages.isEmpty())

        // Peer comes online -> CONNECTED (Encrypted Relay) + auto-requeue & dispatch
        relay.setPeerPresence(online = true, lastSeen = 1700009999L)
        assertEquals("CONNECTED (Encrypted Relay)", manager.connectionUiState.value.status)
        assertTrue(manager.connectionUiState.value.isRelayConnected)
        assertTrue(manager.connectionUiState.value.isPeerOnline)
        assertEquals(1700009999L, manager.connectionUiState.value.peerLastSeen)

        awaitCondition(failureMessage = "Expected FAILED message to be auto-requeued when peer came online") {
            relay.sentMessages.any { it.first == "msg-auto-requeue" } &&
                dao.messages["msg-auto-requeue"]?.state == MessageState.TRANSMITTING
        }
    }
}

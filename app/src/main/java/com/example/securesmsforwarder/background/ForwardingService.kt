package com.example.securesmsforwarder.background

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.securesmsforwarder.AppContainer
import com.example.securesmsforwarder.MainActivity
import com.example.securesmsforwarder.SecureSmsApplication
import com.example.securesmsforwarder.core.domain.DeviceRole
import com.example.securesmsforwarder.p2p.ConnectionUiState
import com.example.securesmsforwarder.p2p.TransportManager
import com.example.securesmsforwarder.p2p.relay.FirebaseRelayTransport
import com.example.securesmsforwarder.sms.SmsObserver
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ForwardingService : Service() {

    private val appContainer: AppContainer by lazy { SecureSmsApplication.container(this) }
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transportScope: CoroutineScope? = null

    @Volatile
    private var transportManager: TransportManager? = null
    private var smsObserver: SmsObserver? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "ForwardingService"
        private const val CHANNEL_ID = "ForwardingServiceChannel"
        private const val MESSAGE_CHANNEL_ID = "IncomingMessagesChannel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CONNECT = "com.example.securesmsforwarder.ACTION_CONNECT"
        const val ACTION_DISCONNECT = "com.example.securesmsforwarder.ACTION_DISCONNECT"
        const val ACTION_RESTART = "com.example.securesmsforwarder.ACTION_RESTART"
        const val ACTION_DRAIN_QUEUE = "com.example.securesmsforwarder.ACTION_DRAIN_QUEUE"
        const val ACTION_WIPE_PEER = "com.example.securesmsforwarder.ACTION_WIPE_PEER"

        private val _connectionUiState = MutableStateFlow(ConnectionUiState())
        val connectionUiState: StateFlow<ConnectionUiState> = _connectionUiState.asStateFlow()

        @Volatile
        var activeTransportManager: TransportManager? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Secure SMS Forwarder")
            .setContentText("Encrypted relay forwarding service is active.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        initializeTransport()
        registerNetworkCallback()
    }

    private fun teardownTransport() {
        smsObserver?.stop()
        smsObserver = null
        transportScope?.cancel()
        transportScope = null
        transportManager?.close()
        transportManager = null
        activeTransportManager = null
    }

    private fun initializeTransport() {
        teardownTransport()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        transportScope = scope

        val roleManager = appContainer.roleManager
        val trustStore = appContainer.trustStore
        val keyManager = appContainer.keyManager
        val settingsManager = appContainer.settingsManager
        val queueManager = appContainer.messageQueueManager
        val storageCipher = appContainer.storageCipher
        val encryption = appContainer.messageEncryption

        if (roleManager.currentRole == DeviceRole.SENDER) {
            smsObserver = SmsObserver(this).also { it.start() }
        }

        val remoteDeviceId = trustStore.getTrustedDeviceId()
        if (remoteDeviceId != null) {
            val localDeviceId = keyManager.getPublicKeyFingerprint()
            val relayTransport = FirebaseRelayTransport(
                localDeviceId = localDeviceId,
                remoteDeviceId = remoteDeviceId,
                role = roleManager.currentRole
            )

            val tm = TransportManager(
                relayTransport = relayTransport,
                encryption = encryption,
                keyManager = keyManager,
                trustStore = trustStore,
                queueManager = queueManager,
                storageCipher = storageCipher,
                isInitiator = (roleManager.currentRole == DeviceRole.SENDER),
                onMessageReceived = { sender, body ->
                    showIncomingMessageNotification(sender, body)
                }
            )
            transportManager = tm
            activeTransportManager = tm

            if (settingsManager.isConnectionPaused) {
                tm.setConnectionPaused(true)
            } else if (roleManager.currentRole == DeviceRole.VIEWER) {
                relayTransport.requestWakeup()
            }
            _connectionUiState.value = tm.connectionUiState.value

            scope.launch {
                tm.connectionUiState.collect { state ->
                    _connectionUiState.value = state
                }
            }
            scope.launch {
                settingsManager.connectionPausedFlow.collect { isPaused ->
                    tm.setConnectionPaused(isPaused)
                }
            }
        } else {
            activeTransportManager = null
            val initiallyPaused = settingsManager.isConnectionPaused
            _connectionUiState.value = ConnectionUiState(
                status = if (initiallyPaused) "PAUSED" else "DISCONNECTED",
                isPaused = initiallyPaused
            )
            scope.launch {
                settingsManager.connectionPausedFlow.collect { isPaused ->
                    _connectionUiState.value = ConnectionUiState(
                        status = if (isPaused) "PAUSED" else "DISCONNECTED",
                        isPaused = isPaused
                    )
                }
            }
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Internet connection restored. Resuming Firebase & syncing queue...")
                FirebaseDatabase.getInstance().goOnline()

                if (!appContainer.settingsManager.isConnectionPaused &&
                    appContainer.trustStore.getTrustedDeviceId() != null
                ) {
                    serviceScope.launch {
                        delay(1000L)
                        if (!appContainer.settingsManager.isConnectionPaused) {
                            Log.d(TAG, "Triggering automatic reconnection and queue drain...")
                            transportManager?.requestConnection()
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                val activeCaps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
                val hasValidatedInternet = activeCaps != null &&
                    activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    activeCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (!hasValidatedInternet) {
                    Log.d(TAG, "All validated internet networks lost. Taking Firebase offline.")
                    FirebaseDatabase.getInstance().goOffline()
                } else {
                    Log.d(TAG, "Network lost, but another validated internet network remains active.")
                }
            }
        }

        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback", e)
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister NetworkCallback", e)
            }
        }
        networkCallback = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                Log.d(TAG, "Handling ACTION_CONNECT.")
                appContainer.settingsManager.isConnectionPaused = false
                FirebaseDatabase.getInstance().goOnline()
                if (transportManager == null && appContainer.trustStore.getTrustedDeviceId() != null) {
                    initializeTransport()
                }
                val tm = transportManager
                if (tm != null) {
                    tm.setConnectionPaused(false)
                    tm.requestConnection()
                } else {
                    _connectionUiState.value = ConnectionUiState(status = "DISCONNECTED", isPaused = false)
                }
            }
            ACTION_DISCONNECT -> {
                Log.d(TAG, "Handling ACTION_DISCONNECT.")
                appContainer.settingsManager.isConnectionPaused = true
                val tm = transportManager
                if (tm != null) {
                    tm.setConnectionPaused(true)
                } else {
                    _connectionUiState.value = ConnectionUiState(status = "PAUSED", isPaused = true)
                }
            }
            ACTION_RESTART, "ACTION_RESTART" -> {
                Log.d(TAG, "Handling ACTION_RESTART.")
                initializeTransport()
            }
            ACTION_DRAIN_QUEUE, "ACTION_DRAIN_QUEUE" -> {
                Log.d(TAG, "Handling ACTION_DRAIN_QUEUE. Draining pending messages.")
                if (transportManager == null && appContainer.trustStore.getTrustedDeviceId() != null) {
                    initializeTransport()
                }
                transportManager?.triggerQueueDrain()
            }
            ACTION_WIPE_PEER -> {
                Log.d(TAG, "Handling ACTION_WIPE_PEER.")
                if (transportManager == null && appContainer.trustStore.getTrustedDeviceId() != null) {
                    initializeTransport()
                }
                transportManager?.sendWipeCommand()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        teardownTransport()
        serviceScope.cancel()
        val isPaused = appContainer.settingsManager.isConnectionPaused
        _connectionUiState.value = ConnectionUiState(
            status = if (isPaused) "PAUSED" else "DISCONNECTED",
            isPaused = isPaused
        )
    }

    inner class LocalBinder : Binder() {
        fun getService(): ForwardingService = this@ForwardingService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun getTransportManager(): TransportManager? {
        return transportManager
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Encrypted Relay Forwarding Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)

        val messageChannel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            "Incoming Messages",
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(messageChannel)
    }

    private fun showIncomingMessageNotification(sender: String, body: String) {
        if (!appContainer.settingsManager.isNotificationsEnabled) {
            return
        }
        serviceScope.launch {
            try {
                val unreadMessages = appContainer.database.messageDao().getUnreadMessages()

                val intent = Intent(this@ForwardingService, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                val pendingIntent = PendingIntent.getActivity(
                    this@ForwardingService,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE
                )

                val inboxStyle = NotificationCompat.InboxStyle()

                // Limit to max 7 lines in the inbox style to avoid huge notifications
                unreadMessages.take(7).reversed().forEach { msg ->
                    val text = runCatching {
                        appContainer.storageCipher.decrypt(msg.encryptedPayload)
                    }.getOrDefault("[Encrypted message]")
                    inboxStyle.addLine("${msg.senderDeviceId}: $text")
                }

                if (unreadMessages.size > 7) {
                    inboxStyle.setSummaryText("+${unreadMessages.size - 7} more")
                }

                val title = if (unreadMessages.size > 1) {
                    "${unreadMessages.size} new messages"
                } else {
                    unreadMessages.firstOrNull()?.senderDeviceId ?: sender
                }

                val notification = NotificationCompat.Builder(this@ForwardingService, MESSAGE_CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_notify_chat)
                    .setContentTitle(title)
                    .setContentText(if (unreadMessages.size > 1) "You have new unread messages" else body)
                    .setStyle(inboxStyle)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .build()

                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(2, notification) // Use fixed ID to replace previous notification
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show incoming message notification", e)
            }
        }
    }
}

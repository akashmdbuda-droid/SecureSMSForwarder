package com.example.securesmsforwarder.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.IBinder
import android.app.PendingIntent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.securesmsforwarder.core.domain.MessageQueueManager
import com.example.securesmsforwarder.core.domain.SettingsManager
import com.example.securesmsforwarder.crypto.encryption.MessageEncryption
import com.example.securesmsforwarder.crypto.identity.KeyManager
import com.example.securesmsforwarder.p2p.TransportManager
import com.example.securesmsforwarder.p2p.webrtc.WebRtcDirectTransport
import com.example.securesmsforwarder.pairing.TrustStore
import com.example.securesmsforwarder.storage.AppDatabase
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ForwardingService : Service() {

    private var transportManager: TransportManager? = null
    private var webRtcTransport: WebRtcDirectTransport? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "ForwardingService"
        private const val CHANNEL_ID = "ForwardingServiceChannel"
        private const val MESSAGE_CHANNEL_ID = "IncomingMessagesChannel"
        private const val NOTIFICATION_ID = 1
        
        // Expose signaling provider to the UI without complex binding for V1
        var activeSignalingProvider: com.example.securesmsforwarder.p2p.signaling.ManualSignalingProvider? = null
            private set
            
        var activeTransportManager: TransportManager? = null
            private set
            
        var connectionStateFlow: kotlinx.coroutines.flow.StateFlow<String>? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Secure SMS Forwarder")
            .setContentText("Forwarding service is active to provide real-time connection.")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        initializeTransport()
        registerNetworkCallback()
    }

    private var smsObserver: com.example.securesmsforwarder.sms.SmsObserver? = null

    private fun initializeTransport() {
        val db = AppDatabase.getDatabase(this)
        webRtcTransport = WebRtcDirectTransport(this)
        val keyManager = KeyManager(this)
        
        // Use the visual fingerprint as a short Device ID for signaling envelopes
        val deviceId = keyManager.getPublicKeyFingerprint()
        val roleManager = com.example.securesmsforwarder.core.domain.RoleManager(this)
        val trustStore = TrustStore(this)
        
        val remoteDeviceId = trustStore.getTrustedDeviceId()
        val signaling = if (remoteDeviceId != null) {
            com.example.securesmsforwarder.p2p.signaling.FirebaseSignalingProvider(
                localDeviceId = deviceId,
                remoteDeviceId = remoteDeviceId,
                role = roleManager.currentRole
            )
        } else {
            com.example.securesmsforwarder.p2p.signaling.ManualSignalingProvider(deviceId, roleManager.currentRole)
        }
        
        transportManager = TransportManager(
            webRtcTransport = webRtcTransport!!,
            encryption = MessageEncryption(),
            keyManager = keyManager,
            trustStore = trustStore,
            queueManager = MessageQueueManager(db.messageDao()),
            signalingProvider = signaling,
            isInitiator = roleManager.currentRole == com.example.securesmsforwarder.core.domain.DeviceRole.SENDER,
            onMessageReceived = { sender, body ->
                showIncomingMessageNotification(sender, body)
            }
        )
        
        activeTransportManager = transportManager
        
        // Only cast if it's the Manual provider for V1 UI, else UI won't show QR
        activeSignalingProvider = signaling as? com.example.securesmsforwarder.p2p.signaling.ManualSignalingProvider
        
        // Bind unified dual-tier connection state to the UI
        connectionStateFlow = transportManager?.connectionStatusFlow
        
        val settingsManager = SettingsManager(this)
        CoroutineScope(Dispatchers.IO).launch {
            settingsManager.connectionPausedFlow.collect { isPaused ->
                transportManager?.setConnectionPaused(isPaused)
            }
        }
        
        if (roleManager.currentRole == com.example.securesmsforwarder.core.domain.DeviceRole.SENDER) {
            smsObserver = com.example.securesmsforwarder.sms.SmsObserver(this)
            smsObserver?.start()
        }

        if (!settingsManager.isConnectionPaused) {
            if (roleManager.currentRole == com.example.securesmsforwarder.core.domain.DeviceRole.SENDER) {
                webRtcTransport?.startConnection(isInitiator = true, useTrickleIce = signaling.supportsTrickleIce())
            } else if (roleManager.currentRole == com.example.securesmsforwarder.core.domain.DeviceRole.VIEWER) {
                webRtcTransport?.startConnection(isInitiator = false, useTrickleIce = signaling.supportsTrickleIce())
                signaling.requestConnection()
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
                
                val settingsManager = SettingsManager(this@ForwardingService)
                val trustStore = TrustStore(this@ForwardingService)
                if (!settingsManager.isConnectionPaused && trustStore.getTrustedDeviceId() != null) {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(1000)
                        Log.d(TAG, "Triggering automatic reconnection and queue drain...")
                        transportManager?.requestConnection()
                    }
                }
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "Internet connection lost.")
                FirebaseDatabase.getInstance().goOffline()
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
        if (intent?.action == "ACTION_RESTART") {
            smsObserver?.stop()
            webRtcTransport?.close()
            transportManager?.close()
            initializeTransport()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNetworkCallback()
        smsObserver?.stop()
        webRtcTransport?.close()
        transportManager?.close()
        transportManager = null
        activeSignalingProvider = null
        activeTransportManager = null
    }

    inner class LocalBinder : android.os.Binder() {
        fun getService(): ForwardingService = this@ForwardingService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun getSignalingProvider(): com.example.securesmsforwarder.p2p.signaling.ManualSignalingProvider? {
        return transportManager?.signalingProvider as? com.example.securesmsforwarder.p2p.signaling.ManualSignalingProvider
    }

    fun getTransportManager(): TransportManager? {
        return transportManager
    }

    fun getWebRtcTransport(): WebRtcDirectTransport? {
        return webRtcTransport
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "P2P Forwarding Service",
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
        CoroutineScope(Dispatchers.IO).launch {
            val unreadMessages = AppDatabase.getDatabase(this@ForwardingService).messageDao().getUnreadMessages()
            
            val intent = Intent(this@ForwardingService, com.example.securesmsforwarder.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(this@ForwardingService, 0, intent, PendingIntent.FLAG_IMMUTABLE)

            val inboxStyle = NotificationCompat.InboxStyle()
            
            // Limit to max 7 lines in the inbox style to avoid huge notifications
            unreadMessages.take(7).reversed().forEach { msg ->
                val text = String(msg.encryptedPayload ?: ByteArray(0), Charsets.UTF_8)
                inboxStyle.addLine("${msg.senderDeviceId}: $text")
            }
            
            if (unreadMessages.size > 7) {
                inboxStyle.setSummaryText("+${unreadMessages.size - 7} more")
            }

            val title = if (unreadMessages.size > 1) "${unreadMessages.size} new messages" else unreadMessages.firstOrNull()?.senderDeviceId ?: sender

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
        }
    }
}

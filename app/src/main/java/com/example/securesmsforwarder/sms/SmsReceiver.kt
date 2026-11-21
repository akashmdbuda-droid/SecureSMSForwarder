package com.example.securesmsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.securesmsforwarder.SecureSmsApplication
import com.example.securesmsforwarder.background.ForwardingService
import com.example.securesmsforwarder.core.domain.DeviceRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parsedMessages = SmsParser.parseIntent(intent)
        if (parsedMessages.isEmpty()) return

        // Acquire WakeLock so CPU doesn't sleep while enqueuing and dispatching
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wakeLock = powerManager?.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SecureSmsForwarder:SmsReceiverWakeLock"
        )
        wakeLock?.acquire(15_000L) // 15 seconds max

        val pendingResult = goAsync()

        scope.launch {
            try {
                val container = SecureSmsApplication.container(context)
                val queueManager = container.messageQueueManager

                parsedMessages.forEach { sms ->
                    Log.d("SmsReceiver", "Received SMS from ${sms.sender}, length: ${sms.body.length}")
                    queueManager.enqueueMessage(sms)
                }

                // Ensure ForwardingService is running and trigger an immediate queue drain
                val roleManager = container.roleManager
                if (roleManager.currentRole == DeviceRole.SENDER) {
                    val activeTransport = ForwardingService.activeTransportManager
                    if (activeTransport != null) {
                        Log.d("SmsReceiver", "Active TransportManager found, triggering immediate queue drain.")
                        activeTransport.triggerQueueDrain()
                    } else {
                        Log.d("SmsReceiver", "Starting ForwardingService with ACTION_DRAIN_QUEUE.")
                        val serviceIntent = Intent(context, ForwardingService::class.java).apply {
                            action = ForwardingService.ACTION_DRAIN_QUEUE
                        }
                        ContextCompat.startForegroundService(context, serviceIntent)
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error processing SMS", e)
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {
                    Log.w("SmsReceiver", "WakeLock release error", e)
                }
                pendingResult.finish()
            }
        }
    }
}

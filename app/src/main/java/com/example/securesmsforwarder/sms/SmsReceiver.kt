package com.example.securesmsforwarder.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val parsedMessages = SmsParser.parseIntent(intent)
        
        // keep receiver alive during coroutine execution
        val pendingResult = goAsync() 
        
        scope.launch {
            try {
                parsedMessages.forEach { sms ->
                    Log.d("SmsReceiver", "Received SMS from ${sms.sender}, length: ${sms.body.length}")
                    val db = com.example.securesmsforwarder.storage.AppDatabase.getDatabase(context)
                    val queueManager = com.example.securesmsforwarder.core.domain.MessageQueueManager(db.messageDao())
                    queueManager.enqueueMessage(sms)
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error processing SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

package com.example.securesmsforwarder.sms

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.securesmsforwarder.core.domain.MessageQueueManager
import com.example.securesmsforwarder.storage.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsObserver(
    private val context: Context,
    handler: Handler = Handler(Looper.getMainLooper())
) : ContentObserver(handler) {

    private var lastProcessedTimestamp = System.currentTimeMillis()
    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        
        // Only run for general sms URI or inbox changes
        if (uri != null && !uri.toString().contains("content://sms")) return

        Log.d("SmsObserver", "SMS Content changed: $uri")
        
        scope.launch {
            try {
                // We only care about new messages in the inbox
                val inboxUri = Uri.parse("content://sms/inbox")
                val projection = arrayOf("_id", "address", "body", "date")
                
                val selection = "date > ?"
                val selectionArgs = arrayOf(lastProcessedTimestamp.toString())
                
                val cursor = context.contentResolver.query(inboxUri, projection, selection, selectionArgs, "date ASC")
                
                cursor?.use { c ->
                    val idIdx = c.getColumnIndex("_id")
                    val addrIdx = c.getColumnIndex("address")
                    val bodyIdx = c.getColumnIndex("body")
                    val dateIdx = c.getColumnIndex("date")
                    
                    val db = AppDatabase.getDatabase(context)
                    val queueManager = MessageQueueManager(db.messageDao())
                    
                    while (c.moveToNext()) {
                        val address = c.getString(addrIdx) ?: "Unknown"
                        val body = c.getString(bodyIdx) ?: ""
                        val date = c.getLong(dateIdx)
                        val id = c.getLong(idIdx)
                        
                        // Update timestamp so we don't process it again
                        if (date > lastProcessedTimestamp) {
                            lastProcessedTimestamp = date
                        }
                        
                        val parsed = ParsedSms(
                            sender = address,
                            body = body,
                            timestamp = date,
                            subscriptionId = -1,
                            messageId = "obs_$id"
                        )
                        
                        Log.d("SmsObserver", "Found new SMS via observer from $address")
                        queueManager.enqueueMessage(parsed)
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsObserver", "Error querying SMS content provider", e)
            }
        }
    }
    
    fun start() {
        // Init timestamp so we don't sync old messages immediately
        lastProcessedTimestamp = System.currentTimeMillis() - 5000 // Look back 5 seconds to catch race conditions
        try {
            context.contentResolver.registerContentObserver(
                Uri.parse("content://sms"),
                true,
                this
            )
            Log.d("SmsObserver", "SmsObserver started")
        } catch (e: Exception) {
            Log.e("SmsObserver", "Failed to register observer", e)
        }
    }
    
    fun stop() {
        try {
            context.contentResolver.unregisterContentObserver(this)
            Log.d("SmsObserver", "SmsObserver stopped")
        } catch (e: Exception) {
            Log.e("SmsObserver", "Failed to unregister observer", e)
        }
    }
}

package com.example.securesmsforwarder.sms

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.securesmsforwarder.core.domain.MessageQueueManager
import com.example.securesmsforwarder.storage.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object SmsHistoryScanner {

    fun syncHistory(context: Context, sinceTimestamp: Long, endTimestamp: Long) {
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "address", "body", "date")
        
        // Filter by date range
        val selection = "date >= ? AND date <= ?"
        val selectionArgs = arrayOf(sinceTimestamp.toString(), endTimestamp.toString())
        
        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, "date ASC")
        
        cursor?.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val addrIdx = c.getColumnIndex("address")
            val bodyIdx = c.getColumnIndex("body")
            val dateIdx = c.getColumnIndex("date")
            
            val db = AppDatabase.getDatabase(context)
            val queueManager = MessageQueueManager(db.messageDao())
            
            var count = 0
            while (c.moveToNext()) {
                val address = c.getString(addrIdx) ?: "Unknown"
                val body = c.getString(bodyIdx) ?: ""
                val date = c.getLong(dateIdx)
                
                val parsed = ParsedSms(
                    sender = address,
                    body = body,
                    timestamp = date,
                    subscriptionId = -1,
                    messageId = "hist_${c.getLong(idIdx)}"
                )
                
                CoroutineScope(Dispatchers.IO).launch {
                    queueManager.enqueueMessage(parsed)
                }
                count++
            }
            Log.d("SmsHistoryScanner", "Synced $count historical messages.")
        }
    }
}

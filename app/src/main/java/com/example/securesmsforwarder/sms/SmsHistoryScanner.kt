package com.example.securesmsforwarder.sms

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.securesmsforwarder.SecureSmsApplication
import com.example.securesmsforwarder.background.ForwardingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object SmsHistoryScanner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun syncHistory(context: Context, sinceTimestamp: Long, endTimestamp: Long) {
        val appContext = context.applicationContext ?: context
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("_id", "address", "body", "date")

        // Filter by date range
        val selection = "date >= ? AND date <= ?"
        val selectionArgs = arrayOf(sinceTimestamp.toString(), endTimestamp.toString())

        val cursor = appContext.contentResolver.query(uri, projection, selection, selectionArgs, "date ASC")

        cursor?.use { c ->
            val idIdx = c.getColumnIndex("_id")
            val addrIdx = c.getColumnIndex("address")
            val bodyIdx = c.getColumnIndex("body")
            val dateIdx = c.getColumnIndex("date")

            val queueManager = SecureSmsApplication.container(appContext).messageQueueManager

            val parsedList = mutableListOf<ParsedSms>()
            while (c.moveToNext()) {
                val address = c.getString(addrIdx) ?: "Unknown"
                val body = c.getString(bodyIdx) ?: ""
                val date = c.getLong(dateIdx)

                parsedList.add(
                    ParsedSms(
                        sender = address,
                        body = body,
                        timestamp = date,
                        subscriptionId = -1,
                        messageId = "hist_${c.getLong(idIdx)}"
                    )
                )
            }

            Log.d("SmsHistoryScanner", "Enqueuing ${parsedList.size} historical messages.")
            scope.launch {
                try {
                    for (parsed in parsedList) {
                        queueManager.enqueueMessage(parsed)
                    }
                    Log.d("SmsHistoryScanner", "Finished enqueuing historical messages. Triggering queue drain.")
                    val activeTransport = ForwardingService.activeTransportManager
                    if (activeTransport != null) {
                        activeTransport.triggerQueueDrain()
                    } else {
                        val serviceIntent = Intent(appContext, ForwardingService::class.java).apply {
                            action = ForwardingService.ACTION_DRAIN_QUEUE
                        }
                        ContextCompat.startForegroundService(appContext, serviceIntent)
                    }
                } catch (e: Exception) {
                    Log.e("SmsHistoryScanner", "Error enqueuing historical messages", e)
                }
            }
        }
    }
}

package com.example.securesmsforwarder.sms

import android.content.Intent
import android.provider.Telephony

data class ParsedSms(
    val sender: String,
    val timestamp: Long,
    val body: String,
    val subscriptionId: Int,
    val messageId: String? = null
)

object SmsParser {
    fun parseIntent(intent: Intent): List<ParsedSms> {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return emptyList()

        // Group by originating address to handle multipart messages
        val groupedMessages = messages.groupBy { it.originatingAddress ?: "Unknown" }
        
        return groupedMessages.map { (sender, parts) ->
            val fullBody = parts.joinToString("") { it.messageBody ?: "" }
            val timestamp = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
            val subId = intent.extras?.getInt("subscription", -1) ?: -1
            
            ParsedSms(sender, timestamp, fullBody, subId)
        }
    }
}

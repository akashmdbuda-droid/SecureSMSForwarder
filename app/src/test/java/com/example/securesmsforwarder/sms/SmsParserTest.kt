package com.example.securesmsforwarder.sms

import android.content.Intent
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// We need Robolectric to mock the Android Intent and Telephony framework classes
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmsParserTest {

    @Test
    fun testParseIntent_emptyIntent_returnsEmptyList() {
        val intent = Intent()
        val parsed = SmsParser.parseIntent(intent)
        assertEquals(0, parsed.size)
    }

    // Since Telephony.Sms.Intents.getMessagesFromIntent requires specific PDU binary data
    // which is complex to mock manually in Robolectric, a common approach is to test the
    // downstream grouping logic or rely on instrumentation tests for accurate PDU parsing.
    // However, we can mock the bundle if we want to simulate the raw data.
    
    // For V1, the focus is that an empty intent doesn't crash the app and returns an empty list,
    // which we just tested above.
}

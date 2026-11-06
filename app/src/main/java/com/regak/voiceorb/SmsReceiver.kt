package com.regak.voiceorb

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony

/**
 * Real-time SMS arrival, via Android's standard broadcast mechanism --
 * much simpler than the WhatsApp notification-listener approach since SMS
 * has a direct, official API (no notification-content scraping needed).
 * Multiple apps can receive this broadcast and send replies via
 * SmsManager without being the device's default SMS app; that's only
 * required for things like deleting messages, which this never does.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        if (messages.isEmpty()) return
        VoiceSessionService.debugLog.add("SMS received (${messages.size} part(s))")

        // A single SMS can arrive split into multiple PDU parts (long
        // messages) -- they share one originating address, so concatenate
        // bodies rather than treating each part as a separate message.
        val number = messages.first().originatingAddress ?: return
        val body = messages.joinToString("") { it.messageBody ?: "" }
        if (body.isBlank()) return

        val senderName = resolveContactName(context.contentResolver, number) ?: number
        VoiceSessionService.onSmsMessage(context, senderName, number, body)
    }

    private fun resolveContactName(resolver: ContentResolver, phoneNumber: String): String? {
        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber),
        )
        return runCatching {
            resolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
    }
}

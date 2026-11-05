package com.regak.voiceorb

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Reads WhatsApp message notifications via Android's public
 * NotificationListenerService API -- the same mechanism Android Auto,
 * Wear OS, and Bixby Routines use to read/reply to messages hands-free.
 * No root, no UI scraping, no WhatsApp API key: this is the Android-
 * sanctioned way to see another app's notification content, gated by a
 * one-time manual "Notification access" grant in system Settings (cannot
 * be auto-granted like a runtime permission).
 *
 * Captures each message's sender + text (via NotificationCompat's
 * MessagingStyle parser, which correctly unpacks grouped/batched
 * notifications into individual messages) plus the notification's inline
 * "Reply" action -- a RemoteInput-backed PendingIntent. Firing that intent
 * with reply text bundled in sends the reply exactly as WhatsApp's own
 * quick-reply UI would, with no WhatsApp UI ever opening.
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    companion object {
        private const val WHATSAPP_PACKAGE = "com.whatsapp"
        private const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"

        /** True once the user has granted Notification access to this app
         *  in system Settings -- there is no runtime permission dialog for
         *  this, so the UI must detect it and deep-link to Settings. */
        fun isEnabled(context: Context): Boolean {
            val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
            return context.packageName in enabledPackages
        }

        /** Keyed by sbn.key so a reply always targets the exact
         *  conversation it was captured from, not just "the last message
         *  from anyone." Holds just enough to fire the reply later --
         *  never anything persisted to disk. */
        data class PendingReply(
            val senderName: String,
            val messageText: String,
            val replyAction: Notification.Action,
            val remoteInputs: Array<RemoteInput>,
        )

        /** Most recent still-repliable WhatsApp conversations, newest
         *  last. VoiceSessionService reads/clears this; this class only
         *  appends to it. */
        val pendingReplies = linkedMapOf<String, PendingReply>()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != WHATSAPP_PACKAGE && sbn.packageName != WHATSAPP_BUSINESS_PACKAGE) return
        val notification = sbn.notification ?: return

        val replyAction = notification.actions?.firstOrNull { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
        val remoteInputs = replyAction?.remoteInputs
        if (replyAction == null || remoteInputs == null) {
            // No inline-reply action on this notification (e.g. it's a
            // media-only or summary notification) -- nothing we can act on.
            return
        }

        val style = NotificationCompat.MessagingStyle
            .extractMessagingStyleFromNotification(notification)
        val messages = style?.messages
        if (messages.isNullOrEmpty()) {
            // Fall back to the plain title/text extras if WhatsApp didn't
            // build this one as a MessagingStyle notification.
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            if (title.isBlank() || text.isBlank()) return
            synchronized(pendingReplies) {
                pendingReplies[sbn.key] = PendingReply(title, text, replyAction, remoteInputs)
            }
            VoiceSessionService.onWhatsAppMessage(applicationContext, title, text)
            return
        }

        // Use the latest message in the batch; MessagingStyle's
        // conversationTitle is the group/contact name, falling back to
        // the sender name on the message itself for 1:1 chats.
        val latest = messages.last()
        val senderName = style.conversationTitle?.toString()
            ?: latest.person?.name?.toString()
            ?: "WhatsApp"
        val messageText = latest.text?.toString().orEmpty()
        if (messageText.isBlank()) return

        synchronized(pendingReplies) {
            pendingReplies[sbn.key] = PendingReply(senderName, messageText, replyAction, remoteInputs)
            // Keep this bounded -- only the few most recent conversations
            // are realistically still repliable/relevant.
            while (pendingReplies.size > 10) {
                pendingReplies.remove(pendingReplies.keys.first())
            }
        }
        VoiceSessionService.onWhatsAppMessage(applicationContext, senderName, messageText)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(pendingReplies) { pendingReplies.remove(sbn.key) }
    }
}

package com.regak.voiceorb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Foreground service (type "microphone") that owns the GeminiLiveClient +
 * AudioEngine session. A Service's lifecycle is independent of the
 * Activity/ViewModel, so the session survives the user backgrounding the
 * app or switching to another app -- a plain ViewModel cannot do this,
 * since Android throttles/kills background mic access and CPU for apps
 * with no foreground service.
 *
 * Command pattern via Intent actions (no binding needed): the Activity
 * sends START/STOP/SET_MUTED intents, and reads session state directly
 * from the companion object's Compose state (safe to mutate from any
 * thread -- Compose's snapshot system handles the cross-thread sync).
 */

class VoiceSessionService : Service() {

    companion object {
        private const val TAG = "VoiceSessionService"
        private const val CHANNEL_ID = "voice_session"
        private const val VIDEO_READY_CHANNEL_ID = "video_ready"
        private const val NOTIFICATION_ID = 1001
        private const val VIDEO_READY_NOTIFICATION_ID = 1002

        const val ACTION_START = "com.regak.voiceorb.action.START"
        const val ACTION_STOP = "com.regak.voiceorb.action.STOP"
        const val ACTION_SWITCH_SESSION = "com.regak.voiceorb.action.SWITCH_SESSION"
        const val ACTION_SET_MUTED = "com.regak.voiceorb.action.SET_MUTED"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_MUTED = "muted"
        const val EXTRA_SESSION_ID = "session_id"

        // Shared Compose state -- the Activity reads these directly, the
        // Service writes to them. This replaces the old ViewModel-owned
        // state, since a ViewModel dies with its Activity/Fragment but this
        // needs to outlive that.
        val orbState = mutableStateOf(OrbState.IDLE)
        val transcript = mutableStateListOf<TranscriptLine>()
        val debugLog = mutableStateListOf<String>()
        val lastError = mutableStateOf<String?>(null)

        // Driving mode (WhatsApp hands-free read + reply): see
        // onWhatsAppMessage() below for the full flow.
        val drivingMode = mutableStateOf(false)
        // New-message notices queued here until a live Gemini session with
        // setupComplete acknowledged exists to actually speak them --
        // companion-level (not tied to one service instance) since a
        // WhatsApp notification can arrive before the service has even
        // been created yet.
        private val pendingAnnouncements = mutableListOf<String>()
        // Set by the running instance once its session's setupComplete
        // arrives; cleared on stop. Companion-level by the same pattern as
        // orbState/transcript above.
        private var liveClient: GeminiLiveClient? = null

        /** Called by WhatsAppNotificationListener for every new WhatsApp
         *  message while driving mode is on. Announces it into the live
         *  voice conversation (auto-starting a session with the app's own
         *  Gemini key if none is running yet) so the user hears it read
         *  aloud and can simply reply by talking -- the model will call
         *  reply_whatsapp with the drafted text. */
        fun onWhatsAppMessage(context: android.content.Context, senderName: String, messageText: String) {
            if (!drivingMode.value) return
            val notice = "[SYSTEM NOTICE -- not something the user said. A new " +
                "WhatsApp message just arrived while the user is driving. Tell " +
                "the user about it naturally and briefly, then ask if they want " +
                "to reply.] New WhatsApp message from $senderName: \"$messageText\""
            synchronized(pendingAnnouncements) { pendingAnnouncements.add(notice) }

            val startIntent = Intent(context, VoiceSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_API_KEY, BuildConfig.GEMINI_API_KEY)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, startIntent)
            flushPendingAnnouncements()
        }

        /** Sends any queued WhatsApp announcements once the live session is
         *  actually ready to speak (setupComplete acknowledged) -- called
         *  both from onWhatsAppMessage() and from the SetupComplete handler
         *  below, since whichever happens second is when it's safe. */
        fun flushPendingAnnouncements() {
            val c = liveClient ?: return
            if (!c.setupAcked.get()) return
            synchronized(pendingAnnouncements) {
                pendingAnnouncements.forEach { c.sendUserTextTurn(it) }
                pendingAnnouncements.clear()
            }
        }

        /** Finds the most recent (optionally sender-matching) pending
         *  WhatsApp conversation and fires its real reply RemoteInput
         *  action -- the same mechanism Android Auto/Wear use, so this
         *  sends exactly as WhatsApp's own quick-reply would, no UI
         *  involved. */
        fun sendWhatsAppReply(context: android.content.Context, message: String, recipientHint: String): org.json.JSONObject {
            if (message.isBlank()) {
                return org.json.JSONObject().apply { put("error", "message was missing or blank") }
            }
            val replies = WhatsAppNotificationListener.pendingReplies
            val entry = synchronized(replies) {
                if (recipientHint.isNotBlank()) {
                    replies.entries.lastOrNull { it.value.senderName.contains(recipientHint, ignoreCase = true) }
                } else {
                    replies.entries.lastOrNull()
                }
            } ?: return org.json.JSONObject().apply { put("error", "No pending WhatsApp conversation to reply to") }

            return try {
                val resultsBundle = android.os.Bundle()
                entry.value.remoteInputs.forEach { ri -> resultsBundle.putCharSequence(ri.resultKey, message) }
                val fillInIntent = Intent()
                android.app.RemoteInput.addResultsToIntent(entry.value.remoteInputs, fillInIntent, resultsBundle)
                entry.value.replyAction.actionIntent.send(context, 0, fillInIntent)
                synchronized(replies) { replies.remove(entry.key) }
                org.json.JSONObject().apply {
                    put("result", "sent")
                    put("to", entry.value.senderName)
                }
            } catch (t: Throwable) {
                org.json.JSONObject().apply { put("error", "Failed to send WhatsApp reply: ${t.message}") }
            }
        }

        // --- SMS: real-time announce + reply, and historical queries ---

        /** Most recent still-repliable SMS senders, keyed by phone number,
         *  newest last. Mirrors WhatsAppNotificationListener.pendingReplies
         *  but SMS replies don't need a captured PendingIntent -- just the
         *  number, since SmsManager.sendTextMessage() sends directly. */
        data class PendingSmsSender(val senderName: String, val phoneNumber: String)
        private val pendingSmsSenders = linkedMapOf<String, PendingSmsSender>()

        /** Called by SmsReceiver for every new SMS while driving mode is
         *  on. Same announce-into-the-live-session flow as WhatsApp. */
        fun onSmsMessage(context: android.content.Context, senderName: String, phoneNumber: String, messageText: String) {
            if (!drivingMode.value) return
            synchronized(pendingSmsSenders) {
                pendingSmsSenders[phoneNumber] = PendingSmsSender(senderName, phoneNumber)
                while (pendingSmsSenders.size > 10) {
                    pendingSmsSenders.remove(pendingSmsSenders.keys.first())
                }
            }
            val notice = "[SYSTEM NOTICE -- not something the user said. A new SMS " +
                "just arrived while the user is driving. Tell the user about it " +
                "naturally and briefly, then ask if they want to reply.] New SMS " +
                "from $senderName: \"$messageText\""
            synchronized(pendingAnnouncements) { pendingAnnouncements.add(notice) }

            val startIntent = Intent(context, VoiceSessionService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_API_KEY, BuildConfig.GEMINI_API_KEY)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, startIntent)
            flushPendingAnnouncements()
        }

        /** Sends a real SMS reply via SmsManager -- a direct send call,
         *  simpler and more robust than WhatsApp's RemoteInput dance since
         *  nothing here can go stale. Matches the sender by name/number
         *  hint if given, else the most recent one. */
        fun sendSmsReply(context: android.content.Context, message: String, recipientHint: String): org.json.JSONObject {
            if (message.isBlank()) {
                return org.json.JSONObject().apply { put("error", "message was missing or blank") }
            }
            val entry = synchronized(pendingSmsSenders) {
                if (recipientHint.isNotBlank()) {
                    pendingSmsSenders.entries.lastOrNull {
                        it.value.senderName.contains(recipientHint, ignoreCase = true) ||
                            it.value.phoneNumber.contains(recipientHint)
                    }
                } else {
                    pendingSmsSenders.entries.lastOrNull()
                }
            } ?: return org.json.JSONObject().apply { put("error", "No pending SMS conversation to reply to") }

            return try {
                val smsManager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    context.getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                }
                smsManager.sendTextMessage(entry.value.phoneNumber, null, message, null, null)
                synchronized(pendingSmsSenders) { pendingSmsSenders.remove(entry.key) }
                org.json.JSONObject().apply {
                    put("result", "sent")
                    put("to", entry.value.senderName)
                }
            } catch (t: Throwable) {
                org.json.JSONObject().apply { put("error", "Failed to send SMS reply: ${t.message}") }
            }
        }

        /** Sends a brand-new SMS to a contact name or raw phone number --
         *  unlike sendWhatsAppReply/sendSmsReply, this doesn't need a prior
         *  incoming message to target; the user names who to text. */
        fun sendNewSms(context: android.content.Context, recipient: String, message: String): org.json.JSONObject {
            if (message.isBlank()) {
                return org.json.JSONObject().apply { put("error", "message was missing or blank") }
            }
            if (recipient.isBlank()) {
                return org.json.JSONObject().apply { put("error", "recipient was missing or blank") }
            }
            // A phone-number-looking recipient is used directly; otherwise
            // treat it as a contact name to resolve.
            val looksLikeNumber = recipient.any { it.isDigit() } && recipient.count { it.isDigit() } >= 6
            val phoneNumber = if (looksLikeNumber) {
                recipient
            } else {
                resolveContactNumbers(context, recipient).firstOrNull()
                    ?: return org.json.JSONObject().apply { put("error", "No contact found matching '$recipient'") }
            }

            return try {
                val smsManager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                    context.getSystemService(android.telephony.SmsManager::class.java)
                } else {
                    @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
                }
                smsManager.sendTextMessage(phoneNumber, null, message, null, null)
                org.json.JSONObject().apply {
                    put("result", "sent")
                    put("to", recipient)
                }
            } catch (t: Throwable) {
                org.json.JSONObject().apply { put("error", "Failed to send SMS: ${t.message}") }
            }
        }

        /** Queries the real on-device SMS history (content://sms), not
         *  just new arrivals -- for "read my messages from Mom today" /
         *  "any unread SMS?" style requests. contactHint is resolved
         *  against Contacts to the matching phone number(s) when given;
         *  timeRange is one of "today"/"yesterday"/"this_week"/"all". */
        fun querySms(
            context: android.content.Context,
            contactHint: String,
            timeRange: String,
            unreadOnly: Boolean,
        ): org.json.JSONObject {
            val selectionParts = mutableListOf<String>()
            val selectionArgs = mutableListOf<String>()

            if (contactHint.isNotBlank()) {
                val numbers = resolveContactNumbers(context, contactHint)
                if (numbers.isEmpty()) {
                    return org.json.JSONObject().apply {
                        put("error", "No contact found matching '$contactHint'")
                    }
                }
                selectionParts.add("(${numbers.joinToString(" OR ") { "${android.provider.Telephony.Sms.ADDRESS} = ?" }})")
                selectionArgs.addAll(numbers)
            }

            val dayMs = 24L * 60 * 60 * 1000
            val cal = java.util.Calendar.getInstance()
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            val todayStart = cal.timeInMillis
            when (timeRange.lowercase()) {
                "today" -> {
                    selectionParts.add("${android.provider.Telephony.Sms.DATE} >= ?")
                    selectionArgs.add(todayStart.toString())
                }
                "yesterday" -> {
                    selectionParts.add("${android.provider.Telephony.Sms.DATE} >= ? AND ${android.provider.Telephony.Sms.DATE} < ?")
                    selectionArgs.add((todayStart - dayMs).toString())
                    selectionArgs.add(todayStart.toString())
                }
                "this_week" -> {
                    selectionParts.add("${android.provider.Telephony.Sms.DATE} >= ?")
                    selectionArgs.add((todayStart - 7 * dayMs).toString())
                }
                // "all" or anything else -- no date filter.
            }

            if (unreadOnly) {
                selectionParts.add("${android.provider.Telephony.Sms.READ} = 0")
                selectionParts.add("${android.provider.Telephony.Sms.TYPE} = ${android.provider.Telephony.Sms.MESSAGE_TYPE_INBOX}")
            }

            val selection = if (selectionParts.isEmpty()) null else selectionParts.joinToString(" AND ")
            val results = org.json.JSONArray()
            try {
                context.contentResolver.query(
                    android.provider.Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        android.provider.Telephony.Sms.ADDRESS,
                        android.provider.Telephony.Sms.BODY,
                        android.provider.Telephony.Sms.DATE,
                        android.provider.Telephony.Sms.READ,
                    ),
                    selection,
                    if (selectionArgs.isEmpty()) null else selectionArgs.toTypedArray(),
                    "${android.provider.Telephony.Sms.DATE} DESC LIMIT 20",
                )?.use { cursor ->
                    val addressIdx = cursor.getColumnIndex(android.provider.Telephony.Sms.ADDRESS)
                    val bodyIdx = cursor.getColumnIndex(android.provider.Telephony.Sms.BODY)
                    val dateIdx = cursor.getColumnIndex(android.provider.Telephony.Sms.DATE)
                    val readIdx = cursor.getColumnIndex(android.provider.Telephony.Sms.READ)
                    while (cursor.moveToNext()) {
                        val address = cursor.getString(addressIdx) ?: ""
                        results.put(org.json.JSONObject().apply {
                            put("from", resolveContactNameForNumber(context, address) ?: address)
                            put("text", cursor.getString(bodyIdx) ?: "")
                            put("date_epoch_ms", cursor.getLong(dateIdx))
                            put("unread", cursor.getInt(readIdx) == 0)
                        })
                    }
                }
            } catch (t: Throwable) {
                return org.json.JSONObject().apply { put("error", "Failed to query SMS: ${t.message}") }
            }

            return org.json.JSONObject().apply {
                put("count", results.length())
                put("messages", results)
            }
        }

        private fun resolveContactNumbers(context: android.content.Context, nameHint: String): List<String> {
            val numbers = mutableListOf<String>()
            runCatching {
                context.contentResolver.query(
                    android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ),
                    "${android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$nameHint%"),
                    null,
                )?.use { cursor ->
                    val numberIdx = cursor.getColumnIndex(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) numbers.add(cursor.getString(numberIdx))
                }
            }
            return numbers
        }

        private fun resolveContactNameForNumber(context: android.content.Context, phoneNumber: String): String? {
            val uri = android.net.Uri.withAppendedPath(
                android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                android.net.Uri.encode(phoneNumber),
            )
            return runCatching {
                context.contentResolver.query(
                    uri, arrayOf(android.provider.ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
        }
    }

    private var client: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null
    @Volatile private var muted: Boolean = false
    private var openTranscriptLine: TranscriptLine? = null
    private var sessionJob: Job? = null
    private var reconnectJob: Job? = null
    private var lastApiKey: String = ""
    private var reconnectAttempts: Int = 0
    // Latest resumable session handle (see GeminiLiveClient's
    // sessionResumption docs) -- carried into every new GeminiLiveClient
    // we create, whether from an auto-reconnect after a network drop or a
    // deliberate stop/start, so the model keeps full context instead of
    // "forgetting everything" the way a brand-new session would.
    private var lastResumeHandle: String? = null
    // Which saved session the LIVE transcript/connection actually belongs to
    // right now. Deliberately NOT read from SessionManager.getCurrentId()
    // when persisting -- that's a UI-owned pointer the drawer flips the
    // instant the user taps a different session, often before this
    // service has finished flushing the OLD session's final turn. Using
    // our own tracked id as the save target removes that race entirely.
    private var activeSessionId: String? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Load the currently-selected session (or create a new one if none exists).
        val session = SessionManager.getCurrent(applicationContext)
            ?: SessionManager.newSession(applicationContext)
        activeSessionId = session.id
        lastResumeHandle = session.resumeHandle
        if (transcript.isEmpty() && session.transcript.isNotEmpty()) {
            session.transcript.forEach { (isModel, text) ->
                transcript.add(TranscriptLine(isModel).apply { this.text = text })
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
                startForegroundNotification()
                startSession(apiKey)
            }
            ACTION_STOP -> {
                stopSession()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            // Switches to a different saved session (or a brand-new one) in a
            // SINGLE onStartCommand call. Deliberately not split into a
            // separate STOP intent followed by a separate START intent from
            // the caller: two separate startService()/startForegroundService()
            // calls race against each other (the old instance can call
            // stopSelf() right as the new start command arrives), which is
            // exactly what caused a real
            // ForegroundServiceDidNotStartInTimeException crash in testing.
            // Doing stop+reload+start synchronously inside one command avoids
            // that race entirely and guarantees startForeground() is (re)called
            // well within the 5s window.
            ACTION_SWITCH_SESSION -> {
                // Flush the OUTGOING session's live transcript to its own
                // disk record FIRST, explicitly by the id we know it
                // actually belongs to -- before touching anything else.
                // This is what was silently failing before: relying on
                // closeOpenTranscriptLine()'s use of the shared "current
                // session id" meant a switch away before a turn fully
                // closed (or any async race with the UI already flipping
                // the current-id pointer) left the outgoing session's
                // record stale/empty on disk, so switching back later
                // loaded nothing -- even though a full app restart (which
                // re-reads via a different path) could still show it if
                // an earlier turn happened to save correctly.
                val outgoingId = activeSessionId
                if (outgoingId != null) {
                    SessionManager.loadAll(applicationContext).firstOrNull { it.id == outgoingId }?.let { outgoing ->
                        SessionManager.updateSession(
                            applicationContext,
                            outgoing.copy(transcript = transcript.map { it.isModel to it.text }),
                        )
                    }
                }
                stopSession()
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                val session = if (sessionId != null) {
                    SessionManager.loadAll(applicationContext).firstOrNull { it.id == sessionId }
                } else {
                    null
                }
                activeSessionId = session?.id ?: sessionId
                transcript.clear()
                session?.transcript?.forEach { (isModel, text) ->
                    transcript.add(TranscriptLine(isModel).apply { this.text = text })
                }
                lastResumeHandle = session?.resumeHandle
                val apiKey = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
                startForegroundNotification()
                startSession(apiKey)
            }
            ACTION_SET_MUTED -> {
                muted = intent.getBooleanExtra(EXTRA_MUTED, false)
                log(if (muted) "mic muted by user" else "mic unmuted by user")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun log(msg: String) {
        debugLog.add(msg)
        while (debugLog.size > 200) debugLog.removeAt(0)
    }

    private fun failWith(label: String, t: Throwable) {
        val msg = "$label: ${t.javaClass.simpleName}: ${t.message}"
        log("ERROR: $msg")
        lastError.value = msg
        orbState.value = OrbState.ERROR
        updateNotification("Error")
    }

    private fun appendTranscriptDelta(text: String, isModel: Boolean) {
        val current = openTranscriptLine
        if (current != null && current.isModel == isModel) {
            current.text += text
        } else {
            val line = TranscriptLine(isModel)
            line.text = text
            transcript.add(line)
            openTranscriptLine = line
            while (transcript.size > 200) transcript.removeAt(0)
        }
    }

    private fun closeOpenTranscriptLine() {
        openTranscriptLine = null
        // Persist to the session this live transcript actually belongs to
        // (NOT SessionManager.getCurrentId() -- see activeSessionId's doc).
        activeSessionId?.let { id ->
            SessionManager.loadAll(applicationContext).firstOrNull { it.id == id }?.let { session ->
                val updated = session.copy(transcript = transcript.map { it.isModel to it.text })
                SessionManager.updateSession(applicationContext, updated)
            }
        }
    }

    /** Dispatches a parsed tool call to a real native action and reports the
     *  result back to the model so it can confirm verbally ("Playing it
     *  now"). Unknown function names get an error response rather than being
     *  silently ignored, so the model doesn't hang waiting for a reply it
     *  will never get. play_youtube does a real network search, so it runs
     *  on a background coroutine rather than blocking the event-collection
     *  loop (which runs on the main thread). */
    private fun dispatchToolCall(client: GeminiLiveClient, call: GeminiLiveClient.Event.ToolCall) {
        when (call.name) {
            "play_youtube" -> {
                serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val result = handlePlayYoutube(call.args)
                    if (call.id.isNotEmpty()) client.sendToolResponse(call.id, result)
                }
            }
            "reply_whatsapp" -> {
                val message = call.args.optString("message")
                val recipientHint = call.args.optString("recipient_hint")
                val result = sendWhatsAppReply(applicationContext, message, recipientHint)
                if (call.id.isNotEmpty()) client.sendToolResponse(call.id, result)
            }
            "reply_sms" -> {
                val message = call.args.optString("message")
                val recipientHint = call.args.optString("recipient_hint")
                val result = sendSmsReply(applicationContext, message, recipientHint)
                if (call.id.isNotEmpty()) client.sendToolResponse(call.id, result)
            }
            "send_sms" -> {
                val recipient = call.args.optString("recipient")
                val message = call.args.optString("message")
                val result = sendNewSms(applicationContext, recipient, message)
                if (call.id.isNotEmpty()) client.sendToolResponse(call.id, result)
            }
            "query_sms" -> {
                serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val contactHint = call.args.optString("contact_hint")
                    val timeRange = call.args.optString("time_range", "all")
                    val unreadOnly = call.args.optBoolean("unread_only", false)
                    val result = querySms(applicationContext, contactHint, timeRange, unreadOnly)
                    if (call.id.isNotEmpty()) client.sendToolResponse(call.id, result)
                }
            }
            else -> respond(client, call, "error", "Unknown function: ${call.name}")
        }
    }

    private fun respond(client: GeminiLiveClient, call: GeminiLiveClient.Event.ToolCall, key: String, value: String) {
        if (call.id.isEmpty()) return
        client.sendToolResponse(call.id, org.json.JSONObject().apply { put(key, value) })
    }

    /** Searches YouTube for [args]'s query via the YouTube Data API v3 (a
     *  real search, not a guess) to find the actual top video, then relays
     *  through MainActivity to open it in the real YouTube app (Option A).
     *  Launching your OWN app's activity from a service is always allowed;
     *  MainActivity then performs the actual YouTube launch from an active
     *  foreground context, where it is reliably exempt from Android's
     *  background-activity-launch restrictions. A tappable fallback
     *  notification is posted every time too, since Android gives no way
     *  to detect whether the direct launch silently got BAL-blocked (which
     *  can still happen if the user is already inside another app, like
     *  YouTube itself, when this fires) -- tapping a notification is
     *  always a direct user action, so it's a guaranteed way through. */
    private suspend fun handlePlayYoutube(args: org.json.JSONObject): org.json.JSONObject {
        val query = args.optString("query")
        if (query.isBlank()) {
            return org.json.JSONObject().apply { put("error", "query was missing or blank") }
        }
        val apiKey = BuildConfig.YOUTUBE_API_KEY
        if (apiKey.isBlank()) {
            return org.json.JSONObject().apply { put("error", "No YouTube API key configured") }
        }

        val searchResult = runCatching { searchYoutube(query, apiKey) }.getOrElse { t ->
            log("YouTube search failed: ${t.javaClass.simpleName}: ${t.message}")
            return org.json.JSONObject().apply { put("error", "YouTube search failed: ${t.message}") }
        }
        if (searchResult == null) {
            return org.json.JSONObject().apply { put("error", "No YouTube results found for '$query'") }
        }
        val (videoId, title) = searchResult
        val videoUrl = "https://www.youtube.com/watch?v=$videoId"

        return try {
            val relayIntent = Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_YOUTUBE
                putExtra(MainActivity.EXTRA_VIDEO_URL, videoUrl)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(relayIntent)
            postVideoReadyNotification(videoUrl, title)

            log("playing real search result '$title' ($videoId) for query '$query'")
            org.json.JSONObject().apply {
                put("result", "opened")
                put("video_url", videoUrl)
                put("title", title)
                put(
                    "note",
                    "A 'Tap to play' notification was also posted as a backup -- " +
                        "mention it briefly in case the app couldn't switch to " +
                        "YouTube automatically (e.g. the user is currently inside " +
                        "another app).",
                )
            }
        } catch (t: Throwable) {
            log("play_youtube failed: ${t.javaClass.simpleName}: ${t.message}")
            org.json.JSONObject().apply { put("error", "Failed to open video: ${t.message}") }
        }
    }

    private val httpClient = okhttp3.OkHttpClient()

    /** Real YouTube Data API v3 search -- returns (videoId, title) of the
     *  top result, or null if nothing matched. Runs on Dispatchers.IO
     *  (blocking call), never the main thread. */
    private fun searchYoutube(query: String, key: String): Pair<String, String>? {
        val url = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("q", query)
            .addQueryParameter("type", "video")
            .addQueryParameter("maxResults", "1")
            .addQueryParameter("key", key)
            .build()
        val request = okhttp3.Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("YouTube API returned HTTP ${response.code}: ${response.body?.string()?.take(300)}")
            }
            val body = response.body?.string() ?: return null
            val json = org.json.JSONObject(body)
            val items = json.optJSONArray("items") ?: return null
            if (items.length() == 0) return null
            val first = items.getJSONObject(0)
            val videoId = first.getJSONObject("id").optString("videoId")
            val title = first.getJSONObject("snippet").optString("title")
            if (videoId.isBlank()) return null
            return videoId to title
        }
    }

    private fun startSession(apiKeyValue: String) {
        if (client != null) return // already running
        if (apiKeyValue.isBlank()) {
            lastError.value = "No Gemini API key configured (set GEMINI_API_KEY in local.properties)."
            orbState.value = OrbState.ERROR
            return
        }
        lastApiKey = apiKeyValue
        orbState.value = OrbState.CONNECTING
        lastError.value = null
        updateNotification("Connecting...")

        val engine = AudioEngine(
            onChunkCaptured = { b64 -> if (!muted) client?.sendAudioChunk(b64) },
            onLog = { log("audio: $it") },
            onError = {
                log("AUDIO ERROR: $it")
                lastError.value = it
                orbState.value = OrbState.ERROR
                updateNotification("Error")
            },
        )
        audioEngine = engine

        val gemini = GeminiLiveClient(apiKey = apiKeyValue, resumeHandle = lastResumeHandle)
        client = gemini

        val exceptionHandler = CoroutineExceptionHandler { _, t ->
            failWith("session coroutine crashed", t)
        }

        sessionJob = serviceScope.launch(exceptionHandler) {
            gemini.events.collect { event ->
                try {
                    when (event) {
                        is GeminiLiveClient.Event.Connected -> log("ws connected")
                        is GeminiLiveClient.Event.SetupComplete -> {
                            log("setupComplete received -- starting mic capture")
                            reconnectAttempts = 0 // connection is healthy again
                            liveClient = gemini
                            flushPendingAnnouncements()
                            orbState.value = OrbState.LISTENING
                            updateNotification("Listening...")
                            engine.startCapture()
                        }
                        is GeminiLiveClient.Event.AudioChunk -> {
                            orbState.value = OrbState.SPEAKING
                            updateNotification("Speaking...")
                            engine.playChunk(event.pcm16Base64)
                        }
                        is GeminiLiveClient.Event.TranscriptDelta -> {
                            appendTranscriptDelta(event.text, event.isModel)
                        }
                        is GeminiLiveClient.Event.Interrupted -> {
                            log("barge-in: interrupted by user, flushing playback")
                            engine.flushPlayback()
                            closeOpenTranscriptLine()
                            orbState.value = OrbState.LISTENING
                            updateNotification("Listening...")
                        }
                        is GeminiLiveClient.Event.TurnComplete -> {
                            closeOpenTranscriptLine()
                            orbState.value = OrbState.LISTENING
                            updateNotification("Listening...")
                        }
                        is GeminiLiveClient.Event.ToolCall -> {
                            log("toolCall: name=${event.name} args=${event.args}")
                            dispatchToolCall(gemini, event)
                        }
                        is GeminiLiveClient.Event.RawServerMessage -> {
                            log("recv: ${event.text.take(500)}")
                        }
                        is GeminiLiveClient.Event.Failure -> {
                            log("FAILURE: ${event.message}")
                            lastError.value = event.message
                            orbState.value = OrbState.ERROR
                            updateNotification("Error")
                            scheduleReconnect(event.message)
                        }
                        is GeminiLiveClient.Event.Closed -> {
                            log("closed: ${event.reason}")
                            orbState.value = OrbState.IDLE
                        }
                        is GeminiLiveClient.Event.SessionResumptionUpdate -> {
                            if (event.resumable) {
                                lastResumeHandle = event.handle
                                // Persist to the session this connection
                                // actually belongs to (see activeSessionId).
                                activeSessionId?.let { id ->
                                    SessionManager.loadAll(applicationContext).firstOrNull { it.id == id }?.let { session ->
                                        val updated = session.copy(resumeHandle = event.handle)
                                        SessionManager.updateSession(applicationContext, updated)
                                    }
                                }
                                log("got resumable session handle (saved to current session)")
                            }
                        }
                    }
                } catch (t: CancellationException) {
                    throw t // normal cooperative cancellation -- not an error
                } catch (t: Throwable) {
                    failWith("event handling (${event.javaClass.simpleName})", t)
                }
            }
        }

        try {
            gemini.connect()
        } catch (t: Throwable) {
            failWith("gemini.connect()", t)
        }
    }

    private fun stopSession() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        sessionJob?.cancel()
        sessionJob = null
        runCatching { audioEngine?.release() }
        runCatching { client?.close() }
        audioEngine = null
        client = null
        liveClient = null
        openTranscriptLine = null
        lastError.value = null
        orbState.value = OrbState.IDLE
    }

    /** The Live WebSocket can drop on its own (e.g. a flaky mobile network
     *  missing a ping/pong within the keep-alive window) -- previously this
     *  just left the user stuck on a red error screen needing a manual
     *  stop+restart. Now it retries automatically a few times with backoff,
     *  reusing the same API key, and gives up (leaving the error visible)
     *  only after repeated failures, so a bad key or persistent outage
     *  doesn't loop forever silently. */
    private fun scheduleReconnect(reason: String) {
        reconnectAttempts++
        if (reconnectAttempts > 3) {
            log("giving up auto-reconnect after $reconnectAttempts attempts: $reason")
            return
        }
        // A stale/expired session-resumption handle gets rejected by the
        // server with "session not found" (WS close code 1008) before
        // setup ever completes. Retrying with the SAME bad handle would
        // just repeat the identical rejection three more times and still
        // end up stuck in ERROR. Self-heal by dropping the handle and
        // starting a brand-new (non-resumed) session instead -- losing
        // mid-air context recovery is much better than never connecting.
        if (reason.contains("session not found", ignoreCase = true) ||
            reason.contains("closed before setup completed", ignoreCase = true)
        ) {
            log("clearing possibly-stale session-resumption handle before retrying")
            lastResumeHandle = null
            activeSessionId?.let { id ->
                SessionManager.loadAll(applicationContext).firstOrNull { it.id == id }?.let { session ->
                    SessionManager.updateSession(applicationContext, session.copy(resumeHandle = null))
                }
            }
        }
        val delayMs = when (reconnectAttempts) {
            1 -> 2_000L
            2 -> 5_000L
            else -> 10_000L
        }
        log("scheduling reconnect attempt $reconnectAttempts in ${delayMs}ms after: $reason")
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            kotlinx.coroutines.delay(delayMs)
            sessionJob?.cancel()
            sessionJob = null
            runCatching { audioEngine?.release() }
            runCatching { client?.close() }
            audioEngine = null
            client = null
            updateNotification("Reconnecting...")
            orbState.value = OrbState.CONNECTING
            startSession(lastApiKey)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Voice Orb session",
                    NotificationManager.IMPORTANCE_LOW, // low = no sound, still visible
                )
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    VIDEO_READY_CHANNEL_ID,
                    "Video ready to play",
                    // HIGH so it shows as a heads-up banner even while
                    // another app (like YouTube itself) has focus -- this is
                    // the fallback for when Android's background-activity-
                    // launch restriction blocks the app from auto-opening
                    // YouTube directly.
                    NotificationManager.IMPORTANCE_HIGH,
                )
            )
        }
    }

    /** Guaranteed fallback for opening a video: a tappable, high-priority
     *  notification the user can tap to actually open it when the direct
     *  launch was silently blocked by Android's background-activity-launch
     *  restriction (e.g. because YouTube itself currently has focus).
     *  Tapping a notification is always counted as a direct user action, so
     *  this is never subject to that restriction. */
    private fun postVideoReadyNotification(videoUrl: String, title: String) {
        val relayIntent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_YOUTUBE
            putExtra(MainActivity.EXTRA_VIDEO_URL, videoUrl)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, videoUrl.hashCode(), relayIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, VIDEO_READY_CHANNEL_ID)
            .setContentTitle("Tap to play")
            .setContentText(title.ifBlank { "Video ready" })
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(VIDEO_READY_NOTIFICATION_ID, notification) }
    }

    private fun buildNotification(statusText: String): Notification {
        val stopIntent = Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Orb")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setContentIntent(openAppPendingIntent)
            .addAction(0, "End conversation", stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification("Connecting...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, buildNotification(statusText)) }
    }
}

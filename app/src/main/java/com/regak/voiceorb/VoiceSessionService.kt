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

// Top-level (not nested in the companion object) so these are directly
// referenceable as VoiceSessionService.YoutubeVideo / .PlayerCommand from
// other files -- types declared inside a companion object block require
// VoiceSessionService.Companion.X instead, which is easy to get wrong.
data class YoutubeVideo(val videoId: String, val title: String)
sealed class PlayerCommand {
    object Play : PlayerCommand()
    object Pause : PlayerCommand()
    object Close : PlayerCommand()
    data class Seek(val deltaSeconds: Int) : PlayerCommand()
}

class VoiceSessionService : Service() {

    companion object {
        private const val TAG = "VoiceSessionService"
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.regak.voiceorb.action.START"
        const val ACTION_STOP = "com.regak.voiceorb.action.STOP"
        const val ACTION_SET_MUTED = "com.regak.voiceorb.action.SET_MUTED"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_MUTED = "muted"

        // Shared Compose state -- the Activity reads these directly, the
        // Service writes to them. This replaces the old ViewModel-owned
        // state, since a ViewModel dies with its Activity/Fragment but this
        // needs to outlive that.
        val orbState = mutableStateOf(OrbState.IDLE)
        val transcript = mutableStateListOf<TranscriptLine>()
        val debugLog = mutableStateListOf<String>()
        val lastError = mutableStateOf<String?>(null)

        // In-app YouTube player state (Option C: embedded WebView + IFrame
        // API, replacing the external-app-launch approach entirely -- since
        // the player now lives inside our OWN UI, there is no cross-app
        // screen switch needed at all, so Android's background-activity-
        // launch restriction simply doesn't apply anymore).
        val currentVideo = mutableStateOf<YoutubeVideo?>(null)
        // One-shot command for the embedded player Composable to consume
        // and then clear back to null (so re-sending the same command
        // later still triggers a state change).
        val playerCommand = mutableStateOf<PlayerCommand?>(null)
        // Set by the embedded player's JS bridge when YouTube itself
        // reports an error (most commonly: this specific video's owner
        // disabled embedding). The UI shows a friendly fallback with a
        // button to open the real YouTube app instead, since that per-
        // video restriction is YouTube's own policy and not something any
        // app can legitimately bypass.
        val playerError = mutableStateOf<String?>(null)
        // Remaining fallback candidates for the current query, used by
        // onEmbeddedPlayerError() below.
        val videoQueue = mutableStateOf<List<YoutubeVideo>>(emptyList())

        /** Called by the embedded player's JS error bridge. Tries the next
         *  search candidate automatically (YouTube's "embeddable" API flag
         *  is not always accurate, e.g. for live/premiere streams) instead
         *  of dead-ending on a single broken video; only shows the
         *  "Open in YouTube app" fallback once every candidate has failed. */
        fun onEmbeddedPlayerError(message: String) {
            val queue = videoQueue.value
            if (queue.isNotEmpty()) {
                currentVideo.value = queue.first()
                videoQueue.value = queue.drop(1)
                playerError.value = null
                playerCommand.value = PlayerCommand.Play
            } else {
                playerError.value = message
            }
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
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
            "pause_youtube" -> {
                playerCommand.value = PlayerCommand.Pause
                respond(client, call, "result", "paused")
            }
            "resume_youtube" -> {
                playerCommand.value = PlayerCommand.Play
                respond(client, call, "result", "resumed")
            }
            "close_youtube" -> {
                playerCommand.value = PlayerCommand.Close
                currentVideo.value = null
                respond(client, call, "result", "closed")
            }
            "forward_youtube" -> {
                val seconds = call.args.optInt("seconds", 10).coerceIn(1, 600)
                playerCommand.value = PlayerCommand.Seek(seconds)
                respond(client, call, "result", "forwarded $seconds seconds")
            }
            "rewind_youtube" -> {
                val seconds = call.args.optInt("seconds", 10).coerceIn(1, 600)
                playerCommand.value = PlayerCommand.Seek(-seconds)
                respond(client, call, "result", "rewound $seconds seconds")
            }
            else -> respond(client, call, "error", "Unknown function: ${call.name}")
        }
    }

    private fun respond(client: GeminiLiveClient, call: GeminiLiveClient.Event.ToolCall, key: String, value: String) {
        if (call.id.isEmpty()) return
        client.sendToolResponse(call.id, org.json.JSONObject().apply { put(key, value) })
    }

    /** Searches YouTube for [args]'s query via the YouTube Data API v3 (a
     *  real search, not a guess) to find the actual top video, then loads it
     *  into the app's OWN embedded player (Option C: WebView + YouTube
     *  IFrame API) instead of launching the external YouTube app. Because
     *  the player lives inside our own UI, there is no cross-app screen
     *  switch involved at all -- this sidesteps Android's background-
     *  activity-launch restriction entirely, and gives real playback
     *  control (pause/resume/close) that an external app hand-off never
     *  could. */
    private suspend fun handlePlayYoutube(args: org.json.JSONObject): org.json.JSONObject {
        val query = args.optString("query")
        if (query.isBlank()) {
            return org.json.JSONObject().apply { put("error", "query was missing or blank") }
        }
        val apiKey = BuildConfig.YOUTUBE_API_KEY
        if (apiKey.isBlank()) {
            return org.json.JSONObject().apply { put("error", "No YouTube API key configured") }
        }

        val searchResults = runCatching { searchYoutube(query, apiKey) }.getOrElse { t ->
            log("YouTube search failed: ${t.javaClass.simpleName}: ${t.message}")
            return org.json.JSONObject().apply { put("error", "YouTube search failed: ${t.message}") }
        }
        if (searchResults.isEmpty()) {
            return org.json.JSONObject().apply { put("error", "No YouTube results found for '$query'") }
        }
        val (videoId, title) = searchResults.first()

        playerError.value = null
        currentVideo.value = YoutubeVideo(videoId, title)
        // Keep the rest of the matches as fallback candidates: YouTube's
        // Data API "embeddable" flag can be wrong for some content (e.g.
        // live/premiere streams), so if the embedded player still reports
        // an error at runtime, onEmbeddedPlayerError() below tries the
        // next one automatically instead of dead-ending on one video.
        videoQueue.value = searchResults.drop(1).map { (id, t2) -> YoutubeVideo(id, t2) }
        playerCommand.value = PlayerCommand.Play
        log("loaded into embedded player: '$title' ($videoId) for query '$query'")

        return org.json.JSONObject().apply {
            put("result", "playing")
            put("title", title)
        }
    }

    private val httpClient = okhttp3.OkHttpClient()

    /** Searches YouTube for [query] via the Data API v3, then filters to
     *  only videos the owner has allowed to be embedded elsewhere (the
     *  "status.embeddable" field) -- picking a non-embeddable video would
     *  just show YouTube's own "this video is unavailable" error inside
     *  our iframe player, since that per-video restriction is enforced by
     *  YouTube itself and cannot legitimately be bypassed. Returns the
     *  first embeddable match, or null if none of the top candidates allow
     *  embedding. Runs on Dispatchers.IO (blocking calls), never the main
     *  thread. */
    private fun searchYoutube(query: String, apiKey: String): List<Pair<String, String>> {
        val searchUrl = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("q", query)
            .addQueryParameter("type", "video")
            .addQueryParameter("maxResults", "5")
            .addQueryParameter("key", apiKey)
            .build()
        val candidates = linkedMapOf<String, String>() // videoId -> title, in result order
        okhttp3.Request.Builder().url(searchUrl).build().let { request ->
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException("YouTube search API returned HTTP ${response.code}: ${response.body?.string()?.take(300)}")
                }
                val items = org.json.JSONObject(response.body?.string() ?: return emptyList()).optJSONArray("items") ?: return emptyList()
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    val videoId = item.getJSONObject("id").optString("videoId")
                    val title = item.getJSONObject("snippet").optString("title")
                    if (videoId.isNotBlank()) candidates[videoId] = title
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        val statusUrl = "https://www.googleapis.com/youtube/v3/videos".toHttpUrl().newBuilder()
            .addQueryParameter("part", "status")
            .addQueryParameter("id", candidates.keys.joinToString(","))
            .addQueryParameter("key", apiKey)
            .build()
        val embeddableIds = linkedSetOf<String>()
        okhttp3.Request.Builder().url(statusUrl).build().let { request ->
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val items = org.json.JSONObject(response.body?.string() ?: "{}").optJSONArray("items")
                    if (items != null) {
                        for (i in 0 until items.length()) {
                            val item = items.getJSONObject(i)
                            val id = item.optString("id")
                            val embeddable = item.optJSONObject("status")?.optBoolean("embeddable", true) ?: true
                            if (embeddable && id.isNotBlank()) embeddableIds.add(id)
                        }
                    }
                }
                // If the status check itself fails for any reason, fall
                // back to trusting the original search order rather than
                // blocking playback entirely.
            }
        }

        val chosenIds = if (embeddableIds.isEmpty()) candidates.keys else candidates.keys.filter { embeddableIds.contains(it) }
        return chosenIds.map { it to (candidates[it] ?: "") }
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

        val gemini = GeminiLiveClient(apiKey = apiKeyValue)
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
        }
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

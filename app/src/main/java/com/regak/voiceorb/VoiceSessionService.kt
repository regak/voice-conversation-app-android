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
    }

    private var client: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null
    @Volatile private var muted: Boolean = false
    private var openTranscriptLine: TranscriptLine? = null
    private var sessionJob: Job? = null
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

        val searchResult = runCatching { searchYoutube(query, apiKey) }.getOrElse { t ->
            log("YouTube search failed: ${t.javaClass.simpleName}: ${t.message}")
            return org.json.JSONObject().apply { put("error", "YouTube search failed: ${t.message}") }
        }
        if (searchResult == null) {
            return org.json.JSONObject().apply { put("error", "No YouTube results found for '$query'") }
        }
        val (videoId, title) = searchResult

        currentVideo.value = YoutubeVideo(videoId, title)
        playerCommand.value = PlayerCommand.Play
        log("loaded into embedded player: '$title' ($videoId) for query '$query'")

        return org.json.JSONObject().apply {
            put("result", "playing")
            put("title", title)
        }
    }

    private val httpClient = okhttp3.OkHttpClient()

    /** Real YouTube Data API v3 search -- returns (videoId, title) of the
     *  top result, or null if nothing matched. Runs on Dispatchers.IO
     *  (blocking call), never the main thread. */
    private fun searchYoutube(query: String, apiKey: String): Pair<String, String>? {
        val url = "https://www.googleapis.com/youtube/v3/search".toHttpUrl().newBuilder()
            .addQueryParameter("part", "snippet")
            .addQueryParameter("q", query)
            .addQueryParameter("type", "video")
            .addQueryParameter("maxResults", "1")
            .addQueryParameter("key", apiKey)
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

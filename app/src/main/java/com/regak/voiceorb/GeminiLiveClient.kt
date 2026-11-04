package com.regak.voiceorb

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal WebSocket client for the Google Gemini Live API
 * (BidiGenerateContent). No official Android SDK exists for this endpoint,
 * so this talks the protocol directly.
 *
 * Protocol notes (see android-app-building skill,
 * references/realtime-voice-websocket-apis.md, for the up-to-date version
 * of these facts -- model names and event shapes shift between beta/GA):
 *  - Auth via the `x-goog-api-key` HTTP header on the handshake request,
 *    NOT a `?key=` query parameter.
 *  - The FIRST message sent after the socket opens MUST be the `setup`
 *    message. Any audio chunk sent before the server's `setupComplete`
 *    reply is silently dropped by the server with no error -- the whole
 *    session just never responds to anything. We guard this with
 *    [setupAcked].
 *  - Audio in: 16kHz mono PCM16, base64, as
 *    realtimeInput.audio = { data, mimeType: "audio/pcm;rate=16000" }.
 *  - Audio out: 24kHz mono PCM16 arrives inside
 *    serverContent.modelTurn.parts[].inlineData.data (base64).
 *  - serverContent.interrupted == true -> user barged in, flush playback.
 *    Gemini cancels the model turn server-side automatically; there is no
 *    client-sent cancel message (unlike OpenAI Realtime).
 *  - serverContent.turnComplete == true -> model finished this turn.
 */
class GeminiLiveClient(
    private val apiKey: String,
    private val model: String = "models/gemini-3.8-live",
) {
    companion object {
        private const val TAG = "GeminiLiveClient"
        private const val ENDPOINT =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    sealed class Event {
        data class Connected(val raw: String) : Event()
        data class SetupComplete(val raw: String) : Event()
        data class AudioChunk(val pcm16Base64: String) : Event()
        data class TranscriptDelta(val text: String, val isModel: Boolean) : Event()
        data class Interrupted(val raw: String) : Event()
        data class TurnComplete(val raw: String) : Event()
        data class ToolCall(val raw: JSONObject) : Event()
        data class RawServerMessage(val text: String) : Event()
        data class Failure(val message: String) : Event()
        data class Closed(val reason: String) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> get() = _events

    private var webSocket: WebSocket? = null
    val setupAcked = AtomicBoolean(false)
    private val pendingAudioQueue = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming socket, no read timeout
        .build()

    fun connect() {
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-goog-api-key", apiKey)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened")
                emit(Event.Connected("open"))
                sendSetupMessage(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                emit(Event.Failure(t.message ?: "unknown failure"))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                emit(Event.Closed("code=$code reason=$reason"))
            }
        })
    }

    /** Must be the first message on the wire -- see class doc. */
    private fun sendSetupMessage(ws: WebSocket) {
        val setup = JSONObject().apply {
            put("setup", JSONObject().apply {
                put("model", model)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", listOf("AUDIO"))
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
            })
        }
        ws.send(setup.toString())
    }

    private fun handleServerMessage(text: String) {
        emit(Event.RawServerMessage(text))
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return

        if (json.has("setupComplete")) {
            setupAcked.set(true)
            emit(Event.SetupComplete(text))
            flushPendingAudio()
            return
        }

        if (json.has("toolCall")) {
            emit(Event.ToolCall(json.getJSONObject("toolCall")))
            return
        }

        val serverContent = json.optJSONObject("serverContent") ?: return

        if (serverContent.optBoolean("interrupted", false)) {
            emit(Event.Interrupted(text))
        }

        serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                part.optJSONObject("inlineData")?.optString("data")?.let { b64 ->
                    if (b64.isNotEmpty()) emit(Event.AudioChunk(b64))
                }
            }
        }

        serverContent.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) emit(Event.TranscriptDelta(it, isModel = true))
        }
        serverContent.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) emit(Event.TranscriptDelta(it, isModel = false))
        }

        if (serverContent.optBoolean("turnComplete", false)) {
            emit(Event.TurnComplete(text))
        }
    }

    /** Send one 16kHz PCM16 chunk, base64-encoded by the caller's AudioEngine. */
    fun sendAudioChunk(pcm16Base64: String) {
        if (!setupAcked.get()) {
            // Queue until setupComplete arrives -- never race the handshake.
            synchronized(pendingAudioQueue) { pendingAudioQueue.add(pcm16Base64) }
            return
        }
        sendRealtimeAudio(pcm16Base64)
    }

    private fun flushPendingAudio() {
        val queued = synchronized(pendingAudioQueue) {
            val copy = pendingAudioQueue.toList()
            pendingAudioQueue.clear()
            copy
        }
        queued.forEach { sendRealtimeAudio(it) }
    }

    private fun sendRealtimeAudio(pcm16Base64: String) {
        val msg = JSONObject().apply {
            put("realtimeInput", JSONObject().apply {
                put("audio", JSONObject().apply {
                    put("data", pcm16Base64)
                    put("mimeType", "audio/pcm;rate=16000")
                })
            })
        }
        webSocket?.send(msg.toString())
    }

    fun sendToolResponse(functionId: String, response: JSONObject) {
        val msg = JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", listOf(JSONObject().apply {
                    put("id", functionId)
                    put("response", response)
                }))
            })
        }
        webSocket?.send(msg.toString())
    }

    fun close() {
        webSocket?.close(1000, "client closing")
        webSocket = null
        setupAcked.set(false)
    }

    private fun emit(event: Event) {
        scope.launch { _events.emit(event) }
    }

    fun encodeToBase64(bytes: ByteArray, size: Int): String =
        Base64.encodeToString(bytes, 0, size, Base64.NO_WRAP)
}

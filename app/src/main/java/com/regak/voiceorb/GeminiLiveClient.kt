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
import org.json.JSONArray
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
    // A handle from a prior SessionResumptionUpdate event -- passing it
    // resumes the EXACT same server-side conversation state (full
    // context, not just our own local transcript) instead of starting
    // fresh. This is what actually fixes "forgets everything after a
    // network drop": our own auto-reconnect used to just open a brand
    // new, empty session.
    private val resumeHandle: String? = null,
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
        data class ToolCall(val id: String, val name: String, val args: JSONObject) : Event()
        data class RawServerMessage(val text: String) : Event()
        data class Failure(val message: String) : Event()
        data class Closed(val reason: String) : Event()
        /** A resumable handle for this exact conversation's server-side
         *  state, sent periodically by the server. [resumable] is false
         *  during the brief window right after the server invalidates the
         *  previous handle while preparing a new one -- only handles with
         *  resumable=true are safe to reconnect with later. */
        data class SessionResumptionUpdate(val handle: String, val resumable: Boolean) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 64)
    val events: SharedFlow<Event> get() = _events

    private var webSocket: WebSocket? = null
    val setupAcked = AtomicBoolean(false)
    private val pendingAudioQueue = mutableListOf<String>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming socket, no read timeout
        .pingInterval(20, TimeUnit.SECONDS) // keep idle sockets alive through NATs/proxies
        .build()

    fun connect() {
        val request = Request.Builder()
            .url(ENDPOINT)
            .addHeader("x-goog-api-key", apiKey)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened")
                emit(Event.Connected("open, httpCode=${response.code}"))
                try {
                    sendSetupMessage(webSocket)
                } catch (t: Throwable) {
                    emit(Event.Failure("sendSetupMessage threw: ${t.javaClass.simpleName}: ${t.message}"))
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                // The Gemini Live API sends its JSON replies as BINARY frames,
                // not text frames (confirmed via a standalone Python test where
                // ws.recv() returned `bytes`, not `str`). OkHttp routes binary
                // frames to this overload specifically -- missing it meant every
                // server reply was silently dropped with zero error, which is
                // exactly the "connects fine, never responds" symptom.
                handleServerMessage(bytes.utf8())
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                val code = response?.code
                val body = runCatching { response?.body?.string() }.getOrNull()
                emit(Event.Failure("${t.javaClass.simpleName}: ${t.message} httpCode=$code body=$body"))
            }

            // The server-initiated graceful close handshake (e.g. code 1008
            // "policy violation" when a stale/expired session-resumption
            // handle is rejected with "session not found"). OkHttp requires
            // the CLIENT to echo back webSocket.close() to complete this
            // handshake before onClosed() will ever fire -- without this
            // override, a server-side close before setup completes left the
            // app stuck showing "Connecting..." forever (no Failure/Closed
            // event was ever emitted, since onClosed() never ran).
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closing: code=$code reason=$reason")
                webSocket.close(code, reason)
                if (!setupAcked.get()) {
                    // Never got through setup -- surface as a real failure
                    // (not a quiet Closed/IDLE) so the UI doesn't hang and
                    // the caller's reconnect/retry logic kicks in.
                    emit(Event.Failure("WebSocket closed before setup completed: code=$code reason=$reason"))
                }
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
                    put("responseModalities", JSONArray(listOf("AUDIO")))
                })
                put("inputAudioTranscription", JSONObject())
                put("outputAudioTranscription", JSONObject())
                // Requests the server to periodically send a resumable
                // handle (SessionResumptionUpdate) -- reconnecting later
                // with that handle restores the EXACT same conversation
                // state server-side, fixing "forgets everything after a
                // network drop" instead of just opening an empty session.
                put("sessionResumption", JSONObject().apply {
                    if (!resumeHandle.isNullOrBlank()) put("handle", resumeHandle)
                })
                put("tools", JSONArray(listOf(
                    // NOTE: googleSearch grounding is deliberately NOT
                    // enabled here -- it requires a billing-enabled Gemini
                    // API tier and immediately fails the ENTIRE session with
                    // a quota error on a free-tier key (confirmed via a
                    // standalone test). Instead, play_youtube takes a plain
                    // search query and the APP performs a real YouTube Data
                    // API v3 search itself to resolve the exact video --
                    // guaranteed real playback, never a hallucinated video.
                    JSONObject().put("functionDeclarations", JSONArray(listOf(
                        JSONObject().apply {
                            put("name", "play_youtube")
                            put(
                                "description",
                                "Plays a YouTube video for the user by searching YouTube " +
                                    "for the given query and opening the top real result " +
                                    "in the YouTube app. Pass the user's request as a " +
                                    "natural search query (e.g. 'Dune Part Two official " +
                                    "trailer', 'lo-fi study music') -- do not try to guess " +
                                    "a video URL or ID yourself.",
                            )
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("query", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Natural-language search query describing the video to play.")
                                    })
                                    put("is_live", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put(
                                            "description",
                                            "Set true ONLY if the user explicitly wants a LIVE " +
                                                "broadcast happening right now (e.g. 'is X live', " +
                                                "'play the live stream', 'watch the live service " +
                                                "now'). This filters the search to currently-live " +
                                                "broadcasts only, so don't set it for ordinary " +
                                                "videos, replays, or when the user didn't ask for " +
                                                "something live.",
                                        )
                                    })
                                })
                                put("required", JSONArray(listOf("query")))
                            })
                        },
                        JSONObject().apply {
                            put("name", "reply_whatsapp")
                            put(
                                "description",
                                "Sends a WhatsApp reply to a conversation that just " +
                                    "sent a new message (announced to you via a SYSTEM " +
                                    "NOTICE). Use this when the user says what they want " +
                                    "to reply, e.g. 'tell her I'll be there by 8'. Draft " +
                                    "the actual message text yourself from what the user " +
                                    "said -- don't just repeat their instruction verbatim " +
                                    "unless that IS the message.",
                            )
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("message", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The reply message text to send, drafted from what the user said.")
                                    })
                                    put("recipient_hint", JSONObject().apply {
                                        put("type", "STRING")
                                        put(
                                            "description",
                                            "Optional: the name of the person/group to reply " +
                                                "to, if there might be more than one pending " +
                                                "WhatsApp conversation. Omit to reply to the " +
                                                "most recently announced one.",
                                        )
                                    })
                                })
                                put("required", JSONArray(listOf("message")))
                            })
                        },
                        JSONObject().apply {
                            put("name", "reply_sms")
                            put(
                                "description",
                                "Sends a plain SMS reply to a conversation that just " +
                                    "sent a new text message (announced to you via a " +
                                    "SYSTEM NOTICE). Use when the user says what they " +
                                    "want to reply. Draft the actual message text " +
                                    "yourself from what the user said.",
                            )
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("message", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The reply text to send, drafted from what the user said.")
                                    })
                                    put("recipient_hint", JSONObject().apply {
                                        put("type", "STRING")
                                        put(
                                            "description",
                                            "Optional: the name or number of who to reply to, " +
                                                "if more than one SMS conversation might be " +
                                                "pending. Omit to reply to the most recent one.",
                                        )
                                    })
                                })
                                put("required", JSONArray(listOf("message")))
                            })
                        },
                        JSONObject().apply {
                            put("name", "send_sms")
                            put(
                                "description",
                                "Sends a brand-new SMS text message to someone -- not a " +
                                    "reply to an existing conversation. Use when the user " +
                                    "says something like 'text Mom I'll be late' or 'send a " +
                                    "message to 0712345678 saying on my way'.",
                            )
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("recipient", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The contact name (e.g. 'Mom') or phone number to send to.")
                                    })
                                    put("message", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "The message text to send, drafted from what the user said.")
                                    })
                                })
                                put("required", JSONArray(listOf("recipient", "message")))
                            })
                        },
                        JSONObject().apply {
                            put("name", "query_sms")
                            put(
                                "description",
                                "Searches the user's actual SMS message history on the " +
                                    "phone (not just new arrivals). Use for requests like " +
                                    "'read my messages from Mom today', 'any unread texts?', " +
                                    "or 'what did John say yesterday'. Returns a list of " +
                                    "matching messages for you to summarize/read aloud.",
                            )
                            put("parameters", JSONObject().apply {
                                put("type", "OBJECT")
                                put("properties", JSONObject().apply {
                                    put("contact_hint", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "Optional: a contact name to filter by, e.g. 'Mom'. Omit to search all senders.")
                                    })
                                    put("time_range", JSONObject().apply {
                                        put("type", "STRING")
                                        put("description", "One of: 'today', 'yesterday', 'this_week', or 'all' (default).")
                                    })
                                    put("unread_only", JSONObject().apply {
                                        put("type", "BOOLEAN")
                                        put("description", "True to only return unread received messages. Defaults to false.")
                                    })
                                })
                            })
                        },
                    ))),
                )))
            })
        }
        val payload = setup.toString()
        emit(Event.RawServerMessage("SENDING setup: $payload"))
        val sent = ws.send(payload)
        emit(Event.RawServerMessage("ws.send(setup) returned $sent"))
    }

    private var audioChunkLogCounter = 0

    private fun handleServerMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json == null) {
            emit(Event.RawServerMessage(text))
            return
        }

        if (json.has("setupComplete")) {
            setupAcked.set(true)
            emit(Event.RawServerMessage(text))
            emit(Event.SetupComplete(text))
            flushPendingAudio()
            return
        }

        if (json.has("sessionResumptionUpdate")) {
            val update = json.getJSONObject("sessionResumptionUpdate")
            val handle = update.optString("newHandle")
            val resumable = update.optBoolean("resumable", false)
            if (handle.isNotBlank()) emit(Event.SessionResumptionUpdate(handle, resumable))
            return
        }

        if (json.has("toolCall")) {
            emit(Event.RawServerMessage(text))
            val toolCall = json.getJSONObject("toolCall")
            val functionCalls = toolCall.optJSONArray("functionCalls")
            if (functionCalls != null) {
                for (i in 0 until functionCalls.length()) {
                    val call = functionCalls.getJSONObject(i)
                    val id = call.optString("id")
                    val name = call.optString("name")
                    val args = call.optJSONObject("args") ?: JSONObject()
                    emit(Event.ToolCall(id, name, args))
                }
            }
            return
        }

        val serverContent = json.optJSONObject("serverContent")
        if (serverContent == null) {
            emit(Event.RawServerMessage(text))
            return
        }

        if (serverContent.optBoolean("interrupted", false)) {
            emit(Event.RawServerMessage(text))
            emit(Event.Interrupted(text))
        }

        // Audio frames arrive many times per second during active speech --
        // deliberately do NOT emit a RawServerMessage for every one of these
        // (that was flooding the Compose debug panel with recompositions on
        // the main thread and causing an ANR mid-conversation). Throttle to
        // a periodic summary instead, same pattern as mic-capture logging.
        var hadAudio = false
        serverContent.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                part.optJSONObject("inlineData")?.optString("data")?.let { b64 ->
                    if (b64.isNotEmpty()) {
                        hadAudio = true
                        emit(Event.AudioChunk(b64))
                    }
                }
            }
        }
        if (hadAudio) {
            audioChunkLogCounter++
            if (audioChunkLogCounter % 20 == 0) {
                emit(Event.RawServerMessage("[throttled] received $audioChunkLogCounter audio chunks so far"))
            }
        }

        serverContent.optJSONObject("outputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) emit(Event.TranscriptDelta(it, isModel = true))
        }
        serverContent.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) emit(Event.TranscriptDelta(it, isModel = false))
        }

        if (serverContent.optBoolean("turnComplete", false)) {
            emit(Event.RawServerMessage(text))
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
                put("functionResponses", JSONArray(listOf(JSONObject().apply {
                    put("id", functionId)
                    put("response", response)
                })))
            })
        }
        webSocket?.send(msg.toString())
    }

    /** Injects a synthetic "user" turn carrying text instead of mic audio --
     *  used to announce events the model should react to out loud (e.g. a
     *  new WhatsApp message arriving) without the user actually having
     *  said anything. The Live API's wire protocol has no separate
     *  "system" turn role mid-stream, so the injected text itself makes
     *  clear it's a system notice, not the user speaking, to avoid the
     *  model treating it as a literal user utterance. */
    fun sendUserTextTurn(text: String) {
        val msg = JSONObject().apply {
            put("clientContent", JSONObject().apply {
                put("turns", JSONArray(listOf(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray(listOf(JSONObject().apply {
                        put("text", text)
                    })))
                })))
                put("turnComplete", true)
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

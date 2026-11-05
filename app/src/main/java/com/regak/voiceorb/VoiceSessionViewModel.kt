package com.regak.voiceorb

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

enum class OrbState { IDLE, LISTENING, SPEAKING, CONNECTING, ERROR }

/** One message bubble in the conversation. `text` is mutable so consecutive
 *  deltas for the SAME turn append into the same bubble (observed in place
 *  by Compose) instead of each delta becoming its own fragment line. */
class TranscriptLine(val isModel: Boolean) {
    var text: String by mutableStateOf("")
}

/**
 * Owns the GeminiLiveClient + AudioEngine lifecycle and exposes
 * Compose-friendly state. Keeping this logic out of MainActivity means it
 * survives configuration changes and keeps the UI dumb.
 */
class VoiceSessionViewModel : ViewModel() {

    var orbState = mutableStateOf(OrbState.IDLE)
    val transcript = mutableStateListOf<TranscriptLine>()
    val debugLog = mutableStateListOf<String>()
    var lastError = mutableStateOf<String?>(null)

    private var client: GeminiLiveClient? = null
    private var audioEngine: AudioEngine? = null
    @Volatile private var muted: Boolean = false
    private var openTranscriptLine: TranscriptLine? = null

    fun setMuted(value: Boolean) {
        muted = value
        log(if (value) "mic muted by user" else "mic unmuted by user")
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
    }

    /** Appends a transcript delta into the currently-open bubble for that
     *  speaker, or starts a new bubble if the speaker changed (or none is
     *  open yet). This is what makes consecutive deltas read as one
     *  continuous sentence/message instead of fragmented lines. */
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

    /** Closes the currently-open bubble so the NEXT delta (even from the
     *  same speaker) starts a fresh message -- called at natural turn
     *  boundaries (model finished speaking, or a barge-in interrupted it). */
    private fun closeOpenTranscriptLine() {
        openTranscriptLine = null
    }

    fun start(apiKeyValue: String) {
        if (apiKeyValue.isBlank()) {
            lastError.value = "No Gemini API key configured (set GEMINI_API_KEY in local.properties)."
            orbState.value = OrbState.ERROR
            return
        }
        orbState.value = OrbState.CONNECTING
        lastError.value = null

        val engine = AudioEngine(
            onChunkCaptured = { b64 -> if (!muted) client?.sendAudioChunk(b64) },
            onLog = { log("audio: $it") },
            onError = {
                log("AUDIO ERROR: $it")
                lastError.value = it
                orbState.value = OrbState.ERROR
            },
        )
        audioEngine = engine

        val gemini = GeminiLiveClient(apiKey = apiKeyValue)
        client = gemini

        val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, t ->
            failWith("session coroutine crashed", t)
        }

        viewModelScope.launch(exceptionHandler) {
            gemini.events.collect { event ->
                try {
                    when (event) {
                        is GeminiLiveClient.Event.Connected -> log("ws connected")
                        is GeminiLiveClient.Event.SetupComplete -> {
                            log("setupComplete received -- starting mic capture")
                            orbState.value = OrbState.LISTENING
                            engine.startCapture()
                        }
                        is GeminiLiveClient.Event.AudioChunk -> {
                            orbState.value = OrbState.SPEAKING
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
                        }
                        is GeminiLiveClient.Event.TurnComplete -> {
                            closeOpenTranscriptLine()
                            orbState.value = OrbState.LISTENING
                        }
                        is GeminiLiveClient.Event.ToolCall -> {
                            log("toolCall: ${event.raw}")
                            // TODO: dispatch to native tool handlers (search / open_url / etc).
                        }
                        is GeminiLiveClient.Event.RawServerMessage -> {
                            log("recv: ${event.text.take(500)}")
                        }
                        is GeminiLiveClient.Event.Failure -> {
                            log("FAILURE: ${event.message}")
                            lastError.value = event.message
                            orbState.value = OrbState.ERROR
                        }
                        is GeminiLiveClient.Event.Closed -> {
                            log("closed: ${event.reason}")
                            orbState.value = OrbState.IDLE
                        }
                    }
                } catch (t: kotlinx.coroutines.CancellationException) {
                    throw t // normal cooperative cancellation (ViewModel cleared) -- not an error
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

    fun stop() {
        runCatching { audioEngine?.release() }
        runCatching { client?.close() }
        audioEngine = null
        client = null
        openTranscriptLine = null
        lastError.value = null
        orbState.value = OrbState.IDLE
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

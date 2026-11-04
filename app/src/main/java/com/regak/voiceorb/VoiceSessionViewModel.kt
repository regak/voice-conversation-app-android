package com.regak.voiceorb

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

enum class OrbState { IDLE, LISTENING, SPEAKING, CONNECTING, ERROR }

data class TranscriptLine(val text: String, val isModel: Boolean)

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

    private fun log(msg: String) {
        debugLog.add(msg)
        while (debugLog.size > 200) debugLog.removeAt(0)
    }

    fun start(apiKey: String) {
        if (apiKey.isBlank()) {
            lastError.value = "No Gemini API key configured (set GEMINI_API_KEY in local.properties)."
            orbState.value = OrbState.ERROR
            return
        }
        orbState.value = OrbState.CONNECTING
        lastError.value = null

        val engine = AudioEngine(
            onChunkCaptured = { b64 -> client?.sendAudioChunk(b64) },
            onLog = { log("audio: $it") },
        )
        audioEngine = engine

        val gemini = GeminiLiveClient(apiKey = apiKey)
        client = gemini

        viewModelScope.launch {
            gemini.events.collect { event ->
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
                        transcript.add(TranscriptLine(event.text, event.isModel))
                    }
                    is GeminiLiveClient.Event.Interrupted -> {
                        log("barge-in: interrupted by user, flushing playback")
                        engine.flushPlayback()
                        orbState.value = OrbState.LISTENING
                    }
                    is GeminiLiveClient.Event.TurnComplete -> {
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
            }
        }

        gemini.connect()
    }

    fun stop() {
        audioEngine?.release()
        client?.close()
        audioEngine = null
        client = null
        orbState.value = OrbState.IDLE
    }

    override fun onCleared() {
        stop()
        super.onCleared()
    }
}

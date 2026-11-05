package com.regak.voiceorb

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel

enum class OrbState { IDLE, LISTENING, SPEAKING, CONNECTING, ERROR }

/** One message bubble in the conversation. `text` is mutable so consecutive
 *  deltas for the SAME turn append into the same bubble (observed in place
 *  by Compose) instead of each delta becoming its own fragment line. */
class TranscriptLine(val isModel: Boolean) {
    var text: String by mutableStateOf("")
}

/**
 * Thin facade over VoiceSessionService: the actual session (GeminiLiveClient
 * + AudioEngine) now lives in a foreground Service so it survives the app
 * being backgrounded -- a plain ViewModel is destroyed/throttled with the
 * Activity and cannot do that. This class just forwards start/stop/mute
 * calls as Intents and exposes the service's own Compose state directly, so
 * the existing Compose UI code barely had to change.
 */
class VoiceSessionViewModel(application: Application) : AndroidViewModel(application) {

    val orbState get() = VoiceSessionService.orbState
    val transcript get() = VoiceSessionService.transcript
    val debugLog get() = VoiceSessionService.debugLog
    val lastError get() = VoiceSessionService.lastError

    fun start(apiKeyValue: String) {
        val intent = Intent(getApplication(), VoiceSessionService::class.java).apply {
            action = VoiceSessionService.ACTION_START
            putExtra(VoiceSessionService.EXTRA_API_KEY, apiKeyValue)
        }
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun stop() {
        val intent = Intent(getApplication(), VoiceSessionService::class.java).apply {
            action = VoiceSessionService.ACTION_STOP
        }
        getApplication<Application>().startService(intent)
    }

    fun setMuted(muted: Boolean) {
        val intent = Intent(getApplication(), VoiceSessionService::class.java).apply {
            action = VoiceSessionService.ACTION_SET_MUTED
            putExtra(VoiceSessionService.EXTRA_MUTED, muted)
        }
        getApplication<Application>().startService(intent)
    }
}

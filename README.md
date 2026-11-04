# voice-conversation-app-android

Native Android (Kotlin + Jetpack Compose) port of
[regak/voice-conversation-app](https://github.com/regak/voice-conversation-app) —
a real-time voice-to-voice companion built on the Google Gemini Live API:
interruptible, with live transcripts and tool calling.

Status: planning / scaffolding — no working build yet.

## Why native instead of the web app

The original is a React + Vite web app using Web Audio API AudioWorklets and a
browser WebSocket client. This project reimplements the same real-time
audio-in/audio-out loop natively on Android:

| Web piece | Android equivalent |
|---|---|
| Web Audio API AudioWorklets (mic capture/playback) | `AudioRecord` (16kHz PCM16 capture) + `AudioTrack` (24kHz PCM16 playback) |
| `@google/genai` WebSocket client | Raw WebSocket client (OkHttp/Ktor) to the Gemini Live endpoint directly — no official Android SDK for this API yet |
| React state / animated orb | Jetpack Compose state + Compose animation |
| Tool calls (googleSearch, play_youtube, open_url, set_theme_color, get_current_time) | Native equivalents: Search grounding, `Intent(ACTION_VIEW)`, Compose theme state, `System.currentTimeMillis()` |
| `.env` API key | `local.properties` / BuildConfig field (same "don't ship publicly" caveat applies) |

## Planned architecture

1. **Project scaffold** — Kotlin + Jetpack Compose, single Activity, minSdk ~24.
2. **WebSocket client** — connects to
   `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent`,
   auth via `x-goog-api-key` header. First message must be the `setup` payload
   before any audio chunk is sent, or the session silently stalls with no
   error.
3. **Mic capture** — `AudioRecord` at 16kHz mono PCM16, base64-chunked into
   `realtimeInput.audio` messages. Gated behind a confirmed `setupComplete`
   flag to avoid racing the handshake.
4. **Playback** — `AudioTrack` at 24kHz mono PCM16, fed from
   `serverContent.modelTurn.parts[].inlineData.data`. Flushed immediately on
   `serverContent.interrupted == true` (Gemini's barge-in is server-driven,
   no client cancel message needed).
5. **Transcripts** — opt-in via empty `inputAudioTranscription` /
   `outputAudioTranscription` objects in the setup message; rendered as a
   scrolling Compose list.
6. **Orb UI** — animated Compose shape with idle / listening / speaking
   states driven by capture/playback flags.
7. **Tool calling** — handle `toolCall` server messages, dispatch to native
   handlers, reply with `toolResponse`.
8. **Permissions** — `RECORD_AUDIO` requested at first use.

## Known risks

- No official Google SDK for this API on Android — hand-rolled WebSocket
  protocol handling; model names and event field names shift between
  beta/GA and need re-verifying against current docs at build time.
- API key bundled in a debug APK is fine for personal sideload testing,
  not for public distribution (same caveat as the original web app).
- The mic-audio-before-setup-ack race is the most common silent failure
  mode for this class of app — plan includes an on-screen debug panel
  (raw last-N server messages) since there's no ADB/logcat access to the
  test device.

## Building

Not yet implemented. Planned: headless Gradle build (JDK 17 + Android
cmdline-tools + Gradle, no Android Studio), `./gradlew assembleDebug`,
sideload via a GitHub Release APK link opened on-device.

## License

MIT (matching the upstream project).

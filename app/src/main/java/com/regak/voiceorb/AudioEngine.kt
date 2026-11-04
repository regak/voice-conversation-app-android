package com.regak.voiceorb

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive

/**
 * Handles raw mic capture (16kHz, for the model) and raw playback
 * (24kHz, from the model). Kept deliberately separate from the WebSocket
 * client -- this class knows nothing about Gemini's JSON protocol, it just
 * produces/consumes base64 PCM16 chunks.
 */
class AudioEngine(
    private val onChunkCaptured: (base64: String) -> Unit,
    private val onLog: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "AudioEngine"
        const val INPUT_SAMPLE_RATE = 16000
        const val OUTPUT_SAMPLE_RATE = 24000
        private const val CHUNK_MS = 100
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile var isCapturing: Boolean = false
        private set

    fun startCapture() {
        if (isCapturing) return
        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf, INPUT_SAMPLE_RATE * 2 * CHUNK_MS / 1000 * 4)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            onLog("AudioRecord failed to initialize")
            return
        }
        audioRecord = record
        isCapturing = true
        record.startRecording()
        onLog("capture started, bufferSize=$bufferSize")

        val chunkSamples = INPUT_SAMPLE_RATE * CHUNK_MS / 1000
        val chunkBytes = ByteArray(chunkSamples * 2) // 16-bit = 2 bytes/sample
        var chunkCount = 0

        captureJob = scope.launch {
            while (isActive && isCapturing) {
                val read = record.read(chunkBytes, 0, chunkBytes.size)
                if (read > 0) {
                    val b64 = Base64.encodeToString(chunkBytes, 0, read, Base64.NO_WRAP)
                    onChunkCaptured(b64)
                    chunkCount++
                    if (chunkCount % 20 == 0) onLog("captured $chunkCount chunks")
                } else if (read < 0) {
                    onLog("AudioRecord.read error code=$read")
                }
            }
        }
    }

    fun stopCapture() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
        onLog("capture stopped")
    }

    private fun ensurePlaybackTrack(): AudioTrack {
        audioTrack?.let { return it }
        val minBuf = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, 4096))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        audioTrack = track
        return track
    }

    /** Feed one base64 PCM16 (24kHz) chunk from the server for playback. */
    fun playChunk(base64Pcm: String) {
        val bytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
        val track = ensurePlaybackTrack()
        track.write(bytes, 0, bytes.size)
    }

    /** Called on serverContent.interrupted == true -- discard queued audio immediately. */
    fun flushPlayback() {
        audioTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.play() }
        }
        onLog("playback flushed (barge-in)")
    }

    fun release() {
        stopCapture()
        audioTrack?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioTrack = null
    }
}

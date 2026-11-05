package com.regak.voiceorb

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
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
    private val onError: (String) -> Unit = {},
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
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Unbounded-ish buffer of decoded PCM chunks awaiting playback. Capacity
    // is generous but finite so a runaway server can't leak memory forever;
    // trySendBlocking from playChunk() will just briefly block the IO-bound
    // caller (never the main thread) if it's ever actually full.
    private val playbackQueue = Channel<ByteArray>(capacity = 256)

    @Volatile var isCapturing: Boolean = false
        private set

    fun startCapture() {
        if (isCapturing) return
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) {
                onError("AudioRecord.getMinBufferSize returned $minBuf -- device/sample rate unsupported")
                return
            }
            val bufferSize = maxOf(minBuf, INPUT_SAMPLE_RATE * 2 * CHUNK_MS / 1000 * 4)

            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                onError("AudioRecord failed to initialize (state=${record.state})")
                return
            }
            audioRecord = record
            isCapturing = true
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                onError("AudioRecord.startRecording() did not enter RECORDING state")
                isCapturing = false
                record.release()
                audioRecord = null
                return
            }
            onLog("capture started, bufferSize=$bufferSize")

            val chunkSamples = INPUT_SAMPLE_RATE * CHUNK_MS / 1000
            val chunkBytes = ByteArray(chunkSamples * 2) // 16-bit = 2 bytes/sample
            var chunkCount = 0

            captureJob = scope.launch {
                try {
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
                } catch (t: CancellationException) {
                    throw t // normal cooperative cancellation (stopCapture) -- not an error
                } catch (t: Throwable) {
                    onError("capture loop crashed: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        } catch (t: Throwable) {
            isCapturing = false
            onError("startCapture() failed: ${t.javaClass.simpleName}: ${t.message}")
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
        ensurePlaybackConsumer()
        return track
    }

    /** Dedicated IO coroutine that drains playbackQueue and does the actual
     *  (potentially blocking) AudioTrack.write(). Started lazily, once. */
    private fun ensurePlaybackConsumer() {
        if (playbackJob != null) return
        playbackJob = scope.launch {
            try {
                for (chunk in playbackQueue) {
                    try {
                        audioTrack?.write(chunk, 0, chunk.size)
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        onError("playback write failed: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            } catch (t: CancellationException) {
                // normal cooperative cancellation (release()/stop()) -- not an error
            } catch (t: Throwable) {
                onError("playback consumer crashed: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** Feed one base64 PCM16 (24kHz) chunk from the server for playback.
     *  Decodes and enqueues only -- the actual blocking AudioTrack.write()
     *  happens on a dedicated IO coroutine (see ensurePlaybackConsumer), so
     *  this is safe to call from the main thread without risking an ANR if
     *  playback briefly falls behind (e.g. while the app is backgrounded and
     *  throttled). */
    fun playChunk(base64Pcm: String) {
        try {
            val bytes = Base64.decode(base64Pcm, Base64.NO_WRAP)
            ensurePlaybackTrack()
            // trySendBlocking only blocks the actual caller if the queue is
            // genuinely full (256 chunks @ ~100ms each = ~25s backlog) --
            // call playChunk from a background coroutine if that's a concern,
            // but in practice the consumer drains far faster than that.
            playbackQueue.trySendBlocking(bytes)
        } catch (t: Throwable) {
            onError("playChunk failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Called on serverContent.interrupted == true -- discard queued audio immediately. */
    fun flushPlayback() {
        // Drop anything still queued but not yet written -- a barge-in means
        // the user doesn't want to hear the rest of what's buffered.
        while (true) {
            val result = playbackQueue.tryReceive()
            if (result.isFailure) break
        }
        audioTrack?.let {
            runCatching { it.pause() }
            runCatching { it.flush() }
            runCatching { it.play() }
        }
        onLog("playback flushed (barge-in)")
    }

    fun release() {
        stopCapture()
        playbackJob?.cancel()
        playbackJob = null
        audioTrack?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioTrack = null
    }
}

package com.audiobridge.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.util.Log
import com.audiobridge.app.util.PcmChunk
import com.audiobridge.app.util.PcmFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AudioCaptureEngine"

/**
 * Captures system audio (music/apps/notifications — NOT the mic) using
 * AudioPlaybackCaptureConfiguration, available API 29+.
 *
 * Requires a MediaProjection token, obtained via MediaProjectionManager.createScreenCaptureIntent()
 * — Android requires this user consent step for any app capturing system audio, there's no way
 * around that one system dialog (it's a privacy backstop, not app complexity).
 */
class AudioCaptureEngine(private val mediaProjection: MediaProjection) {

    /**
     * Wraps one AudioRecord instance together with a release-once guard.
     *
     * SIGSEGV Double-Free fix: AudioRecord.release() is NOT idempotent — calling it
     * a second time on an already-released instance frees an already-freed native
     * handle, which crashes the whole process with a native SIGSEGV instead of a
     * catchable Kotlin/Java exception. Previously, stop() (callable from a
     * completely different thread at any time) and the capture flow's own teardown
     * (its `finally` block, running after its read loop exits) each called
     * record.stop()/record.release() independently with no coordination between
     * them — whichever one ran second was operating on a handle the other had
     * already freed. The defensive block at the top of start() had the exact same
     * problem against a still-tearing-down PREVIOUS session's record. Routing every
     * teardown path through releaseOnce() below, guarded by a single AtomicBoolean
     * per session, guarantees the actual stop()/release() pair executes exactly
     * once no matter which caller reaches it first or how the two race.
     */
    private class RecordSession(val record: AudioRecord) {
        private val released = AtomicBoolean(false)

        fun releaseOnce() {
            if (!released.compareAndSet(false, true)) return
            runCatching { record.stop() }
            runCatching { record.release() }
        }
    }

    @Volatile private var currentSession: RecordSession? = null
    @Volatile private var isCapturing = false

    @SuppressLint("MissingPermission")
    fun start(format: PcmFormat): Flow<PcmChunk> = flow {
        // Tear down any previous session through the SAME guarded path used
        // everywhere else, rather than calling stop()/release() on a stale
        // AudioRecord directly — if that previous session's own flow hadn't
        // finished tearing itself down yet, this call and that flow's finally
        // block now safely race on the same AtomicBoolean instead of each calling
        // release() independently on the same native handle.
        currentSession?.releaseOnce()
        currentSession = null
        isCapturing = false

        val isFloatFormat = format.bitDepth == 32
        val encoding = if (isFloatFormat) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(format.sampleRateHz)
            .setEncoding(encoding)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            format.sampleRateHz,
            AudioFormat.CHANNEL_IN_STEREO,
            encoding
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBufferSize")
            return@flow
        }
        // 4x the platform minimum gives headroom against scheduling jitter without
        // adding meaningful latency (~10-20ms at these sample rates).
        val bufferSize = minBufferSize * 4

        val record = AudioRecord.Builder()
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(captureConfig)
            .build()

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return@flow
        }

        val session = RecordSession(record)
        currentSession = session
        audioRecord = record
        isCapturing = true
        record.startRecording()

        if (isFloatFormat) {
            // --- 32-bit float capture path ----------------------------------------
            //
            // AudioRecord.read(byte[], ...) is hardcoded by the platform to reject
            // any AudioRecord configured with ENCODING_PCM_FLOAT — it returns
            // ERROR_INVALID_OPERATION (-3) on every single call, never throws, never
            // blocks. Since -3 is not > 0, the old capture loop's
            // "if (bytesRead > 0)" guard silently treated every read as "no data
            // yet" forever: the UI shows STREAMING, the retry logic never fires
            // (there's no exception to trigger it), and zero packets are ever sent —
            // with nothing anywhere indicating why.
            //
            // The only valid read path for float-encoded audio is the float[]
            // overload, so this branch uses that, then marshals the floats into raw
            // little-endian bytes (4 bytes/sample) for the rest of the pipeline,
            // which is byte-oriented end to end (network transports, ring buffer,
            // compressor) and doesn't need to know the difference. The receiving
            // side (AudioPlaybackEngine) reverses this exact marshaling before
            // calling AudioTrack's own float[] write overload, which has the
            // identical byte[]-rejection restriction.
            val floatChunk = FloatArray(minBufferSize / 4)
            val byteChunk = ByteArray(floatChunk.size * 4)
            val byteView = ByteBuffer.wrap(byteChunk).order(ByteOrder.LITTLE_ENDIAN)
            try {
                while (isCapturing) {
                    val floatsRead = record.read(floatChunk, 0, floatChunk.size, AudioRecord.READ_BLOCKING)
                    if (floatsRead > 0) {
                        byteView.clear()
                        for (i in 0 until floatsRead) byteView.putFloat(floatChunk[i])
                        emit(PcmChunk(byteChunk, floatsRead * 4))
                    }
                }
            } finally {
                session.releaseOnce()
                if (currentSession === session) currentSession = null
                if (audioRecord === record) audioRecord = null
            }
        } else {
            // 16-bit path: the byte[] overload is fully valid for
            // ENCODING_PCM_16BIT, so no marshaling is needed — read straight into a
            // single reused buffer and emit it directly. Every send() path
            // downstream (Udp/Tcp/Bluetooth transports, PcmCompressor) fully
            // consumes the given (data, length) pair synchronously before
            // returning, and Flow.emit() itself suspends this producer until the
            // collector's lambda completes — so it's safe to hand out the same
            // backing array on every iteration instead of allocating+copying a new
            // bytesRead-sized array each time, avoiding one avoidable garbage
            // object per audio chunk on the hottest path in the app.
            val chunk = ByteArray(minBufferSize)
            try {
                while (isCapturing) {
                    val bytesRead = record.read(chunk, 0, chunk.size)
                    if (bytesRead > 0) {
                        emit(PcmChunk(chunk, bytesRead))
                    }
                }
            } finally {
                session.releaseOnce()
                if (currentSession === session) currentSession = null
                if (audioRecord === record) audioRecord = null
            }
        }
    }

    // Retained only as a best-effort external inspection point (nothing in this
    // codebase reads it) — actual teardown never touches this field directly
    // anymore; every real stop()/release() call goes through RecordSession.
    @Volatile private var audioRecord: AudioRecord? = null

    fun stop() {
        isCapturing = false
        // Deliberately NOT nulling currentSession here — the flow's own finally
        // block (running on whatever thread/coroutine is collecting it) still needs
        // to see the same session object so its own releaseOnce() call resolves to
        // a safe no-op via the AtomicBoolean guard, and it clears currentSession
        // itself once it confirms it's still pointing at this exact session (so a
        // brand-new session started concurrently via start() is never clobbered).
        currentSession?.releaseOnce()
    }
}
 
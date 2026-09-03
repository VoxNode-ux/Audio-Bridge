package com.audiobridge.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import com.audiobridge.app.util.PcmFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.roundToInt

private const val TAG = "AudioPlaybackEngine"
private const val DEFAULT_SAFETY_BUFFER_MS = 120
// Matches setSafetyBuffer()'s own coerceIn upper bound — see RING_CAPACITY_MS below.
private const val MAX_SAFETY_BUFFER_MS = 1000
// Ring capacity is sized against the maximum configurable safety buffer (with 20%
// headroom), NOT against whatever safetyBufferMs happens to be set to at prepare()
// time. Otherwise, raising the Jitter Buffer slider at runtime past the capacity
// that was picked for a smaller initial value would silently cap the real cushion
// below what the user asked for — write() would just keep dropping the oldest bytes
// once the undersized ring filled up, no matter how far the slider was dragged.
private const val RING_CAPACITY_MS = 1200

// --- Clock-drift compensation ---------------------------------------------------
//
// Two independent devices' audio clocks (crystal oscillators) never run at exactly
// the same rate — typical consumer-grade crystals drift by tens to a few hundred
// parts-per-million (ppm) relative to nominal. Over a short clip that's
// imperceptible, but over a long session it adds up: if the Sender's effective
// capture rate is even 100ppm faster than the Receiver's playback rate, the ring
// buffer gains roughly 100 microseconds of audio every second — about 6ms/minute,
// ~180ms over 30 minutes — until it periodically overflows and the ring's own
// "drop oldest bytes" overflow logic has to yank a chunk out wholesale, which is
// audible. Conversely, if the Receiver's clock is faster, the ring slowly starves
// and the safety buffer's underrun protection kicks in repeatedly.
//
// The fix is a tiny, continuously-adjusting resampling ratio: nudge the EFFECTIVE
// playback rate by up to a few hundred ppm — well below the ~0.3%+ threshold where
// pitch shift becomes perceptible — in whichever direction keeps the ring buffer's
// fill level hovering around its target, using linear-interpolation resampling to
// synthesize output samples at fractional input positions. This never touches the
// actual AudioTrack hardware sample rate; it only very slightly stretches or
// compresses the SEQUENCE of samples fed to it — the same technique real-time
// playout buffers (e.g. WebRTC's NetEQ) use to absorb inter-device clock drift.
private const val DRIFT_CORRECTION_MAX_PPM = 500.0
private const val DRIFT_CONTROL_INTERVAL_MS = 1000L
private const val DRIFT_CONTROL_GAIN = 0.15
private const val RESAMPLE_BLOCK_MS = 20

/**
 * Plays incoming raw PCM bytes on the tablet's speakers via AudioTrack.
 *
 * Writing straight from the network callback to AudioTrack.write() (the previous
 * behavior) means any jitter in packet arrival — a GC pause, a WiFi retransmit, a
 * scheduling hiccup — becomes an audible glitch immediately, because there's no
 * cushion between "byte arrived" and "byte must play now." This engine adds a small
 * ring buffer in between: incoming chunks are appended to the ring, and a dedicated
 * playback loop pulls from the ring, decodes it into float sample frames, and feeds
 * those through a small adaptive resampler before writing to AudioTrack. That ring
 * buffer trades a small, fixed amount of extra latency for smoothing out irregular
 * arrival timing; the resampler on top of it compensates for the slow, cumulative
 * clock-drift problem the ring buffer alone can't fully absorb over a long session.
 *
 * `context` is used only to reach AudioManager for audio-focus requests (see the
 * "Audio focus" section below) — no reference to it is retained past construction
 * beyond the applicationContext, so this doesn't leak an Activity/Service instance.
 */
class AudioPlaybackEngine(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager: AudioManager? =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private var audioTrack: AudioTrack? = null
    private var currentFormat: PcmFormat? = null

    private val lock = ReentrantLock()
    private var ring = ByteArray(0)
    private var ringCapacity = 0
    private var writePos = 0
    private var readPos = 0
    private var buffered = 0 // bytes currently held in the ring, always <= ringCapacity

    // Bytes per one stereo sample-frame for the current format: 4 for 16-bit
    // (2 channels x 2 bytes), 8 for 32-bit float (2 channels x 4 bytes). Every ring
    // read/write/drop that could break frame alignment is rounded against this, or
    // the resampler's byte->float decode step would desync mid-stream and corrupt
    // every sample after the first misaligned read.
    private var frameSizeBytes = 4
    private var isFloatFormat = false

    // --- Resampler staging buffer (float, decoded, interleaved stereo) -----------
    // Fed FROM the raw byte ring above; the playback thread is the only reader/
    // writer of these fields, so — unlike `ring` — they need no locking.
    private var source = FloatArray(0)
    private var sourceCapacityFrames = 0
    private var sourceFrameCount = 0
    private var sourceReadCursor = 0.0
    private var pullScratch = ByteArray(0)

    private var outFrames = 0
    private var outFloatsScratch: FloatArray? = null
    private var outBytesScratch: ByteArray? = null

    @Volatile private var driftCorrectionPpm: Double = 0.0
    private var lastDriftControlAt = 0L

    @Volatile private var safetyBufferMs: Int = DEFAULT_SAFETY_BUFFER_MS
    @Volatile private var hasPrimedOnce = false

    // --- Audio focus ---------------------------------------------------------------
    //
    // Without requesting focus, this engine writes to AudioTrack directly regardless
    // of what else is making sound on the device — a notification ping, an incoming
    // call ringing, another media app the user switches to — all mix together or get
    // talked over, because nothing has told the system this app wants priority use of
    // the output. Requesting AUDIOFOCUS_GAIN and reacting to the resulting callbacks
    // is what lets this behave like any other well-behaved audio app: muting under a
    // phone call, ducking under a short notification sound, and coming back up when
    // the interruption ends — instead of the network stream and the interrupting
    // sound fighting over the same speaker.
    //
    // Network reception and the ring buffer keep running unaffected by focus state —
    // only the audible output (the AudioTrack volume) is touched. Muting rather than
    // fully stopping playback means a transient interruption doesn't risk desyncing
    // the ring against the live sender or losing the safety-buffer priming state.
    private var audioFocusRequest: AudioFocusRequest? = null
    private var userVolume: Float = 1.0f
    @Volatile private var focusDuckFactor: Float = 1.0f // 1.0 normal, 0.2 ducked, 0.0 muted

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus regained — restoring volume")
                focusDuckFactor = 1.0f
                applyEffectiveVolume()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app has taken focus outright (started its own playback,
                // took over the output device, etc.). Mute and give up the focus
                // grant rather than holding onto one that's no longer meaningful —
                // if the user comes back to this app, prepare()/resume flows will
                // request focus fresh.
                Log.i(TAG, "Audio focus lost permanently — muting")
                focusDuckFactor = 0.0f
                applyEffectiveVolume()
                abandonAudioFocus()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus lost transiently — muting until it returns")
                focusDuckFactor = 0.0f
                applyEffectiveVolume()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Audio focus lost transiently (can duck) — lowering volume")
                focusDuckFactor = 0.2f
                applyEffectiveVolume()
            }
        }
    }

    /**
     * Requests AUDIOFOCUS_GAIN for sustained media-style playback. Safe to call
     * repeatedly — a fresh request replaces any previous one instead of stacking.
     * Failure to obtain focus is logged but treated as non-fatal: this app is a
     * local relay for audio the user explicitly started streaming, so playback still
     * proceeds even without a focus grant, rather than refusing to play anything.
     */
    private fun requestAudioFocus() {
        val manager = audioManager ?: return
        abandonAudioFocus()

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        audioFocusRequest = request
        val result = runCatching { manager.requestAudioFocus(request) }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            Log.w(TAG, "Audio focus request not granted immediately (result=$result)")
        }
    }

    /** Releases any held audio focus request. Safe to call even if none was ever
     *  granted, or if it was already abandoned. */
    private fun abandonAudioFocus() {
        val manager = audioManager
        audioFocusRequest?.let { req ->
            if (manager != null) runCatching { manager.abandonAudioFocusRequest(req) }
        }
        audioFocusRequest = null
    }

    /** Applies the user's chosen volume scaled by the current focus-driven duck
     *  factor, so a focus interruption never permanently overwrites what the user
     *  set via the Volume slider — it's restored automatically once focus returns. */
    private fun applyEffectiveVolume() {
        audioTrack?.setVolume((userVolume * focusDuckFactor).coerceIn(0f, 1f))
    }

    @Volatile private var playbackThread: Thread? = null
    @Volatile private var isRunning = false

    /**
     * Updates the safety buffer at runtime. Larger = smoother under jitter but more
     * lag behind the source; smaller = tighter sync but more prone to audible
     * underrun glitches on a rough link. Takes effect on the next re-prime (empty
     * ring) without requiring a full prepare()/release() cycle.
     */
    fun setSafetyBuffer(ms: Int) {
        safetyBufferMs = ms.coerceIn(20, MAX_SAFETY_BUFFER_MS)
    }

    fun getSafetyBuffer(): Int = safetyBufferMs

    fun prepare(format: PcmFormat) {
        if (currentFormat == format && audioTrack != null) return
        release()

        val encoding = if (format.bitDepth == 32) {
            AudioFormat.ENCODING_PCM_FLOAT
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

        val minBufferSize = AudioTrack.getMinBufferSize(
            format.sampleRateHz,
            AudioFormat.CHANNEL_OUT_STEREO,
            encoding
        )
        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid min buffer size: $minBufferSize")
            return
        }
        // AudioTrack's own internal buffer stays a fixed, modest 2x-minimum — it only
        // needs to smooth CPU scheduling, not network jitter. Network jitter smoothing
        // is the ring buffer's job, sized below against the maximum possible safety
        // buffer (see RING_CAPACITY_MS doc comment above).
        val trackBufferSize = minBufferSize * 2

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(format.sampleRateHz)
                    .setEncoding(encoding)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(trackBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()

        // Request focus before the track actually starts producing sound, and reset
        // any duck/mute state left over from a previous session — a fresh prepare()
        // means a fresh streaming session and should start at full user-chosen volume
        // unless/until a new focus-loss callback says otherwise.
        focusDuckFactor = 1.0f
        requestAudioFocus()

        track.play()
        audioTrack = track
        currentFormat = format

        isFloatFormat = format.bitDepth == 32
        frameSizeBytes = if (isFloatFormat) 8 else 4

        lock.withLock {
            ringCapacity = bytesForMs(format, RING_CAPACITY_MS)
            ring = ByteArray(ringCapacity)
            writePos = 0
            readPos = 0
            buffered = 0
        }
        hasPrimedOnce = false

        // No lock needed here: the playback thread that will read these fields
        // hasn't been started yet (startPlaybackThread() is the last call in this
        // function), so there is no concurrent access at this point.
        sourceCapacityFrames = (ringCapacity / frameSizeBytes) + 8
        source = FloatArray(sourceCapacityFrames * 2)
        sourceFrameCount = 0
        sourceReadCursor = 0.0
        pullScratch = ByteArray(ringCapacity)

        outFrames = (bytesForMs(format, RESAMPLE_BLOCK_MS) / frameSizeBytes).coerceAtLeast(1)
        if (isFloatFormat) {
            outFloatsScratch = FloatArray(outFrames * 2)
            outBytesScratch = null
        } else {
            outBytesScratch = ByteArray(outFrames * frameSizeBytes)
            outFloatsScratch = null
        }
        driftCorrectionPpm = 0.0
        lastDriftControlAt = 0L

        // Apply the user's last-set volume (scaled by the just-reset duck factor)
        // now that the track exists, so a volume set before this prepare() call
        // isn't silently lost on the freshly built AudioTrack instance.
        applyEffectiveVolume()

        startPlaybackThread(format)
        Log.i(TAG, "AudioTrack prepared: ${format.label}, trackBuffer=$trackBufferSize, ringCapacity=$ringCapacity")
    }

    /** Bytes needed to hold `ms` milliseconds of audio at this format — reuses the
     *  format's own bytesPerSecond() so the math lives in one place (PcmFormat).
     *
     *  Rounded DOWN to a whole number of stereo sample-frames (4 bytes for 16-bit,
     *  8 bytes for 32-bit float). The raw ms-based calculation is a float multiply
     *  truncated to Int, which can land short of a frame boundary — e.g. 44.1kHz/
     *  16-bit at 127ms truncates to 22402 bytes, not a multiple of 4. Nothing
     *  downstream actually corrupts on a misaligned byte count today (refillSourceBuffer()
     *  independently re-aligns via `buffered % frameSizeBytes` before every read), but
     *  frame-aligning at the source is cheap insurance against ever handing a
     *  misaligned count to a future call site that assumes whole frames without
     *  re-checking.
     */
    private fun bytesForMs(format: PcmFormat, ms: Int): Int {
        val bytesPerSecond = format.bytesPerSecond(channels = 2)
        val raw = (bytesPerSecond * (ms / 1000.0)).toInt().coerceAtLeast(1)
        val frameSize = (format.bitDepth / 8) * 2
        val aligned = (raw / frameSize) * frameSize
        return aligned.coerceAtLeast(frameSize)
    }

    /**
     * Appends incoming network audio to the ring. Called from the network-receiving
     * coroutine, NOT the playback thread — the two run concurrently, hence the lock.
     * If the ring is full (playback thread has fallen badly behind, e.g. after a long
     * stall), oldest bytes are dropped to make room rather than growing unbounded or
     * blocking the network collector indefinitely.
     */
    fun write(data: ByteArray, length: Int) {
        if (length <= 0) return
        lock.withLock {
            if (ringCapacity == 0) return@withLock
            var remaining = length
            var srcOffset = 0

            if (length > ringCapacity) {
                // Single chunk bigger than the whole ring (shouldn't normally happen —
                // network chunks are small) — keep only the tail that fits.
                srcOffset = length - ringCapacity
                remaining = ringCapacity
            }

            val freeSpace = ringCapacity - buffered
            if (remaining > freeSpace) {
                // Drop the oldest bytes to make room, rather than blocking the caller
                // or growing the buffer — a bounded ring must shed data under sustained
                // overflow, and dropping old audio is less disruptive than an unbounded
                // growing latency spiral.
                var toDrop = remaining - freeSpace
                // Round up to a whole number of sample-frames so `buffered` never ends
                // up frame-misaligned — a misaligned ring would desync the resampler's
                // byte->float decode step, corrupting every sample after the first
                // drop rather than just cleanly losing a little audio.
                val misalignment = toDrop % frameSizeBytes
                if (misalignment != 0) toDrop += (frameSizeBytes - misalignment)
                toDrop = toDrop.coerceAtMost(buffered)
                readPos = (readPos + toDrop) % ringCapacity
                buffered -= toDrop
            }

            var written = 0
            while (written < remaining) {
                val chunkLen = minOf(remaining - written, ringCapacity - writePos)
                System.arraycopy(data, srcOffset + written, ring, writePos, chunkLen)
                writePos = (writePos + chunkLen) % ringCapacity
                written += chunkLen
            }
            buffered += remaining
        }
    }

    private fun startPlaybackThread(format: PcmFormat) {
        isRunning = true
        val thread = Thread({
            try {
                while (isRunning) {
                    val thresholdBytes = bytesForMs(format, safetyBufferMs)
                    val ready = lock.withLock {
                        val needed = if (hasPrimedOnce) frameSizeBytes else thresholdBytes
                        buffered >= needed
                    }
                    if (!ready) {
                        Thread.sleep(5)
                        continue
                    }
                    hasPrimedOnce = true

                    maybeUpdateDriftCorrection(thresholdBytes)

                    if (!isRunning) break

                    val produced = produceOutputBlock()
                    if (!produced) {
                        Thread.sleep(5)
                        continue
                    }

                    // AudioTrack can be released by release()/prepare() racing this
                    // same thread (e.g. release()'s join(300) timed out and the
                    // caller moved on) — write() on an already-released track throws
                    // IllegalStateException. Treat it as "time to stop" rather than
                    // letting it crash the app from a background thread.
                    try {
                        if (isFloatFormat) {
                            // sizeInFloats counts individual interleaved float VALUES
                            // (L and R combined), not frames — outFloatsScratch holds
                            // outFrames*2 valid floats, so the size argument here must
                            // match that, not the frame count itself.
                            audioTrack?.write(outFloatsScratch!!, 0, outFrames * 2, AudioTrack.WRITE_BLOCKING)
                        } else {
                            audioTrack?.write(outBytesScratch!!, 0, outFrames * frameSizeBytes)
                        }
                    } catch (e: IllegalStateException) {
                        Log.w(TAG, "AudioTrack write failed (likely released) — stopping playback thread")
                        isRunning = false
                    }
                }
            } catch (e: InterruptedException) {
                // Expected when release() interrupts a thread stuck in Thread.sleep(5)
                // after join() timed out — not an error.
            } catch (e: Exception) {
                Log.e(TAG, "Playback thread terminating due to unexpected error: ${e.message}")
            }
        }, "AudioBridge-Playback")
        thread.isDaemon = true
        playbackThread = thread
        thread.start()
    }

    /**
     * Produces one ~20ms output block via linear-interpolation resampling at the
     * current drift-correction step size, pulling more raw bytes out of the ring
     * into the float staging buffer as needed. Returns false if there isn't yet
     * enough buffered source data to produce a full block — callers should back off
     * briefly and retry; this can legitimately happen right after priming or after a
     * brief network stall, and is not an error.
     */
    private fun produceOutputBlock(): Boolean {
        val stepSize = 1.0 + (driftCorrectionPpm / 1_000_000.0)
        val framesNeeded = ceil(sourceReadCursor + outFrames * stepSize).toInt() + 2

        if (framesNeeded > sourceFrameCount) {
            refillSourceBuffer(framesNeeded - sourceFrameCount)
        }
        if (sourceFrameCount < 2 || sourceReadCursor + outFrames * stepSize + 1 > sourceFrameCount) {
            return false
        }

        var pos = sourceReadCursor
        val lastIdx = sourceFrameCount - 1
        for (i in 0 until outFrames) {
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val idxNext = (idx + 1).coerceAtMost(lastIdx)
            val l = source[idx * 2] * (1 - frac) + source[idxNext * 2] * frac
            val r = source[idx * 2 + 1] * (1 - frac) + source[idxNext * 2 + 1] * frac
            if (isFloatFormat) {
                outFloatsScratch!![i * 2] = l
                outFloatsScratch!![i * 2 + 1] = r
            } else {
                writeInt16Sample(outBytesScratch!!, i * 2, l)
                writeInt16Sample(outBytesScratch!!, i * 2 + 1, r)
            }
            pos += stepSize
        }
        sourceReadCursor = pos
        compactSourceBuffer()
        return true
    }

    private fun writeInt16Sample(dest: ByteArray, sampleIndex: Int, value: Float) {
        val clamped = value.roundToInt().coerceIn(-32768, 32767)
        val byteOffset = sampleIndex * 2
        dest[byteOffset] = (clamped and 0xFF).toByte()
        dest[byteOffset + 1] = ((clamped shr 8) and 0xFF).toByte()
    }

    /** Drops whole consumed frames off the front of the source staging buffer and
     *  rebases the fractional read cursor accordingly, keeping the buffer bounded
     *  instead of growing forever. */
    private fun compactSourceBuffer() {
        val consumed = sourceReadCursor.toInt()
        if (consumed <= 0) return
        val remaining = sourceFrameCount - consumed
        if (remaining > 0) {
            System.arraycopy(source, consumed * 2, source, 0, remaining * 2)
        }
        sourceFrameCount = remaining
        sourceReadCursor -= consumed
    }

    /** Pulls whole sample-frames out of the raw byte ring (network-jitter buffer)
     *  and decodes them into the float staging buffer the resampler reads from. */
    private fun refillSourceBuffer(minFramesWanted: Int) {
        val spaceFrames = sourceCapacityFrames - sourceFrameCount
        if (spaceFrames <= 0) return
        // Pull a bit more than the immediate minimum so this isn't called on nearly
        // every single output block, but stay capped near what's actually needed —
        // pulling the full remaining ring capacity every call would mean an
        // unnecessarily large arraycopy on the real-time playback thread each time.
        val wantFrames = minFramesWanted.coerceAtLeast(1).coerceAtMost(spaceFrames)
        lock.withLock {
            val alignedBuffered = buffered - (buffered % frameSizeBytes)
            val wantBytes = minOf(wantFrames * frameSizeBytes, alignedBuffered, pullScratch.size)
            if (wantBytes <= 0) return@withLock
            var remaining = wantBytes
            var localOffset = 0
            while (remaining > 0) {
                val chunkLen = minOf(remaining, ringCapacity - readPos)
                System.arraycopy(ring, readPos, pullScratch, localOffset, chunkLen)
                readPos = (readPos + chunkLen) % ringCapacity
                localOffset += chunkLen
                remaining -= chunkLen
            }
            buffered -= wantBytes
            val framesPulled = wantBytes / frameSizeBytes
            decodeFrames(pullScratch, framesPulled)
            sourceFrameCount += framesPulled
        }
    }

    private fun decodeFrames(raw: ByteArray, frameCount: Int) {
        if (frameCount <= 0) return
        val bb = ByteBuffer.wrap(raw, 0, frameCount * frameSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
        var destIndex = sourceFrameCount * 2
        for (f in 0 until frameCount) {
            if (isFloatFormat) {
                source[destIndex++] = bb.float
                source[destIndex++] = bb.float
            } else {
                source[destIndex++] = bb.short.toFloat()
                source[destIndex++] = bb.short.toFloat()
            }
        }
    }

    /**
     * Re-evaluates the drift correction once per DRIFT_CONTROL_INTERVAL_MS using a
     * simple proportional controller: how far the ring's current fill level is from
     * its target (the configured safety buffer), scaled by a gain factor and
     * clamped to the safe correction range. The correction itself is low-pass
     * filtered (glided toward the new target rather than snapped to it) so it can
     * never itself introduce an audible step change.
     */
    private fun maybeUpdateDriftCorrection(thresholdBytes: Int) {
        val now = System.currentTimeMillis()
        if (now - lastDriftControlAt < DRIFT_CONTROL_INTERVAL_MS) return
        lastDriftControlAt = now
        if (thresholdBytes <= 0) return

        val currentBuffered = lock.withLock { buffered }
        val relativeError = (currentBuffered - thresholdBytes).toDouble() / thresholdBytes.toDouble()
        val target = (relativeError * DRIFT_CONTROL_GAIN * DRIFT_CORRECTION_MAX_PPM)
            .coerceIn(-DRIFT_CORRECTION_MAX_PPM, DRIFT_CORRECTION_MAX_PPM)
        driftCorrectionPpm = driftCorrectionPpm * 0.7 + target * 0.3
    }

    /** Sets the user-chosen output volume. Stored separately from the focus-driven
     *  duck factor (see applyEffectiveVolume) so a focus interruption never
     *  overwrites what the user actually asked for — it's simply re-applied once
     *  focus returns to normal. */
    fun setVolume(volume: Float) {
        userVolume = volume.coerceIn(0f, 1f)
        applyEffectiveVolume()
    }

    fun release() {
        isRunning = false
        playbackThread?.let { t ->
            runCatching { t.join(300) }
            if (t.isAlive) {
                // Still stuck — most likely inside Thread.sleep(5) or a blocking
                // AudioTrack.write() call. Interrupt to force it out of sleep, then
                // give it one more short window to notice isRunning=false and exit,
                // rather than silently losing track of a still-running thread that
                // holds a reference to the AudioTrack we're about to release below.
                runCatching { t.interrupt() }
                runCatching { t.join(200) }
            }
        }
        playbackThread = null

        audioTrack?.let {
            runCatching {
                it.stop()
                it.release()
            }
        }
        audioTrack = null
        currentFormat = null

        // Give up focus now that nothing is playing — holding onto a focus grant
        // with no active output would needlessly block/duck other apps for no
        // benefit to this one.
        abandonAudioFocus()
        focusDuckFactor = 1.0f

        lock.withLock {
            ring = ByteArray(0)
            ringCapacity = 0
            writePos = 0
            readPos = 0
            buffered = 0
        }
        hasPrimedOnce = false

        source = FloatArray(0)
        sourceCapacityFrames = 0
        sourceFrameCount = 0
        sourceReadCursor = 0.0
        pullScratch = ByteArray(0)
        outFloatsScratch = null
        outBytesScratch = null
        driftCorrectionPpm = 0.0
        lastDriftControlAt = 0L
    }
}


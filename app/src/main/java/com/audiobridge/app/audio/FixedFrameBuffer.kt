package com.audiobridge.app.audio

/**
 * Re-frames a stream of arbitrarily-sized PCM byte chunks (as delivered by
 * AudioRecord.read(), whose actual chunk size depends on the platform's chosen
 * internal buffer granularity, not anything the app controls) into FIXED-duration
 * frames.
 *
 * ## Why this exists
 * PcmCompressor (delta-encode + deflate) tolerates arbitrary chunk sizes — it
 * doesn't care how many samples it's handed. A real Opus encoder does not have that
 * flexibility: Opus is strictly frame-based and will reject any call that isn't
 * exactly one of its supported frame durations (2.5, 5, 10, 20, 40, or 60 ms) worth
 * of samples. AudioRecord's actual read sizes never line up with those durations by
 * construction (minBufferSize is chosen by the platform for scheduling headroom,
 * not codec compatibility), so a raw AudioCaptureEngine chunk can never be handed
 * directly to an Opus encoder — some accumulation/re-slicing step has to sit in
 * between, which is what this class is.
 *
 * ## Feasibility of a pure-JVM Opus path (e.g. via the Concentus library)
 * Concentus is a straight Kotlin/Java port of the reference libopus encoder/decoder
 * with no JNI or native compilation step, so it fits this project's "Pure JVM Only"
 * constraint (Termux-only build, no NDK) without issue. The ONLY integration gap is
 * exactly the framing mismatch described above — once PCM is flowing through this
 * re-framer instead of directly from AudioCaptureEngine's raw reads, every frame
 * handed onward is guaranteed to be exactly one Opus frame duration, and an encoder
 * can be dropped in as an alternative "compressor" beside PcmCompressor with no
 * other changes needed upstream or downstream. The AudioTransport interface already
 * treats compression as an implementation detail of the sender/receiver transport,
 * not something the capture/playback engines or UI need to know about, so swapping
 * or adding a codec there doesn't ripple outward.
 *
 * This class has no dependency on Opus/Concentus itself — it's a general-purpose
 * fixed-size re-framer usable by any fixed-frame encoder — and is safe to drop into
 * the capture pipeline today, before any Opus dependency is added, with zero effect
 * on current behavior until something actually consumes its output.
 */
class FixedFrameBuffer(
    private val frameSizeBytes: Int
) {
    init {
        require(frameSizeBytes > 0) { "frameSizeBytes must be positive" }
    }

    // Leftover bytes from the previous push() call that didn't add up to a whole
    // frame yet — carried forward and prepended to the next push() so no audio is
    // ever dropped, just re-sliced across call boundaries.
    private var carry = ByteArray(0)

    /**
     * Feeds `length` bytes of newly-captured PCM in. Returns zero or more complete,
     * exactly-`frameSizeBytes`-sized frames extracted from the concatenation of any
     * carried-over remainder plus this new data. Any bytes left over (less than one
     * full frame) are retained internally and prepended to the next call's input —
     * nothing is ever discarded.
     *
     * The returned frames are freshly allocated (safe to retain/enqueue/hand off to
     * an encoder asynchronously, unlike the reused-buffer pattern used elsewhere in
     * this codebase's hot capture path — fixed framing runs at a much lower rate,
     * typically tens to a couple hundred calls/sec at most, so the extra allocation
     * here is not a meaningful GC pressure source the way per-AudioRecord-read
     * copies would be).
     */
    fun push(data: ByteArray, length: Int): List<ByteArray> {
        if (length <= 0) return emptyList()

        val combined: ByteArray
        val combinedLength: Int
        if (carry.isEmpty()) {
            combined = data
            combinedLength = length
        } else {
            combined = ByteArray(carry.size + length)
            System.arraycopy(carry, 0, combined, 0, carry.size)
            System.arraycopy(data, 0, combined, carry.size, length)
            combinedLength = combined.size
        }

        val wholeFrames = combinedLength / frameSizeBytes
        val frames = ArrayList<ByteArray>(wholeFrames)
        var offset = 0
        repeat(wholeFrames) {
            val frame = ByteArray(frameSizeBytes)
            System.arraycopy(combined, offset, frame, 0, frameSizeBytes)
            frames.add(frame)
            offset += frameSizeBytes
        }

        val leftoverLength = combinedLength - offset
        carry = if (leftoverLength > 0) combined.copyOfRange(offset, combinedLength) else ByteArray(0)

        return frames
    }

    /** Discards any partial frame currently carried over — call when starting a
     *  fresh capture session so a previous session's leftover bytes can't bleed
     *  into a new one's first frame. */
    fun reset() {
        carry = ByteArray(0)
    }

    companion object {
        /**
         * Convenience: computes the exact byte size of one Opus-compatible frame
         * for the given format and target frame duration. Pass the result as this
         * class's frameSizeBytes to re-frame PCM specifically for an Opus encoder.
         * Only 2.5/5/10/20/40/60 ms are valid Opus frame durations — anything else
         * will re-frame just fine through THIS class (it accepts any size), but
         * will be rejected by the Opus encoder itself.
         */
        fun opusFrameSizeBytes(sampleRateHz: Int, bitDepth: Int, channels: Int, frameDurationMs: Double): Int {
            val bytesPerSecond = sampleRateHz * (bitDepth / 8) * channels
            return (bytesPerSecond * (frameDurationMs / 1000.0)).toInt()
        }
    }
}
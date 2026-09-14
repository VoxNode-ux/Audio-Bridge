package com.audiobridge.app.audio

import io.voxkit.kopus.Channels
import io.voxkit.kopus.Opus
import io.voxkit.kopus.OpusApplication
import io.voxkit.kopus.OpusDecoder
import io.voxkit.kopus.OpusEncoder
import io.voxkit.kopus.SampleRate
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Opus codec wrapper (io.voxkit:kopus — prebuilt native binaries via JNI, no NDK
 * build step required on this project's end, unlike eu.buney.kopus-full which
 * needs its own native build scripts run locally).
 *
 * Unlike PcmCompressor (stateless object, delta+deflate, tolerates arbitrary chunk
 * sizes), Opus is a genuinely different kind of codec:
 *   - Stateful: encoder/decoder instances carry internal prediction state across
 *     calls, so one instance must be reused for the life of a stream, not
 *     recreated per-chunk.
 *   - Strictly frame-based: every encode() call must be handed exactly one Opus
 *     frame duration's worth of samples (2.5/5/10/20/40/60 ms) - see
 *     FixedFrameBuffer, which re-slices AudioCaptureEngine's variable-size reads
 *     into frames this class can actually accept.
 *   - Operates on Short PCM internally (matches kopus's API), not raw bytes -
 *     this class does the byte<->short marshaling at the boundary so callers keep
 *     working with the same byte[] shape PcmCompressor already uses, and doesn't
 *     need to know which codec is active.
 *
 * One instance per stream direction (one for the sender's encoder, one for the
 * receiver's decoder) - NOT a shared object like PcmCompressor, since Opus state
 * is inherently per-connection.
 */
class OpusCodec private constructor(
    private val encoder: OpusEncoder?,
    private val decoder: OpusDecoder?,
    private val channels: Int
) {
    private val encodeOutputBuffer = ByteArray(OpusEncoder.DEFAULT_OUTPUT_BUFFER_SIZE)

    /**
     * Encodes exactly one frame of 16-bit PCM (frameSizeSamples samples per
     * channel — see FixedFrameBuffer.opusFrameSizeBytes for how the caller should
     * have sized the input to match). pcmBytes.size must equal
     * frameSizeSamples * channels * 2 (16-bit = 2 bytes/sample) — callers using
     * FixedFrameBuffer with a 16-bit format get this automatically.
     */
    fun encode(pcmBytes: ByteArray, frameSizeSamples: Int): ByteArray {
        val enc = encoder ?: error("OpusCodec was not created with encoding support")
        val shorts = bytesToShorts(pcmBytes)
        val length = enc.encode(shorts, frameSizeSamples, encodeOutputBuffer)
        return encodeOutputBuffer.copyOf(length)
    }

    /**
     * Decodes one Opus packet back into 16-bit PCM bytes. Pass data = null (with
     * the expected frameSizeSamples) to invoke Opus's built-in packet-loss
     * concealment for a frame that never arrived, instead of silence or dropping
     * the frame outright — kopus exposes this directly per the earlier research.
     */
    fun decode(data: ByteArray?, frameSizeSamples: Int): ByteArray {
        val dec = decoder ?: error("OpusCodec was not created with decoding support")
        val outShorts = ShortArray(frameSizeSamples * channels)
        val decodedSamples = dec.decode(data, frameSizeSamples, outShorts)
        return shortsToBytes(outShorts, decodedSamples * channels)
    }

    fun close() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
    }

    private fun bytesToShorts(bytes: ByteArray): ShortArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val shorts = ShortArray(bytes.size / 2)
        for (i in shorts.indices) shorts[i] = buf.short
        return shorts
    }

    private fun shortsToBytes(shorts: ShortArray, count: Int): ByteArray {
        val bytes = ByteArray(count * 2)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) buf.putShort(shorts[i])
        return bytes
    }

    companion object {
        /**
         * Creates an encoder-only instance for the sender side. sampleRateHz must
         * be one of Opus's supported rates (8k/12k/16k/24k/48k) — AudioBridge's
         * PcmFormat presets (44.1k/48k) mean 48kHz is the only one that lines up
         * directly; 44.1kHz content should be resampled or the format restricted
         * to 48kHz variants when Opus is selected (see MainViewModel wiring).
         */
        fun forEncoding(sampleRateHz: Int, stereo: Boolean): OpusCodec {
            val encoder = Opus.encoder(
                sampleRate = sampleRateFor(sampleRateHz),
                channels = if (stereo) Channels.STEREO else Channels.MONO,
                application = OpusApplication.AUDIO
            )
            return OpusCodec(encoder, null, if (stereo) 2 else 1)
        }

        /** Creates a decoder-only instance for the receiver side. */
        fun forDecoding(sampleRateHz: Int, stereo: Boolean): OpusCodec {
            val decoder = Opus.decoder(
                sampleRate = sampleRateFor(sampleRateHz),
                channels = if (stereo) Channels.STEREO else Channels.MONO
            )
            return OpusCodec(null, decoder, if (stereo) 2 else 1)
        }

        private fun sampleRateFor(hz: Int): SampleRate = when (hz) {
            8_000 -> SampleRate.RATE_8K
            12_000 -> SampleRate.RATE_12K
            16_000 -> SampleRate.RATE_16K
            24_000 -> SampleRate.RATE_24K
            48_000 -> SampleRate.RATE_48K
            else -> error(
                "Opus does not support ${hz}Hz directly (supported: 8k/12k/16k/24k/48k). " +
                    "AudioBridge should restrict PcmFormat choices to a 48kHz variant when Opus is selected."
            )
        }
    }
}
 
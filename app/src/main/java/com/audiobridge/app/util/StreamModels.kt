package com.audiobridge.app.util

/** Which role this device is playing in the current session. */
enum class DeviceRole {
    SENDER,   // Moto Edge 70 — captures system audio, streams it out
    RECEIVER  // Lenovo Tab 9 — receives the stream, plays it through speakers
}

/** Which physical link carries the audio bytes. All three are user-selectable; none required. */
enum class TransportMedium(val label: String) {
    HOTSPOT_WIFI("Hotspot / Wi-Fi"),
    WIFI_DIRECT("Wi-Fi Direct"),
    BLUETOOTH("Bluetooth")
}

/** Socket-level protocol. UDP for lowest latency, TCP if you want guaranteed delivery. */
enum class SocketProtocol(val label: String) {
    UDP("UDP (lowest latency)"),
    TCP("TCP (reliable)")
}

/** The three PCM quality presets you asked to be able to toggle between. */
enum class PcmFormat(
    val label: String,
    val sampleRateHz: Int,
    val bitDepth: Int
) {
    PCM_16_44("16-bit / 44.1kHz", 44_100, 16),
    PCM_16_48("16-bit / 48kHz", 48_000, 16),
    PCM_32_48("32-bit / 48kHz (max quality)", 48_000, 32);

    /** Raw bytes/sec this format pushes down the wire, mono or stereo. */
    fun bytesPerSecond(channels: Int = 2): Int =
        sampleRateHz * (bitDepth / 8) * channels
}

/**
 * One chunk of raw captured PCM audio plus how many bytes of `data` are actually
 * valid. `data` may be a reused, larger-than-`length` buffer that the capture engine
 * keeps writing into on every read — every consumer must only read the first
 * `length` bytes and must not retain the reference past the call it was passed to,
 * since it will be overwritten on the next capture iteration.
 */
data class PcmChunk(val data: ByteArray, val length: Int) {
    // Compares/hashes in place rather than via data.copyOf(length).contentEquals(...)
    // — the copyOf() approach allocates a fresh array on every single call purely to
    // hand it to contentEquals()/contentHashCode(). Nothing in this codebase
    // currently invokes equals()/hashCode() on a per-chunk basis on the hot capture
    // path (no Set/Map keying, no distinctUntilChanged() on a chunk-emitting Flow),
    // but these are the auto-eligible data-class overrides and a future caller (a
    // test, a dedup operator someone adds later) could trigger them tens of times a
    // second without anyone noticing the allocation. Looping over `length` compares/
    // hashes the exact same logical range with zero extra allocation.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmChunk) return false
        if (length != other.length) return false
        for (i in 0 until length) {
            if (data[i] != other.data[i]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = 1
        for (i in 0 until length) result = 31 * result + data[i]
        result = 31 * result + length
        return result
    }
}

enum class ConnectionState {
    IDLE,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    STREAMING,
    ERROR
}

/**
 * Qualitative connection health, derived from jitter and packet loss rather than
 * the (fundamentally unreliable — see StreamStats.latencyMs doc comment) raw
 * latency number. This is what the UI should show as the headline indicator.
 */
enum class ConnectionQuality(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    POOR("Poor"),
    UNKNOWN("—")
}

/** Live stats shown in the UI while streaming. */
data class StreamStats(
    // NOTE: this is NOT a reliable measure of real network latency. It's computed by
    // comparing the sender's clock to the receiver's clock, and the two devices'
    // clocks are never synchronized (no NTP/handshake exists in this codebase) — what
    // this number actually reflects is clock DRIFT between the two devices since the
    // stream started, which can easily read negative or hover near zero by pure
    // coincidence. Kept around for debugging/curiosity, but the UI should prefer
    // `quality` (derived from jitter + packet loss, which ARE meaningful without
    // synchronized clocks) as the real-world connection health indicator.
    val latencyMs: Double = 0.0,
    val jitterMs: Double = 0.0,
    val bitrateKbps: Double = 0.0,
    val packetsLost: Long = 0,
    val packetsReceived: Long = 0,
    val packetsSent: Long = 0
) {
    /**
     * Derives a user-facing quality rating purely from jitter (consecutive-packet
     * timing variance — meaningful without synchronized clocks, unlike latencyMs
     * above) and loss rate. Thresholds are deliberately conservative for a local
     * Wi-Fi/Bluetooth link at close range — real network latency doesn't factor in
     * since it can't be measured reliably here.
     */
    val quality: ConnectionQuality
        get() {
            val totalExpected = packetsReceived + packetsLost
            if (totalExpected <= 0) return ConnectionQuality.UNKNOWN
            val lossRate = packetsLost.toDouble() / totalExpected

            return when {
                lossRate > 0.15 || jitterMs > 80.0 -> ConnectionQuality.POOR
                lossRate > 0.05 || jitterMs > 40.0 -> ConnectionQuality.FAIR
                lossRate > 0.01 || jitterMs > 15.0 -> ConnectionQuality.GOOD
                else -> ConnectionQuality.EXCELLENT
            }
        }
}

/** A discovered peer, found via mDNS in either direction. */
data class DiscoveredDevice(
    val name: String,
    val host: String,
    val port: Int,
    val role: DeviceRole // role the *other* device is advertising
)

/** Everything needed to remember + auto-reconnect to the last session. */
data class ConnectionConfig(
    val role: DeviceRole = DeviceRole.SENDER,
    val transport: TransportMedium = TransportMedium.HOTSPOT_WIFI,
    val protocol: SocketProtocol = SocketProtocol.UDP,
    val pcmFormat: PcmFormat = PcmFormat.PCM_16_48,
    val lastDeviceName: String = "",
    val lastDeviceHost: String = "",
    val lastDevicePort: Int = 0,
    val volume: Float = 1.0f,
    // Jitter buffer target, in ms — larger smooths a rougher link at the cost of more
    // lag behind the source; smaller tightens sync but risks audible underrun glitches.
    val safetyBufferMs: Int = 120
)
 

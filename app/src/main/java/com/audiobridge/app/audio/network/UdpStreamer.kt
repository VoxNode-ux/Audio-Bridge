package com.audiobridge.app.network

import android.util.Log
import com.audiobridge.app.util.StreamStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

private const val TAG = "UdpStreamer"

/**
 * Wire format: an 8-byte header in front of every raw PCM chunk —
 *   [0..3]  sequence number (Int, big-endian) — detects lost/out-of-order packets
 *   [4..7]  send timestamp, low 32 bits of System.nanoTime()/1000 (Int) — for latency math
 * followed by the raw PCM bytes. Kept tiny on purpose: header overhead should be
 * invisible next to a payload of ~a few KB per packet.
 */
private const val HEADER_SIZE = 8
const val UDP_DEFAULT_PORT = 45778

class UdpSender(private val targetHost: String, private val targetPort: Int = UDP_DEFAULT_PORT) {

    private val socket = DatagramSocket()
    private var sequenceNumber = 0

    // Resolved once (see resolveTarget()), not on every send() — InetAddress.getByName()
    // does real parsing/validation work (and can trigger an actual DNS lookup when
    // targetHost isn't a literal IP) on every call. The old code called it on every
    // single send(), which runs tens of times a second on the hottest path in the
    // app, for a target that never changes for the lifetime of this sender.
    @Volatile private var resolvedAddress: InetAddress? = null

    // Reused scratch buffers. send() is only ever invoked sequentially by the single
    // coroutine collecting the capture flow (see AudioStreamService's sending loop —
    // Flow.emit() suspends the producer until the collector lambda returns), so
    // reusing these across calls is safe and avoids allocating a fresh ByteBuffer,
    // ByteArray, and DatagramPacket on every single audio chunk, same rationale as
    // AudioCaptureEngine's reused capture buffer.
    private val headerScratch = ByteBuffer.allocate(HEADER_SIZE)
    private var packetScratch = ByteArray(0)

    /** Resolves and caches the target address ahead of the first send(). Returns
     *  false (rather than throwing) on failure so callers can surface a clean
     *  connect-failed state instead of crashing the sending coroutine. */
    fun resolveTarget(): Boolean {
        return try {
            resolvedAddress = InetAddress.getByName(targetHost)
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP target resolve failed: ${e.message}")
            false
        }
    }

    fun send(payload: ByteArray, length: Int) {
        // Falls back to a lazy one-off resolve if resolveTarget() was never called
        // (defensive — AudioTransport.connect() is expected to call it first via
        // UdpSenderTransport below, but send() shouldn't hard-fail just because a
        // caller skipped that step).
        val address = resolvedAddress ?: (runCatching { InetAddress.getByName(targetHost) }
            .onFailure { Log.e(TAG, "UDP send failed to resolve target: ${it.message}") }
            .getOrNull() ?: return).also { resolvedAddress = it }

        headerScratch.clear()
        headerScratch.putInt(sequenceNumber++)
        headerScratch.putInt((System.nanoTime() / 1000L).toInt())

        val totalLength = HEADER_SIZE + length
        if (packetScratch.size < totalLength) {
            packetScratch = ByteArray(totalLength)
        }
        System.arraycopy(headerScratch.array(), 0, packetScratch, 0, HEADER_SIZE)
        System.arraycopy(payload, 0, packetScratch, HEADER_SIZE, length)

        val packet = DatagramPacket(packetScratch, totalLength, address, targetPort)
        runCatching { socket.send(packet) }
            .onFailure { Log.e(TAG, "UDP send failed: ${it.message}") }
    }

    fun close() {
        runCatching { socket.close() }
    }
}

data class UdpReceivedChunk(val data: ByteArray, val length: Int, val stats: StreamStats)

class UdpReceiver(private val listenPort: Int = UDP_DEFAULT_PORT) {

    private var socket: DatagramSocket? = null
    @Volatile private var isListening = false

    private var packetsReceived = 0L
    private var packetsLost = 0L
    private var lastLatencies = ArrayDeque<Double>(20)

    // Sequence tracking for out-of-order/duplicate detection. Using "highest seen"
    // rather than "expected next" avoids a class of bugs where a late/reordered
    // packet moves the expectation backward and corrupts loss accounting for every
    // packet after it (see doc comment in the receive loop below).
    private var highestSeqSeen = 0
    private var hasReceivedFirst = false

    /** Wraparound-safe sequence comparison (same trick TCP uses): works correctly
     *  even after the 32-bit counter wraps back through zero, as long as the true
     *  gap between the two sequence numbers being compared is under 2^31. */
    private fun isNewer(a: Int, b: Int): Boolean = (a - b) > 0

    fun listen(): Flow<UdpReceivedChunk> = flow {
        val sock = DatagramSocket(listenPort).apply { soTimeout = 2000 }
        socket = sock
        isListening = true

        // Generous buffer: 32-bit/48kHz stereo needs ~384KB/s; this comfortably covers
        // a single UDP datagram's worth of audio plus header with room to spare.
        val buffer = ByteArray(65_507)

        while (isListening) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (e: java.net.SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (isListening) Log.e(TAG, "UDP receive error: ${e.message}")
                continue
            }

            if (packet.length < HEADER_SIZE) continue

            val bb = ByteBuffer.wrap(packet.data, packet.offset, HEADER_SIZE)
            val seq = bb.int
            val sentTimestampUs = bb.int.toLong() and 0xFFFFFFFFL
            // Truncate to the SAME 32-bit unsigned space the sender's timestamp was
            // packed into before diffing. Subtracting a 32-bit-wrapped remote value
            // from a raw, unbounded local nanoTime() meant this "latency" grew with
            // however long each device happened to have been powered on (nanoTime()
            // is monotonic since an arbitrary per-device reference point, never
            // synchronized across devices) — occasionally producing wildly wrong
            // multi-minute readings, and spurious jitter spikes whenever that
            // asymmetry crossed a 2^32-microsecond boundary mid-session. Masking
            // both operands the same way keeps this a bounded, wraparound-safe
            // circular difference, matching what the wire format actually carries.
            val nowLow32Us = (System.nanoTime() / 1000L) and 0xFFFFFFFFL
            val latencyMs = ((nowLow32Us - sentTimestampUs) and 0xFFFFFFFFL) / 1000.0

            packetsReceived++

            // Out-of-order / stale packet handling. The previous version always set
            // expectedSeq = seq + 1 regardless of ordering, so a single late/reordered
            // packet would move the expectation BACKWARD, corrupting loss counting for
            // every subsequent packet — and, more importantly, every packet was played
            // in raw arrival order with no protection at all, so a reordered packet
            // got written into the playback ring buffer out of temporal order, which is
            // exactly what produces an audible "zap." Now: only packets newer than
            // anything seen so far advance the high-water mark and get played; anything
            // older (late/duplicate) is dropped rather than played backward in time.
            val dropStale: Boolean
            if (!hasReceivedFirst) {
                hasReceivedFirst = true
                highestSeqSeen = seq
                dropStale = false
            } else {
                val diff = seq - highestSeqSeen
                if (isNewer(seq, highestSeqSeen)) {
                    if (diff > 1) packetsLost += (diff - 1)
                    highestSeqSeen = seq
                    dropStale = false
                } else {
                    dropStale = true
                }
            }

            if (dropStale) {
                Log.w(TAG, "Dropping stale/out-of-order UDP packet (seq=$seq, highest=$highestSeqSeen)")
                continue
            }

            if (lastLatencies.size >= 20) lastLatencies.removeFirst()
            lastLatencies.addLast(latencyMs)
            val avgLatency = lastLatencies.average()
            val jitter = if (lastLatencies.size > 1) {
                lastLatencies.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()
            } else 0.0

            val payloadLength = packet.length - HEADER_SIZE
            val payload = ByteArray(payloadLength)
            System.arraycopy(packet.data, packet.offset + HEADER_SIZE, payload, 0, payloadLength)

            emit(
                UdpReceivedChunk(
                    data = payload,
                    length = payloadLength,
                    stats = StreamStats(
                        latencyMs = avgLatency,
                        jitterMs = jitter,
                        packetsLost = packetsLost,
                        packetsReceived = packetsReceived
                    )
                )
            )
        }
        sock.close()
    }

    fun stop() {
        isListening = false
        runCatching { socket?.close() }
    }
}

/**
 * Adapts the existing UdpSender to the AudioTransport interface. UDP is connectionless,
 * so there's no wire handshake to perform before send() works — but connect() is still
 * meaningful now: it resolves the target address once, off the sending coroutine's hot
 * path, and returns false (surfacing a clean ERROR/retry instead of a silently-dropped
 * first packet) if that resolution fails.
 */
class UdpSenderTransport(
    targetHost: String,
    targetPort: Int = UDP_DEFAULT_PORT
) : AudioTransport {

    private val sender = UdpSender(targetHost, targetPort)

    override suspend fun connect(): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { sender.resolveTarget() }

    override suspend fun send(data: ByteArray, length: Int) = sender.send(data, length)
    override fun listen(): Flow<TransportChunk> = flow { /* sender doesn't listen */ }
    override fun close() = sender.close()
}

/** Adapts the existing UdpReceiver to the AudioTransport interface. */
class UdpReceiverTransport(
    listenPort: Int = UDP_DEFAULT_PORT
) : AudioTransport {

    private val receiver = UdpReceiver(listenPort)

    override suspend fun connect(): Boolean = true
    override suspend fun send(data: ByteArray, length: Int) { /* receiver doesn't send */ }
    override fun listen(): Flow<TransportChunk> =
        receiver.listen().map { chunk -> TransportChunk(chunk.data, chunk.length, chunk.stats) }
    override fun close() = receiver.stop()
}
 
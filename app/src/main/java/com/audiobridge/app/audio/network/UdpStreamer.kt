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
 * Wire format: an 8-byte header in front of every raw PCM chunk -
 *   [0..3]  sequence number (Int, big-endian) - detects lost/out-of-order packets
 *   [4..7]  send timestamp, low 32 bits of System.nanoTime()/1000 (Int) - for latency math
 * followed by the raw PCM bytes. Kept tiny on purpose: header overhead should be
 * invisible next to a payload of ~a few KB per packet.
 */
private const val HEADER_SIZE = 8
const val UDP_DEFAULT_PORT = 45778

class UdpSender(private val targetHost: String, private val targetPort: Int = UDP_DEFAULT_PORT) {

    private val socket = DatagramSocket()
    private var sequenceNumber = 0
    private var packetsSent = 0L

    @Volatile private var resolvedAddress: InetAddress? = null

    private val headerScratch = ByteBuffer.allocate(HEADER_SIZE)
    private var packetScratch = ByteArray(0)

    fun resolveTarget(): Boolean {
        return try {
            resolvedAddress = InetAddress.getByName(targetHost)
            true
        } catch (e: Exception) {
            Log.e(TAG, "UDP target resolve failed: ${e.message}")
            false
        }
    }

    fun send(payload: ByteArray, length: Int): Long {
        val address = resolvedAddress ?: (runCatching { InetAddress.getByName(targetHost) }
            .onFailure { Log.e(TAG, "UDP send failed to resolve target: ${it.message}") }
            .getOrNull() ?: return packetsSent).also { resolvedAddress = it }

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
            .onSuccess { packetsSent++ }
            .onFailure { Log.e(TAG, "UDP send failed: ${it.message}") }
        return packetsSent
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

    private var highestSeqSeen = 0
    private var hasReceivedFirst = false

    // Clock-offset calibration: the sender and receiver's System.nanoTime() values
    // are each relative to an arbitrary, unrelated per-device reference point (usually
    // boot time) - they are never synchronized with each other. A raw diff between
    // the two is therefore not latency at all, it's just the random phase offset
    // between two unrelated clocks, wrapped into a ~71.6-minute window by the 32-bit
    // header field - which is exactly why this used to show nonsense values like
    // 549907 ms. There is no way to learn the TRUE one-way delay from this alone,
    // but for a display stat, what actually matters is CHANGE over time, not the
    // absolute number. Recording the very first packet's raw diff as a baseline and
    // subtracting it from every later reading turns that meaningless constant offset
    // into a number that starts near zero and only moves when real delay changes -
    // which is what "Latency" should communicate on this screen.
    private var baselineOffsetUs: Long? = null

    private fun isNewer(a: Int, b: Int): Boolean = (a - b) > 0

    fun listen(): Flow<UdpReceivedChunk> = flow {
        val sock = DatagramSocket(listenPort).apply { soTimeout = 2000 }
        socket = sock
        isListening = true

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
            val nowLow32Us = (System.nanoTime() / 1000L) and 0xFFFFFFFFL
            val rawOffsetUs = (nowLow32Us - sentTimestampUs) and 0xFFFFFFFFL

            // First packet of the session establishes the baseline; every later
            // packet's latency is reported relative to that baseline instead of
            // as the raw (meaningless) absolute offset.
            val baseline = baselineOffsetUs ?: rawOffsetUs.also { baselineOffsetUs = it }
            val relativeOffsetUs = (rawOffsetUs - baseline) and 0xFFFFFFFFL
            // The relative offset is itself a wraparound-safe unsigned value, so a
            // small amount of negative drift (the receiver's clock running fractionally
            // faster than the sender's since the baseline was taken) would otherwise
            // wrap around to a huge positive number instead of a small negative one.
            // Treating anything past the halfway point of the 32-bit space as negative
            // unwraps that correctly.
            val signedOffsetUs = if (relativeOffsetUs > 0x7FFFFFFFL) {
                relativeOffsetUs - 0x100000000L
            } else {
                relativeOffsetUs
            }
            val latencyMs = signedOffsetUs / 1000.0

            packetsReceived++

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
        baselineOffsetUs = null
        runCatching { socket?.close() }
    }
}

/**
 * Adapts the existing UdpSender to the AudioTransport interface. UDP is connectionless,
 * so there's no wire handshake to perform before send() works - but connect() is still
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
    override suspend fun send(data: ByteArray, length: Int): Long = 0 // receiver doesn't send
    override fun listen(): Flow<TransportChunk> =
        receiver.listen().map { chunk -> TransportChunk(chunk.data, chunk.length, chunk.stats) }
    override fun close() = receiver.stop()
}
 
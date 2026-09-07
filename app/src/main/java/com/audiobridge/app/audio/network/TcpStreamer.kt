package com.audiobridge.app.network

import android.util.Log
import com.audiobridge.app.util.StreamStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "TcpStreamer"
const val TCP_DEFAULT_PORT = 45779

/**
 * TCP path: each chunk is length-prefixed (4-byte Int) since TCP is a byte stream with
 * no built-in framing. Slightly higher latency than UDP (ordering + retransmission
 * guarantees aren't free) but nothing gets silently dropped — useful if your hotspot
 * link is flaky and glitches bother you more than a few extra ms of lag.
 */
class TcpSender(private val targetHost: String, private val targetPort: Int = TCP_DEFAULT_PORT) {

    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private var packetsSent = 0L

    fun connect(): Boolean {
        return try {
            val s = Socket(targetHost, targetPort)
            s.tcpNoDelay = true // disable Nagle's algorithm — don't batch-wait small audio chunks
            socket = s
            out = DataOutputStream(s.getOutputStream())
            true
        } catch (e: Exception) {
            Log.e(TAG, "TCP connect failed: ${e.message}")
            false
        }
    }

    fun send(payload: ByteArray, length: Int) {
        // Rethrow rather than swallow: TCP is connection-oriented, so a failed write
        // means the connection is genuinely dead (unlike UDP, where a single dropped
        // datagram is normal and shouldn't kill the stream). AudioStreamService's
        // sending loop treats an exception here as a drop worth retrying — swallowing
        // it silently instead meant a dead TCP connection just stopped sending audio
        // with no retry and no visible ERROR state.
        out?.apply {
            writeInt(length)
            write(payload, 0, length)
            flush()
        } ?: throw java.io.IOException("TCP output stream not connected")
    }

    fun close() {
        runCatching { out?.close() }
        runCatching { socket?.close() }
    }
}

data class TcpReceivedChunk(val data: ByteArray, val length: Int, val stats: StreamStats)

class TcpReceiver(private val listenPort: Int = TCP_DEFAULT_PORT) {

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    @Volatile private var isListening = false
    private var packetsReceived = 0L

    fun listen(): Flow<TcpReceivedChunk> = flow {
        val server = ServerSocket(listenPort)
        serverSocket = server
        isListening = true

        Log.i(TAG, "TCP server listening on port $listenPort, waiting for sender...")
        val client = server.accept()
        client.tcpNoDelay = true
        clientSocket = client
        Log.i(TAG, "TCP client connected: ${client.inetAddress.hostAddress}")

        val input = DataInputStream(client.getInputStream())
        val arrivalTimes = ArrayDeque<Long>(20)

        try {
            while (isListening) {
                val length = input.readInt()
                if (length <= 0 || length > 1_000_000) {
                    Log.e(TAG, "Suspicious frame length $length, dropping connection")
                    break
                }
                val payload = ByteArray(length)
                input.readFully(payload)

                packetsReceived++
                val now = System.currentTimeMillis()
                if (arrivalTimes.size >= 20) arrivalTimes.removeFirst()
                arrivalTimes.addLast(now)
                val jitter = if (arrivalTimes.size > 1) {
                    arrivalTimes.zipWithNext { a, b -> kotlin.math.abs((b - a).toDouble()) }.average()
                } else 0.0

                emit(
                    TcpReceivedChunk(
                        data = payload,
                        length = length,
                        stats = StreamStats(
                            latencyMs = 0.0, // TCP path doesn't carry a send timestamp; jitter still useful
                            jitterMs = jitter,
                            packetsLost = 0, // TCP guarantees delivery — no loss to report
                            packetsReceived = packetsReceived
                        )
                    )
                )
            }
        } catch (e: Exception) {
            // Only swallow when this is an INTENTIONAL stop (isListening already
            // flipped false by stop() before its socket.close() unblocked this
            // readInt()/readFully()) — that produces the same IOException shape as a
            // genuine drop and shouldn't be treated as one. A genuine drop (isListening
            // still true here) must propagate: the flow simply completing here — the
            // previous behavior — looked identical to "the user called stop()" to
            // AudioStreamService's collector, so a dead TCP connection just ended the
            // session silently, with connectionState stuck at STREAMING forever
            // instead of triggering retryOrFail()'s retry-then-ERROR path.
            if (isListening) {
                Log.e(TAG, "TCP receive error: ${e.message}")
                throw e
            } else {
                Log.d(TAG, "TCP listen loop exiting after intentional stop()")
            }
        } finally {
            runCatching { input.close() }
        }
    }

    fun stop() {
        isListening = false
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
    }
}

/**
 * Adapts TcpSender to AudioTransport. connect() wraps the blocking Socket() constructor
 * in withContext(Dispatchers.IO) — the underlying TcpSender.connect() is a plain blocking
 * call, and calling it directly from a suspend function without dispatching would block
 * whatever thread is currently running the coroutine.
 */
class TcpSenderTransport(
    targetHost: String,
    targetPort: Int = TCP_DEFAULT_PORT
) : AudioTransport {

    private val sender = TcpSender(targetHost, targetPort)

    override suspend fun connect(): Boolean =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { sender.connect() }

    override suspend fun send(data: ByteArray, length: Int) = sender.send(data, length)
    override fun listen(): Flow<TransportChunk> = flow { /* sender doesn't listen */ }
    override fun close() = sender.close()
}

/** Adapts TcpReceiver to AudioTransport. */
class TcpReceiverTransport(
    listenPort: Int = TCP_DEFAULT_PORT
) : AudioTransport {

    private val receiver = TcpReceiver(listenPort)

    override suspend fun connect(): Boolean = true
    override suspend fun send(data: ByteArray, length: Int) { /* receiver doesn't send */ }
    override fun listen(): Flow<TransportChunk> =
        receiver.listen().map { chunk -> TransportChunk(chunk.data, chunk.length, chunk.stats) }
    override fun close() = receiver.stop()
}
 
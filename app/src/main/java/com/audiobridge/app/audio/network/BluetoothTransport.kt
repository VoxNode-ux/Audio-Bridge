package com.audiobridge.app.network

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.audiobridge.app.audio.PcmCompressor
import com.audiobridge.app.util.StreamStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

private const val TAG = "BluetoothTransport"

// Fixed app-specific RFCOMM UUID — both devices must agree on this to find each
// other's service record; arbitrary but must match sender and receiver exactly.
private val AUDIOBRIDGE_UUID: UUID = UUID.fromString("8f4a2b10-6e3d-4c9a-9f21-a1b2c3d4e5f6")
private const val SDP_SERVICE_NAME = "AudioBridgeStream"

/**
 * RFCOMM Bluetooth Classic transport. Standard Bluetooth tops out around 1-2 Mbps,
 * which is comfortably under what 16-bit/44.1kHz stereo needs (~1.4 Mbps) but leaves
 * very little headroom — 16-bit/48kHz (~1.5 Mbps) is tight and 32-bit/48kHz (~3 Mbps)
 * will not fit. Callers should steer users toward the lower PCM formats when
 * Bluetooth is the selected transport; this class does not downsample on its own,
 * since silently changing audio quality out from under a user-selected PCM format
 * would be a worse surprise than an occasional buffer underrun.
 */
class BluetoothSenderTransport(
    private val adapter: BluetoothAdapter,
    private val targetDevice: BluetoothDevice,
    private val pcmBitDepth: Int = 16
) : AudioTransport {

    private var socket: BluetoothSocket? = null
    private var out: DataOutputStream? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Boolean {
        return try {
            adapter.cancelDiscovery() // discovery in progress slows the connect handshake
            val s = targetDevice.createRfcommSocketToServiceRecord(AUDIOBRIDGE_UUID)
            s.connect()
            socket = s
            out = DataOutputStream(s.outputStream)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Bluetooth connect failed: ${e.message}")
            false
        }
    }

    override suspend fun send(data: ByteArray, length: Int) {
        // Rethrow rather than swallow: AudioStreamService's sending loop wraps
        // engine.start().collect { transport.send(...) } in a try/catch that treats
        // any exception here as a drop worth retrying. Swallowing the error instead
        // (the previous behavior) meant a broken Bluetooth link failed silently —
        // audio just stopped arriving with no retry and no visible ERROR state.
        val compressed = PcmCompressor.compress(data, length, pcmBitDepth)
        out?.apply {
            writeInt(compressed.size)
            write(compressed, 0, compressed.size)
            flush()
        } ?: throw java.io.IOException("Bluetooth output stream not connected")
    }

    override fun listen(): Flow<TransportChunk> = flow {
        // Sender never listens; this transport is send-only on this side.
    }

    override fun close() {
        runCatching { out?.close() }
        runCatching { socket?.close() }
    }
}

/**
 * Receiver side: accepts one incoming RFCOMM connection. Bluetooth must already be
 * paired/bonded between the two devices before this will succeed — unlike Wi-Fi
 * hotspot/Direct, RFCOMM has no equivalent of "just connect," pairing is a one-time
 * manual step done in Android's Bluetooth settings first.
 */
class BluetoothReceiverTransport(
    private val adapter: BluetoothAdapter,
    private val pcmBitDepth: Int = 16
) : AudioTransport {

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    @Volatile private var isListening = false

    override suspend fun connect(): Boolean = true // receiver has nothing to dial out to

    override suspend fun send(data: ByteArray, length: Int) {
        // Receiver never sends; audio flow in this app is one-directional (sender-only).
    }

    @SuppressLint("MissingPermission")
    override fun listen(): Flow<TransportChunk> = flow {
        val server = adapter.listenUsingRfcommWithServiceRecord(SDP_SERVICE_NAME, AUDIOBRIDGE_UUID)
        serverSocket = server
        isListening = true

        Log.i(TAG, "Bluetooth RFCOMM listening, waiting for sender to pair+connect…")
        val client = server.accept() // blocks until the sender connects
        clientSocket = client
        Log.i(TAG, "Bluetooth client connected: ${client.remoteDevice?.name}")

        val input = DataInputStream(client.inputStream)
        var packetsReceived = 0L
        val arrivalTimes = ArrayDeque<Long>(20)

        try {
            while (isListening) {
                val compressedLength = input.readInt()
                if (compressedLength <= 0 || compressedLength > 1_000_000) {
                    Log.e(TAG, "Suspicious frame length $compressedLength, dropping connection")
                    break
                }
                val compressed = ByteArray(compressedLength)
                input.readFully(compressed)
                val payload = PcmCompressor.decompress(compressed, pcmBitDepth)

                packetsReceived++
                val now = System.currentTimeMillis()
                if (arrivalTimes.size >= 20) arrivalTimes.removeFirst()
                arrivalTimes.addLast(now)
                val jitter = if (arrivalTimes.size > 1) {
                    arrivalTimes.zipWithNext { a, b -> kotlin.math.abs((b - a).toDouble()) }.average()
                } else 0.0

                emit(
                    TransportChunk(
                        data = payload,
                        length = payload.size,
                        stats = StreamStats(
                            latencyMs = 0.0,
                            jitterMs = jitter,
                            packetsLost = 0,
                            packetsReceived = packetsReceived
                        )
                    )
                )
            }
        } catch (e: Exception) {
            // Same reasoning as TcpReceiver.listen(): only swallow when isListening
            // was already flipped false by an intentional stop() before the socket
            // close that unblocked this read. A genuine drop (isListening still true)
            // has to propagate, or a broken Bluetooth link silently ends the session
            // — connectionState stuck at STREAMING — instead of triggering
            // AudioStreamService's retryOrFail() retry-then-ERROR path.
            if (isListening) {
                Log.e(TAG, "Bluetooth receive error: ${e.message}")
                throw e
            } else {
                Log.d(TAG, "Bluetooth listen loop exiting after intentional stop()")
            }
        } finally {
            runCatching { input.close() }
        }
    }

    override fun close() {
        isListening = false
        runCatching { clientSocket?.close() }
        runCatching { serverSocket?.close() }
    }
} 

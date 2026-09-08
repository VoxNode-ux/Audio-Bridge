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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

private const val TAG = "BluetoothTransport"

// Fixed app-specific RFCOMM UUID - both devices must agree on this to find each
// other's service record; arbitrary but must match sender and receiver exactly.
private val AUDIOBRIDGE_UUID: UUID = UUID.fromString("8f4a2b10-6e3d-4c9a-9f21-a1b2c3d4e5f6")
private const val SDP_SERVICE_NAME = "AudioBridgeStream"

// Bounds how long a single connect attempt (standard OR fallback path) is allowed
// to hang before we give up and let AudioStreamService's retry loop try again -
// without this, a stuck SDP lookup on some OEM Bluetooth stacks can block for the
// full underlying OS timeout (up to ~12s) with no visible feedback to the user.
private const val CONNECT_TIMEOUT_MS = 6000L

/**
 * RFCOMM Bluetooth Classic transport. Standard Bluetooth tops out around 1-2 Mbps,
 * which is comfortably under what 16-bit/44.1kHz stereo needs (~1.4 Mbps) but leaves
 * very little headroom - 16-bit/48kHz (~1.5 Mbps) is tight and 32-bit/48kHz (~3 Mbps)
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
    private var packetsSent = 0L

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Boolean {
        adapter.cancelDiscovery() // discovery in progress slows the connect handshake

        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            attemptConnect { targetDevice.createRfcommSocketToServiceRecord(AUDIOBRIDGE_UUID) }
                ?: run {
                    Log.w(TAG, "Standard RFCOMM connect failed/timed out, trying fallback channel")
                    attemptConnect { createFallbackSocket(targetDevice) }
                }
        }

        return if (connected != null) {
            socket = connected
            out = DataOutputStream(connected.outputStream)
            true
        } else {
            Log.e(TAG, "Bluetooth connect failed after standard + fallback attempts")
            false
        }
    }

    @SuppressLint("MissingPermission")
    private fun attemptConnect(makeSocket: () -> BluetoothSocket): BluetoothSocket? {
        return try {
            val s = makeSocket()
            s.connect()
            s
        } catch (e: Exception) {
            Log.e(TAG, "RFCOMM attempt failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun createFallbackSocket(device: BluetoothDevice): BluetoothSocket {
        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
        return method.invoke(device, 1) as BluetoothSocket
    }

    override suspend fun send(data: ByteArray, length: Int): Long {
        val compressed = PcmCompressor.compress(data, length, pcmBitDepth)
        out?.apply {
            writeInt(compressed.size)
            write(compressed, 0, compressed.size)
            flush()
        } ?: throw java.io.IOException("Bluetooth output stream not connected")
        packetsSent++
        return packetsSent
    }

    override fun listen(): Flow<TransportChunk> = flow {
        // Sender never listens; this transport is send-only on this side.
    }

    override fun close() {
        runCatching { out?.close() }
        runCatching { socket?.close() }
    }
}

class BluetoothReceiverTransport(
    private val adapter: BluetoothAdapter,
    private val pcmBitDepth: Int = 16
) : AudioTransport {

    private var serverSocket: BluetoothServerSocket? = null
    private var clientSocket: BluetoothSocket? = null
    @Volatile private var isListening = false

    override suspend fun connect(): Boolean = true

    override suspend fun send(data: ByteArray, length: Int): Long {
        // Receiver never sends; audio flow in this app is one-directional (sender-only).
        return 0
    }

    @SuppressLint("MissingPermission")
    override fun listen(): Flow<TransportChunk> = flow {
        val server = adapter.listenUsingRfcommWithServiceRecord(SDP_SERVICE_NAME, AUDIOBRIDGE_UUID)
        serverSocket = server
        isListening = true

        Log.i(TAG, "Bluetooth RFCOMM listening, waiting for sender to pair+connect...")
        val client = server.accept()
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
 

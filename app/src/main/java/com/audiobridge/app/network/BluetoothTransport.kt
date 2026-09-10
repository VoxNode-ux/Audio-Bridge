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

// 4-byte magic marker prefixed before every frame's [sequence][length] header.
// RFCOMM is a byte stream with no inherent framing - if even a single byte is
// ever corrupted, dropped, or duplicated in transit (which does happen on
// Bluetooth Classic, including at very close range where two radios can
// self-saturate each other), a plain length-prefixed protocol has no way to
// tell "this is a real frame length" from "this is garbage left over from a
// desync" - it just reads whatever bytes are next as if they were a valid
// header, which either kills the whole connection outright or, worse, reads
// the wrong number of bytes into Inflater and produces audible garbage that
// keeps compounding on every subsequent frame because the stream never
// recovers alignment. Scanning forward for this marker after any suspicious
// frame lets the receiver resynchronize to the next real frame boundary
// instead of tearing down the whole connection or decoding corrupted data.
private val FRAME_MAGIC = byteArrayOf(0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(), 0x01)
private const val MAX_FRAME_BYTES = 1_000_000
private const val MAX_RESYNC_SCAN_BYTES = 65_536

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
    private var sequenceNumber = 0

    @SuppressLint("MissingPermission")
    override suspend fun connect(): Boolean {
        adapter.cancelDiscovery()

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
            sequenceNumber = 0
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
            write(FRAME_MAGIC)
            writeInt(sequenceNumber++)
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

    /**
     * Scans forward byte-by-byte on the input stream until it finds FRAME_MAGIC,
     * discarding everything before it. Called whenever the stream's framing is
     * suspect (a bogus length, a decompress failure) instead of tearing down the
     * whole connection - RFCOMM guarantees in-order, at-most-corrupted bytes, not
     * that this app's own framing stays aligned after a glitch, so resyncing to
     * the next known-good marker is what actually recovers a session instead of
     * reconnecting (which drops far more audio than a brief resync scan does).
     * Returns false if no marker is found within the scan cap, meaning the link
     * is bad enough that reconnecting is the better move.
     */
    private fun resyncToNextFrame(input: DataInputStream): Boolean {
        var matched = 0
        var scanned = 0
        while (scanned < MAX_RESYNC_SCAN_BYTES) {
            val b = input.readUnsignedByte()
            scanned++
            if (b.toByte() == FRAME_MAGIC[matched]) {
                matched++
                if (matched == FRAME_MAGIC.size) return true
            } else {
                matched = if (b.toByte() == FRAME_MAGIC[0]) 1 else 0
            }
        }
        return false
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
        var packetsLost = 0L
        var highestSeqSeen = -1
        var hasReceivedFirst = false
        val arrivalTimes = ArrayDeque<Long>(20)
        val magicBuf = ByteArray(FRAME_MAGIC.size)

        try {
            while (isListening) {
                input.readFully(magicBuf)
                if (!magicBuf.contentEquals(FRAME_MAGIC)) {
                    Log.w(TAG, "Frame magic mismatch, resyncing")
                    if (!resyncToNextFrame(input)) {
                        Log.e(TAG, "Resync scan exhausted without finding a frame, dropping connection")
                        break
                    }
                }

                val seq = input.readInt()
                val compressedLength = input.readInt()
                if (compressedLength <= 0 || compressedLength > MAX_FRAME_BYTES) {
                    Log.w(TAG, "Suspicious frame length $compressedLength after valid magic, resyncing")
                    if (!resyncToNextFrame(input)) {
                        Log.e(TAG, "Resync scan exhausted without finding a frame, dropping connection")
                        break
                    }
                    continue
                }

                val compressed = ByteArray(compressedLength)
                input.readFully(compressed)

                val payload = try {
                    PcmCompressor.decompress(compressed, pcmBitDepth)
                } catch (e: Exception) {
                    // A corrupted frame that still passed the length sanity check -
                    // count it as lost and keep the connection alive rather than
                    // letting Inflater's exception tear the whole session down.
                    Log.w(TAG, "Frame $seq failed to decompress, treating as lost: ${e.message}")
                    packetsLost++
                    continue
                }

                packetsReceived++
                if (!hasReceivedFirst) {
                    hasReceivedFirst = true
                    highestSeqSeen = seq
                } else if (seq > highestSeqSeen) {
                    val gap = seq - highestSeqSeen - 1
                    if (gap > 0) packetsLost += gap
                    highestSeqSeen = seq
                }

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
                            packetsLost = packetsLost,
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
 
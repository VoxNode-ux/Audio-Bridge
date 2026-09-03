package com.audiobridge.app.network

import kotlinx.coroutines.flow.Flow

/**
 * Unifies UDP, TCP, and Bluetooth RFCOMM under one contract so AudioStreamService
 * doesn't need a protocol-specific `when` block per transport. Each chunk carries
 * its own length because the underlying array may be reused/padded by the caller.
 */
data class TransportChunk(
    val data: ByteArray,
    val length: Int,
    val stats: com.audiobridge.app.util.StreamStats = com.audiobridge.app.util.StreamStats()
) {
    // See PcmChunk's equals/hashCode doc comment (StreamModels.kt) for why this
    // compares/hashes in place instead of via data.copyOf(length).contentEquals(...)
    // — same allocation-per-comparison trap, same in-place fix; `stats` is a small
    // data class so comparing it directly is already allocation-free.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TransportChunk) return false
        if (length != other.length || stats != other.stats) return false
        for (i in 0 until length) {
            if (data[i] != other.data[i]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = 1
        for (i in 0 until length) result = 31 * result + data[i]
        result = 31 * result + length
        result = 31 * result + stats.hashCode()
        return result
    }
}

interface AudioTransport : AutoCloseable {
    /** Sender side: push one chunk of raw PCM bytes out. Suspends only if the
     *  underlying transport needs to (e.g. TCP backpressure); UDP returns fast. */
    suspend fun send(data: ByteArray, length: Int)

    /** Receiver side: opens the listening socket/channel and emits chunks as they
     *  arrive. Collecting this Flow is what actually starts listening — matches the
     *  existing UdpReceiver/TcpReceiver pattern so no behavior changes for those two. */
    fun listen(): Flow<TransportChunk>

    /** Sender-side connect step. UDP is connectionless so this is a no-op there;
     *  TCP and Bluetooth need an explicit handshake before send() is meaningful.
     *  Returns false on failure instead of throwing, so callers can surface a clean
     *  ConnectionState.ERROR without a try/catch at every call site. */
    suspend fun connect(): Boolean

    override fun close()
} 
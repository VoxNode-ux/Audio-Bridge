package com.audiobridge.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Covers StreamStats.quality — the derived connection-health indicator that
 * replaced the raw (unreliable, unsynchronized-clock-based) latencyMs display.
 * These thresholds are the actual contract the UI relies on; changing them
 * without updating this test is a signal the UI's meaning is silently shifting.
 */
class StreamStatsQualityTest {

    @Test
    fun `no packets yet reports unknown`() {
        val stats = StreamStats()
        assertEquals(ConnectionQuality.UNKNOWN, stats.quality)
    }

    @Test
    fun `zero loss and low jitter is excellent`() {
        val stats = StreamStats(
            jitterMs = 5.0,
            packetsReceived = 1000,
            packetsLost = 0
        )
        assertEquals(ConnectionQuality.EXCELLENT, stats.quality)
    }

    @Test
    fun `small loss rate is good`() {
        val stats = StreamStats(
            jitterMs = 5.0,
            packetsReceived = 980,
            packetsLost = 5 // ~0.5% loss
        )
        assertEquals(ConnectionQuality.GOOD, stats.quality)
    }

    @Test
    fun `moderate loss rate is fair`() {
        val stats = StreamStats(
            jitterMs = 5.0,
            packetsReceived = 950,
            packetsLost = 60 // ~6% loss
        )
        assertEquals(ConnectionQuality.FAIR, stats.quality)
    }

    @Test
    fun `high loss rate is poor`() {
        val stats = StreamStats(
            jitterMs = 5.0,
            packetsReceived = 800,
            packetsLost = 200 // 20% loss
        )
        assertEquals(ConnectionQuality.POOR, stats.quality)
    }

    @Test
    fun `high jitter alone is poor even with zero loss`() {
        val stats = StreamStats(
            jitterMs = 100.0,
            packetsReceived = 1000,
            packetsLost = 0
        )
        assertEquals(ConnectionQuality.POOR, stats.quality)
    }

    @Test
    fun `moderate jitter alone is fair even with zero loss`() {
        val stats = StreamStats(
            jitterMs = 50.0,
            packetsReceived = 1000,
            packetsLost = 0
        )
        assertEquals(ConnectionQuality.FAIR, stats.quality)
    }
}

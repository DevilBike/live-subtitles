package dev.bike.livesubs.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LatencyStatsTest {

    @Test
    fun averagesOverTheLastSamplesOnly() {
        val stats = LatencyStats(size = 2)
        stats.recordPass(passMs = 100, lagMs = 1_000)
        stats.recordPass(passMs = 200, lagMs = 2_000)
        stats.recordPass(passMs = 400, lagMs = 3_000)
        val s = stats.snapshot()
        assertEquals(3, s.passes)
        assertEquals(400, s.lastPassMs)
        assertEquals(300, s.avgPassMs)
        assertEquals(2_500, s.avgLagMs)
        assertNull(s.avgSettleMs)
    }

    @Test
    fun settleAverage() {
        val stats = LatencyStats()
        stats.recordSettle(1_000)
        stats.recordSettle(3_000)
        assertEquals(2_000L, stats.snapshot().avgSettleMs)
    }
}

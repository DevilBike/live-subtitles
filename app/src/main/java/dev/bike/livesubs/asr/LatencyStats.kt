package dev.bike.livesubs.asr

/**
 * Rolling latency figures over the last [size] samples, for the screen and logcat.
 *
 * - pass: how long one whisper.cpp run takes. Above the 500 ms hop, the
 *   recognizer cannot keep up and skips windows.
 * - lag: from the end of a window's audio to its text being on screen, i.e.
 *   how far tentative text trails the newest audio it covers.
 * - settle: from the end of a spoken word to that word being committed.
 */
class LatencyStats(private val size: Int = 20) {

    data class Snapshot(
        val passes: Int,
        val lastPassMs: Long,
        val avgPassMs: Long,
        val avgLagMs: Long,
        val avgSettleMs: Long?,
    )

    private val passMs = ArrayDeque<Long>()
    private val lagMs = ArrayDeque<Long>()
    private val settleMs = ArrayDeque<Long>()
    private var passes = 0
    private var lastPass = 0L

    fun recordPass(passMs: Long, lagMs: Long) {
        passes++
        lastPass = passMs
        push(this.passMs, passMs)
        push(this.lagMs, lagMs)
    }

    fun recordSettle(ms: Long) = push(settleMs, ms)

    fun snapshot() = Snapshot(
        passes = passes,
        lastPassMs = lastPass,
        avgPassMs = passMs.averageOrZero(),
        avgLagMs = lagMs.averageOrZero(),
        avgSettleMs = if (settleMs.isEmpty()) null else settleMs.averageOrZero(),
    )

    private fun push(q: ArrayDeque<Long>, v: Long) {
        q.addLast(v)
        if (q.size > size) q.removeFirst()
    }

    private fun ArrayDeque<Long>.averageOrZero() = if (isEmpty()) 0L else sum() / size
}

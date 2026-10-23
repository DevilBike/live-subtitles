package dev.bike.livesubs.asr

/** One recognized English word, timed on the capture clock ([android.os.SystemClock.elapsedRealtime] ms). */
data class Word(val text: String, val startMs: Long, val endMs: Long)

/**
 * Joins whisper.cpp text tokens into words. A token that starts with a space
 * begins a new word; any other token (", "'s", the rest of a split word or of a
 * split UTF-8 character) continues the current one.
 *
 * [tokenTimes] holds a `t0, t1` pair per token in centiseconds from the start
 * of the pass audio, which itself began at [audioStartMs].
 */
fun wordsFromTokens(tokenTexts: List<ByteArray>, tokenTimes: LongArray, audioStartMs: Long): List<Word> {
    require(tokenTimes.size == tokenTexts.size * 2) { "need a t0,t1 pair per token" }
    val words = ArrayList<Word>()
    var bytes = ByteArray(0)
    var start = 0L
    var end = 0L

    fun flush() {
        val text = String(bytes, Charsets.UTF_8).trim()
        if (text.isNotEmpty()) words += Word(text, start, maxOf(start, end))
        bytes = ByteArray(0)
    }

    for (i in tokenTexts.indices) {
        val token = tokenTexts[i]
        val t0 = audioStartMs + tokenTimes[2 * i] * 10
        val t1 = audioStartMs + tokenTimes[2 * i + 1] * 10
        if (bytes.isEmpty() || (token.isNotEmpty() && token[0] == ' '.code.toByte())) {
            flush()
            start = t0
            end = t1
        } else {
            end = maxOf(end, t1)
        }
        bytes += token
    }
    flush()
    return words
}

/** Case and punctuation do not count when comparing words across passes. */
internal fun Word.key(): String = text.filter { it.isLetterOrDigit() }.lowercase()

package dev.bike.livesubs.asr

/**
 * Turns overlapping recognizer passes into committed text, which never changes
 * again, and tentative text for the newest audio.
 *
 * Each pass re-recognizes the whole sliding window, so it repeats words that
 * are already committed and may revise the newest ones. A word is committed
 * once two passes in a row agree on it (LocalAgreement-2, as in
 * whisper_streaming), when it is about to slide out of the window, or when
 * speech pauses and the pass is final.
 */
class TranscriptStabilizer(private val keepChars: Int = 1_000) {

    class Result(val newlyCommitted: List<Word>, val tentative: List<Word>)

    private val committedWords = ArrayDeque<Word>()
    private val text = StringBuilder()
    private var committedEndMs = Long.MIN_VALUE
    private var previous: List<Word> = emptyList()

    /** The most recent committed text. Older text is trimmed from the front past [keepChars]. */
    val committedText: String get() = text.toString()

    /**
     * Adds one pass's words (all words recognized in the window, in order).
     *
     * [cutoffMs] is where the next window will start: words beginning before it
     * will be cut off next time, so they are committed now rather than lost.
     * Pass [final] when speech has paused, to commit everything.
     */
    fun update(words: List<Word>, cutoffMs: Long = Long.MIN_VALUE, final: Boolean = false): Result {
        val hypothesis = dropCommitted(words)
        val count = if (final) {
            hypothesis.size
        } else {
            maxOf(agreedPrefix(previous, hypothesis), hypothesis.takeWhile { it.startMs < cutoffMs }.size)
        }
        val newlyCommitted = hypothesis.subList(0, count).toList()
        newlyCommitted.forEach(::commit)
        val tentative = hypothesis.drop(count)
        previous = if (final) emptyList() else tentative
        return Result(newlyCommitted, tentative)
    }

    /** Commits whatever is still tentative, e.g. when the audio it came from has been dropped. */
    fun flush(): List<Word> {
        val words = previous
        words.forEach(::commit)
        previous = emptyList()
        return words
    }

    val hasTentative: Boolean get() = previous.isNotEmpty()

    private fun commit(word: Word) {
        committedWords.addLast(word)
        while (committedWords.size > MAX_KEPT_WORDS) committedWords.removeFirst()
        if (text.isNotEmpty()) text.append(' ')
        text.append(word.text)
        if (text.length > keepChars) {
            val cut = text.indexOf(" ", text.length - keepChars)
            if (cut > 0) text.delete(0, cut + 1)
        }
        committedEndMs = maxOf(committedEndMs, word.endMs)
    }

    /** Removes the words of a pass that repeat already committed text. */
    private fun dropCommitted(words: List<Word>): List<Word> {
        if (committedWords.isEmpty()) return words

        // First by text: the pass usually starts by repeating the end of the committed text.
        // Allow a couple of older words before the repeat, but trust a single-word match
        // only right at the start, since short words ("the") repeat by chance.
        val tail = committedWords.takeLast(MAX_OVERLAP_WORDS).map { it.key() }
        val near = words.filter { it.endMs > committedEndMs - OVERLAP_SLACK_MS }
        for (k in minOf(tail.size, near.size) downTo 1) {
            val suffix = tail.subList(tail.size - k, tail.size)
            for (i in 0..minOf(MAX_SKIPPED_WORDS, near.size - k)) {
                if (k == 1 && i > 0) continue
                if ((0 until k).all { near[i + it].key() == suffix[it] }) return near.drop(i + k)
            }
        }
        // Otherwise by time: keep words that lie mostly after the last committed one.
        return words.filter { (it.startMs + it.endMs) / 2 > committedEndMs }
    }

    private fun agreedPrefix(a: List<Word>, b: List<Word>): Int {
        var n = 0
        while (n < a.size && n < b.size && a[n].key() == b[n].key()) n++
        return n
    }

    private companion object {
        const val MAX_KEPT_WORDS = 64
        const val MAX_OVERLAP_WORDS = 6
        const val MAX_SKIPPED_WORDS = 2
        /** Whisper's word timestamps are rough, so look this far back for repeats. */
        const val OVERLAP_SLACK_MS = 1_500L
    }
}

package dev.bike.livesubs.asr

/**
 * One recognized word, timed on the capture clock ([android.os.SystemClock.elapsedRealtime] ms).
 *
 * In Japanese, and within Korean words, a "word" is a single token (often one
 * to three characters), since the text has no spaces to split on.
 * [spaceBefore] says whether it is separated from the previous word by a space.
 */
data class Word(val text: String, val startMs: Long, val endMs: Long, val spaceBefore: Boolean = true)

/** Joins words back into text, with spaces only where the original had them. */
fun List<Word>.joinText(): String = buildString {
    for (word in this@joinText) {
        if (isNotEmpty() && word.spaceBefore) append(' ')
        append(word.text)
    }
}

/**
 * Joins whisper.cpp text tokens into words. A token that starts with a space
 * begins a new word. So does a token next to Japanese or Korean script, once
 * the previous character is complete. Any other token (", "'s", the rest of a
 * split English word or of a split UTF-8 character) continues the current word.
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
        val raw = String(bytes, Charsets.UTF_8)
        val text = raw.trim()
        if (text.isNotEmpty()) words += Word(text, start, maxOf(start, end), spaceBefore = raw.startsWith(' '))
        bytes = ByteArray(0)
    }

    for (i in tokenTexts.indices) {
        val token = tokenTexts[i]
        val t0 = audioStartMs + tokenTimes[2 * i] * 10
        val t1 = audioStartMs + tokenTimes[2 * i + 1] * 10
        val startsWord = bytes.isEmpty() ||
            (token.isNotEmpty() && token[0] == ' '.code.toByte()) ||
            (token.isNotEmpty() && endsCompleteChar(bytes) && (isCjkLead(token[0]) || endsWithCjk(bytes)))
        if (startsWord) {
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

/** UTF-8 lead bytes E3..ED start U+3000..U+DFFF: kana, CJK ideographs, Hangul, CJK punctuation. */
private fun isCjkLead(b: Byte) = (b.toInt() and 0xFF) in 0xE3..0xED

private fun isContinuation(b: Byte) = (b.toInt() and 0xC0) == 0x80

private fun sequenceLength(lead: Byte): Int {
    val v = lead.toInt() and 0xFF
    return when {
        v < 0x80 -> 1
        v >= 0xF0 -> 4
        v >= 0xE0 -> 3
        else -> 2
    }
}

/** True when [bytes] does not end in the middle of a multi-byte character. */
private fun endsCompleteChar(bytes: ByteArray): Boolean {
    var i = bytes.size - 1
    while (i >= 0 && isContinuation(bytes[i])) i--
    return i < 0 || bytes.size - i == sequenceLength(bytes[i])
}

private fun endsWithCjk(bytes: ByteArray): Boolean {
    var i = bytes.size - 1
    while (i >= 0 && isContinuation(bytes[i])) i--
    return i >= 0 && isCjkLead(bytes[i])
}

/** Case and punctuation do not count when comparing words across passes. */
internal fun Word.key(): String = text.filter { it.isLetterOrDigit() }.lowercase()

/**
 * Removes immediate repeats of a phrase of [minWords] or more words, keeping
 * the first copy: "I'm going to the bathroom. I'm going to the bathroom."
 * becomes one sentence. Whisper falls into such loops on unclear audio, and
 * without them a stuck pass would keep committing the same line.
 */
fun collapseRepeats(words: List<Word>, minWords: Int = 2): List<Word> {
    val out = ArrayList<Word>(words.size)
    for (word in words) {
        out += word
        // Does the end of `out` now hold the same phrase twice in a row?
        for (n in out.size / 2 downTo minWords) {
            val first = out.size - 2 * n
            if ((0 until n).all { out[first + it].key() == out[first + n + it].key() }) {
                repeat(n) { out.removeAt(out.size - 1) }
                break
            }
        }
    }
    return out
}

package dev.bike.livesubs.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptStabilizerTest {

    /** Words 400 ms apart starting at [startMs], each 300 ms long. */
    private fun words(text: String, startMs: Long = 0): List<Word> =
        text.split(' ').mapIndexed { i, w -> Word(w, startMs + i * 400L, startMs + i * 400L + 300) }

    private fun TranscriptStabilizer.Result.tentativeText() = tentative.joinToString(" ") { it.text }

    @Test
    fun firstPassIsAllTentative() {
        val s = TranscriptStabilizer()
        val r = s.update(words("the cat sat"))
        assertEquals("", s.committedText)
        assertEquals("the cat sat", r.tentativeText())
    }

    @Test
    fun commitsWhatTwoPassesAgreeOn() {
        val s = TranscriptStabilizer()
        s.update(words("the cat sat"))
        val r = s.update(words("the cat sat on the"))
        assertEquals("the cat sat", s.committedText)
        assertEquals("on the", r.tentativeText())
    }

    @Test
    fun agreementIgnoresCaseAndPunctuation() {
        val s = TranscriptStabilizer()
        s.update(words("Hello there"))
        s.update(words("hello, there."))
        assertEquals("hello, there.", s.committedText)
    }

    @Test
    fun revisedWordsStayTentative() {
        val s = TranscriptStabilizer()
        s.update(words("I scream"))
        val r = s.update(words("ice cream please"))
        assertEquals("", s.committedText)
        assertEquals("ice cream please", r.tentativeText())
    }

    @Test
    fun repeatedCommittedWordsAreDroppedByText() {
        val s = TranscriptStabilizer()
        s.update(words("the cat sat"))
        s.update(words("the cat sat on"))
        // The window slid by one word: the pass starts mid-sentence and repeats committed words.
        val r = s.update(words("cat sat on the mat", startMs = 400))
        assertEquals("the cat sat on", s.committedText)
        assertEquals("the mat", r.tentativeText())
    }

    @Test
    fun repeatedWordsAreDroppedByTimeWhenTextDiffers() {
        val s = TranscriptStabilizer()
        s.update(words("one two three"))
        s.update(words("one two three"))
        // The new pass words the committed audio differently but its timestamps show it is old.
        val r = s.update(words("uno dos tres four", startMs = 0))
        assertEquals("one two three", s.committedText)
        assertEquals("four", r.tentativeText())
    }

    @Test
    fun wordsLeavingTheWindowAreCommittedWithoutAgreement() {
        val s = TranscriptStabilizer()
        s.update(words("alpha beta gamma"))
        // Disagrees from the first word, but the first two start before the next window does.
        val r = s.update(words("alfa betta gamma delta"), cutoffMs = 500)
        assertEquals("alfa betta", s.committedText)
        assertEquals("gamma delta", r.tentativeText())
    }

    @Test
    fun finalPassCommitsEverything() {
        val s = TranscriptStabilizer()
        s.update(words("good morning"))
        val r = s.update(words("good morning everyone"), final = true)
        assertEquals("good morning everyone", s.committedText)
        assertEquals(emptyList<Word>(), r.tentative)
        // The next sentence starts fresh rather than agreeing with the old tentative text.
        s.update(words("how are you", startMs = 3_000))
        assertEquals("good morning everyone", s.committedText)
    }

    @Test
    fun flushCommitsPendingTentativeText() {
        val s = TranscriptStabilizer()
        s.update(words("see you"))
        assertEquals(true, s.hasTentative)
        s.flush()
        assertEquals("see you", s.committedText)
        assertEquals(false, s.hasTentative)
    }

    @Test
    fun committedTextIsTrimmedAtAWordBoundary() {
        val s = TranscriptStabilizer(keepChars = 12)
        s.update(words("aaaa bbbb cccc dddd"), final = true)
        assertEquals("cccc dddd", s.committedText)
    }
}

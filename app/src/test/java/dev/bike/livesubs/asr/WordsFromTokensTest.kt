package dev.bike.livesubs.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class WordsFromTokensTest {

    private fun tokens(vararg t: String) = t.map { it.toByteArray() }

    @Test
    fun joinsSubwordTokensAndPunctuation() {
        val words = wordsFromTokens(
            tokens(" Hel", "lo", ",", " world", "'s", " end", "."),
            longArrayOf(0, 10, 10, 20, 20, 22, 30, 50, 50, 55, 60, 70, 70, 71),
            audioStartMs = 1_000,
        )
        assertEquals(listOf("Hello,", "world's", "end."), words.map { it.text })
        assertEquals(Word("Hello,", 1_000, 1_220), words[0])
        assertEquals(Word("end.", 1_600, 1_710), words[2])
    }

    @Test
    fun reassemblesUtf8SplitAcrossTokens() {
        val bytes = " café".toByteArray()
        // Split inside the two-byte "é".
        val split = listOf(bytes.copyOfRange(0, bytes.size - 1), bytes.copyOfRange(bytes.size - 1, bytes.size))
        val words = wordsFromTokens(split, longArrayOf(0, 5, 5, 10), audioStartMs = 0)
        assertEquals(listOf("café"), words.map { it.text })
    }

    @Test
    fun endNeverPrecedesStart() {
        val words = wordsFromTokens(tokens(" odd"), longArrayOf(50, 40), audioStartMs = 0)
        assertEquals(Word("odd", 500, 500), words.single())
    }

    @Test
    fun splitsJapaneseIntoTokensWithoutSpaces() {
        val words = wordsFromTokens(tokens("今日", "は", "暑い", "。"), LongArray(8) { it.toLong() }, audioStartMs = 0)
        assertEquals(listOf("今日", "は", "暑い", "。"), words.map { it.text })
        assertEquals("今日は暑い。", words.joinText())
    }

    @Test
    fun keepsAJapaneseCharacterSplitAcrossTokensTogether() {
        val bytes = "猫".toByteArray()
        val split = listOf(bytes.copyOfRange(0, 2), bytes.copyOfRange(2, 3), "だ".toByteArray())
        val words = wordsFromTokens(split, LongArray(6), audioStartMs = 0)
        assertEquals(listOf("猫", "だ"), words.map { it.text })
    }

    @Test
    fun keepsKoreanWordSpacing() {
        val words = wordsFromTokens(tokens(" 안녕", "하세요", " 여러분"), LongArray(6), audioStartMs = 0)
        assertEquals("안녕하세요 여러분", words.joinText())
    }

    @Test
    fun dropsWhitespaceOnlyWords() {
        val words = wordsFromTokens(tokens(" ", " yes"), longArrayOf(0, 1, 1, 2), audioStartMs = 0)
        assertEquals(listOf("yes"), words.map { it.text })
    }
}

class CollapseRepeatsTest {

    private fun words(text: String) = text.split(' ').mapIndexed { i, w -> Word(w, i * 100L, i * 100L + 80) }
    private fun List<Word>.text() = joinToString(" ") { it.text }

    @Test
    fun collapsesARepeatedSentence() {
        val w = words("I'm going to the bathroom. I'm going to the bathroom. I'm going to the bathroom.")
        assertEquals("I'm going to the bathroom.", collapseRepeats(w).text())
    }

    @Test
    fun keepsWhatFollowsTheLoop() {
        assertEquals("thank you very much", collapseRepeats(words("thank you thank you very much")).text())
    }

    @Test
    fun leavesSingleWordRepeatsAlone() {
        assertEquals("no no no", collapseRepeats(words("no no no")).text())
    }

    @Test
    fun leavesNormalTextAlone() {
        val w = words("the cat sat on the mat")
        assertEquals(w, collapseRepeats(w))
    }
}

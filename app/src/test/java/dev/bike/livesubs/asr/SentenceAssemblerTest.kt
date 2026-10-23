package dev.bike.livesubs.asr

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceAssemblerTest {

    /** Japanese-style units: no spaces between them. */
    private fun ja(vararg units: String) = units.map { Word(it, 0, 0, spaceBefore = false) }

    @Test
    fun completesASentenceAtJapanesePunctuation() {
        val a = SentenceAssembler()
        val r = a.update(ja("今日", "は", "暑い", "。", "明日"), ja("も"), final = false)
        assertEquals(listOf("今日は暑い。"), r.completed)
        assertEquals("明日も", r.open)
    }

    @Test
    fun holdsAnUnfinishedSentenceAcrossPasses() {
        val a = SentenceAssembler()
        assertEquals(emptyList<String>(), a.update(ja("ちょっと"), ja("待って"), final = false).completed)
        val r = a.update(ja("待って", "ください", "！"), emptyList(), final = false)
        assertEquals(listOf("ちょっと待ってください！"), r.completed)
        assertEquals("", r.open)
    }

    @Test
    fun aPauseEndsTheSentence() {
        val a = SentenceAssembler()
        val r = a.update(ja("そう", "です", "ね"), emptyList(), final = true)
        assertEquals(listOf("そうですね"), r.completed)
    }

    @Test
    fun keepsKoreanSpaces() {
        val a = SentenceAssembler()
        val words = listOf(Word("안녕", 0, 0), Word("하세요", 0, 0, spaceBefore = false), Word("여러분.", 0, 0))
        assertEquals(listOf("안녕하세요 여러분."), a.update(words, emptyList(), final = false).completed)
    }

    @Test
    fun splitsRunOnSentences() {
        val a = SentenceAssembler(maxWords = 3)
        val r = a.update(ja("あ", "い", "う", "え"), emptyList(), final = false)
        assertEquals(listOf("あいう"), r.completed)
        assertEquals("え", r.open)
    }
}

package dev.bike.livesubs.asr

/**
 * Groups committed source-language words into sentences for the translator.
 *
 * A translator does much better with a whole sentence than with the fragments
 * a 4 s window produces, so committed words wait here until a sentence ends:
 * at sentence punctuation, after a pause in speech, or after [maxWords] words
 * so a long run-on sentence still appears. The [Result.open] text (the
 * unfinished sentence plus tentative words) is translated as a preview.
 */
class SentenceAssembler(private val maxWords: Int = 40) {

    class Result(val completed: List<String>, val open: String)

    private val pending = ArrayList<Word>()

    fun update(newlyCommitted: List<Word>, tentative: List<Word>, final: Boolean): Result {
        val completed = ArrayList<String>()
        for (word in newlyCommitted) {
            pending += word
            if (word.text.last() in SENTENCE_END || pending.size >= maxWords) completed += takePending()
        }
        if (final && pending.isNotEmpty()) completed += takePending()
        return Result(completed, (pending + tentative).joinText())
    }

    private fun takePending(): String = pending.joinText().also { pending.clear() }

    private companion object {
        const val SENTENCE_END = "。！？.!?…"
    }
}

package dev.bike.livesubs.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageLockTest {

    @Test
    fun locksAfterTwoAgreeingDetections() {
        val lock = LanguageLock()
        assertEquals("auto", lock.nextPassLanguage)
        lock.observe("ja", speechMs = 2_000)
        assertNull(lock.locked)
        lock.observe("ja", speechMs = 2_000)
        assertEquals("ja", lock.locked)
        assertEquals("ja", lock.nextPassLanguage)
    }

    @Test
    fun aDisagreementRestartsTheCount() {
        val lock = LanguageLock()
        lock.observe("ja", 2_000)
        lock.observe("zh", 2_000)
        assertNull(lock.locked)
        assertEquals("zh", lock.latest)
        lock.observe("zh", 2_000)
        assertEquals("zh", lock.locked)
    }

    @Test
    fun ignoresDetectionsOnTooLittleSpeech() {
        val lock = LanguageLock()
        lock.observe("ko", 500)
        lock.observe("ko", 500)
        assertNull(lock.locked)
        assertNull(lock.latest)
    }

    @Test
    fun staysLockedOnceLocked() {
        val lock = LanguageLock()
        lock.observe("es", 2_000)
        lock.observe("es", 2_000)
        lock.observe("pt", 2_000)
        lock.observe("pt", 2_000)
        assertEquals("es", lock.locked)
    }
}
